/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafka.schemaregistry.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.confluent.kafka.schemaregistry.storage.exceptions.SerializationException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreTimeoutException;
import io.confluent.kafka.schemaregistry.storage.serialization.Serializer;
import kafka.common.MessageSizeTooLargeException;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.ConsumerTimeoutException;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.javaapi.consumer.ZookeeperConsumerConnector;
import kafka.message.MessageAndMetadata;
import kafka.utils.ShutdownableThread;
import kafka.utils.ZkUtils;
import scala.Option;

public class KafkaStoreReaderThread<K, V> extends ShutdownableThread {

  private static final Logger log = LoggerFactory.getLogger(KafkaStoreReaderThread.class);

  private final String topic;
  private final String groupId;
  private final StoreUpdateHandler<K, V> storeUpdateHandler;
  private final Serializer<K, V> serializer;
  private final Store<K, V> localStore;
  private final long commitInterval;
  private final ReentrantLock offsetUpdateLock;
  private final Condition offsetReachedThreshold;
  private ConsumerIterator<byte[], byte[]> consumerIterator;
  private ConsumerConnector consumer;
  private long offsetInSchemasTopic = -1L;
  private long lastCommitTime = 0L;
  // Noop key is only used to help reliably determine last offset; reader thread ignores 
  // messages with this key
  private final K noopKey;

  public KafkaStoreReaderThread(ZkUtils zkUtils,
                                String kafkaClusterZkUrl,
                                String topic,
                                String groupId,
                                int commitInterval,
                                StoreUpdateHandler<K, V> storeUpdateHandler,
                                Serializer<K, V> serializer,
                                Store<K, V> localStore,
                                K noopKey) {
    super("kafka-store-reader-thread-" + topic, false);  // this thread is not interruptible
    offsetUpdateLock = new ReentrantLock();
    offsetReachedThreshold = offsetUpdateLock.newCondition();
    this.topic = topic;
    this.groupId = groupId;
    this.storeUpdateHandler = storeUpdateHandler;
    this.serializer = serializer;
    this.localStore = localStore;
    this.commitInterval = commitInterval;
    this.noopKey = noopKey;

    offsetInSchemasTopic = offsetOfLastConsumedMessage(zkUtils, groupId, topic);
    log.info("Initialized the consumer offset to " + offsetInSchemasTopic);
    Properties consumerProps = new Properties();
    consumerProps.put("group.id", this.groupId);
    consumerProps.put("client.id", "KafkaStore-reader-" + this.topic);
    consumerProps.put("zookeeper.connect", kafkaClusterZkUrl);
    consumerProps.put("auto.offset.reset", "smallest");
    consumerProps.put("auto.commit.enable", "false");
    consumer = new ZookeeperConsumerConnector(new ConsumerConfig(consumerProps));
    Map<String, Integer> kafkaStreamConfig = new HashMap<String, Integer>();
    kafkaStreamConfig.put(topic, 1);
    Map<String, List<KafkaStream<byte[], byte[]>>> streams =
        consumer.createMessageStreams(kafkaStreamConfig);
    List<KafkaStream<byte[], byte[]>> streamsForTheLogTopic = streams.get(topic);
    // there should be only one kafka partition and hence only one stream
    if (streamsForTheLogTopic != null && streamsForTheLogTopic.size() != 1) {
      throw new IllegalArgumentException("Unable to subscribe to the Kafka topic " + topic +
                                         " backing this data store. Topic may not exist.");
    }
    KafkaStream<byte[], byte[]> stream = streamsForTheLogTopic.get(0);
    consumerIterator = stream.iterator();
    log.debug("Kafka store reader thread started with consumer properties " +
              consumerProps.toString());
  }

  /**
   * Fetch the offset of the last consumed message from ZK.
   */
  private long offsetOfLastConsumedMessage(ZkUtils zkUtils, String group, String topic) {
    Option<String> committedOffsetStringOpt = zkUtils.readDataMaybeNull(
        String.format("/consumers/%s/offsets/%s/0", group, topic))._1();
    if (committedOffsetStringOpt.isEmpty()) {
      return -1L;
    } else {
      // the offset of the last consumed message is always one less than the last committed offset
      return Long.parseLong(committedOffsetStringOpt.get()) - 1;
    }
  }

