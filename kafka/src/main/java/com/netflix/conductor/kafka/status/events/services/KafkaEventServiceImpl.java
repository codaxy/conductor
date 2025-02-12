/*
 * Copyright 2024 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.kafka.status.events.services;

import java.net.InetAddress;
import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.netflix.conductor.kafka.status.events.config.KafkaProperties;

public class KafkaEventServiceImpl implements KafkaEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventServiceImpl.class);
    private static KafkaProducer kafkaProducer;

    private static synchronized <V> KafkaProducer<String, V> getKafkaProducer(
            KafkaProperties kafkaProperties) throws Exception {
        if (kafkaProducer == null) {
            Properties producerConfig = new Properties();
            producerConfig.put(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
            producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
            producerConfig.put(
                    ProducerConfig.CLIENT_ID_CONFIG, InetAddress.getLocalHost().getHostName());
            producerConfig.put(
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

            KafkaJsonSerializer<V> jsonSerializer = new KafkaJsonSerializer<>();
            try (KafkaProducer<String, V> producer =
                    new KafkaProducer<>(producerConfig, new StringSerializer(), jsonSerializer)) {
                kafkaProducer = producer;
            } catch (Exception e) {
                LOGGER.error("Failed to create producer.", e);
                throw e;
            }
        }

        return kafkaProducer;
    }

    @Autowired private final KafkaProperties kafkaProperties;

    public KafkaEventServiceImpl(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public <V> void produce(String key, V message, String topic) throws Exception {
        KafkaProducer<String, V> producer = getKafkaProducer(kafkaProperties);
        final ProducerRecord<String, V> record = new ProducerRecord<>(topic, key, message);

        producer.send(
                record,
                (metadata, exception) -> {
                    if (exception != null) {
                        LOGGER.error("Failed to send message to Kafka topic: {}", topic, exception);
                    } else {
                        LOGGER.info(
                                "Message sent to topic: {} with offset: {}",
                                topic,
                                metadata.offset());
                    }
                });
    }
}