  @Override
  public void doWork() {
    try {
      if (consumerIterator.hasNext()) {
        MessageAndMetadata<byte[], byte[]> messageAndMetadata = consumerIterator.next();
        byte[] messageBytes = messageAndMetadata.message();
        K messageKey = null;
        try {
          messageKey = this.serializer.deserializeKey(messageAndMetadata.key());
        } catch (SerializationException e) {
          log.error("Failed to deserialize the schema or config key", e);
        }
        
        if (messageKey.equals(noopKey)) {
          // If it's a noop, update local offset counter and do nothing else
          try {
            offsetUpdateLock.lock();
            offsetInSchemasTopic = messageAndMetadata.offset();
            offsetReachedThreshold.signalAll();
          } finally {
            offsetUpdateLock.unlock();
          }
        } else {
          V message = null;
          try {
            message =
                messageBytes == null ? null : serializer.deserializeValue(messageKey, messageBytes);
          } catch (SerializationException e) {
            log.error("Failed to deserialize a schema or config update", e);
          }
          try {
            log.trace("Applying update (" + messageKey + "," + message + ") to the local " +
                      "store");
            if (message == null) {
              localStore.delete(messageKey);
            } else {
              localStore.put(messageKey, message);
            }
            this.storeUpdateHandler.handleUpdate(messageKey, message);
            try {
              offsetUpdateLock.lock();
              offsetInSchemasTopic = messageAndMetadata.offset();
              offsetReachedThreshold.signalAll();
            } finally {
              offsetUpdateLock.unlock();
            }
          } catch (StoreException se) {
            log.error("Failed to add record from the Kafka topic" + topic + " the local store");
          }
        }
      }
    } catch (ConsumerTimeoutException cte) {
      // Expect ConsumerTimeout == -1, so this *should* never happen
      throw new IllegalStateException(
          "KafkaStoreReaderThread's ConsumerIterator timed out despite expected infinite timeout.");
    } catch (MessageSizeTooLargeException mstle) {
      throw new IllegalStateException(
          "ConsumerIterator threw MessageSizeTooLargeException. A schema has been written that "
          + "exceeds the default maximum fetch size.", mstle);
    } catch (RuntimeException e) {
      log.error("KafkaStoreReader thread has died for an unknown reason.");
      throw new RuntimeException(e);
    }
    
    if (commitInterval > 0 && System.currentTimeMillis() - lastCommitTime > commitInterval) {
      log.debug("Committing offsets");
      consumer.commitOffsets(true);
    }
  }

  @Override
  public void shutdown() {
    log.debug("Starting shutdown of KafkaStoreReaderThread.");
    if (consumer != null) {
      consumer.shutdown();
    }
    if (localStore != null) {
      localStore.close();
    }
    super.shutdown();
    log.info("KafkaStoreReaderThread shutdown complete.");
  }

  public void waitUntilOffset(long offset, long timeout, TimeUnit timeUnit) throws StoreException {
    if (offset < 0) {
      throw new StoreException("KafkaStoreReaderThread can't wait for a negative offset.");
    }
    
    try {
      offsetUpdateLock.lock();
      long timeoutNs = TimeUnit.NANOSECONDS.convert(timeout, timeUnit);
      while ((offsetInSchemasTopic < offset) && (timeoutNs > 0)) {
        try {
          timeoutNs = offsetReachedThreshold.awaitNanos(timeoutNs);
        } catch (InterruptedException e) {
          log.debug("Interrupted while waiting for the background store reader thread to reach"
                    + " the specified offset: " + offset, e);
        }
      }
    } finally {
      offsetUpdateLock.unlock();
    }
    
    if (offsetInSchemasTopic < offset) {
      throw new StoreTimeoutException(
          "KafkaStoreReaderThread failed to reach target offset within the timeout interval. "
          + "targetOffset: " + offset + ", offsetReached: " + offsetInSchemasTopic 
          + ", timeout(ms): " + TimeUnit.MILLISECONDS.convert(timeout, timeUnit));
    }
  }
}
