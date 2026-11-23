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
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.kafka.status.events.config.KafkaProperties;

public class KafkaEventServiceImpl implements KafkaEventService, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaEventServiceImpl.class);

    private static final String DEFAULT_CLIENT_ID = "conductor-status-producer";

    /**
     * Upper bound for closing the producer on shutdown. Without it, close waits indefinitely for
     * unsent events, which would hold up shutdown while Kafka is unreachable.
     */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /** Certificates and keys given as PEM text in config; only file locations are supported. */
    static final List<String> INLINE_CERTIFICATE_CONFIGS =
            List.of(
                    SslConfigs.SSL_KEYSTORE_KEY_CONFIG,
                    SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG,
                    SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG);

    private static final List<String> STORE_LOCATION_CONFIGS =
            List.of(
                    SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG,
                    SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);

    private static final List<String> SERIALIZER_CONFIGS =
            List.of(
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);

    private final Properties producerConfig;
    private KafkaProducer<String, Object> kafkaProducer;

    public KafkaEventServiceImpl(KafkaProperties kafkaProperties) {
        // Built and validated here so that misconfiguration fails startup. The producer itself
        // is created on the first event, so the broker does not need to be reachable yet.
        this.producerConfig = buildProducerConfig(kafkaProperties);
    }

    /**
     * Builds the producer settings: defaults, then the user's {@code producer.*} settings, then
     * {@code bootstrap.servers}, then the serializers this service depends on.
     *
     * @throws ConfigException if a setting is invalid, a certificate is given inline, or a keystore
     *     or truststore file is not readable
     */
    static Properties buildProducerConfig(KafkaProperties kafkaProperties) {
        Properties config = new Properties();
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.CLIENT_ID_CONFIG, defaultClientId());

        kafkaProperties
                .getProducer()
                .forEach(
                        (key, value) -> {
                            if (!ProducerConfig.configNames().contains(key)) {
                                LOGGER.warn(
                                        "Unknown Kafka producer setting '{}' in"
                                                + " conductor.message-publisher.kafka.producer",
                                        key);
                            }
                            if (SERIALIZER_CONFIGS.contains(key)) {
                                LOGGER.warn(
                                        "Ignoring Kafka producer setting '{}': status events"
                                                + " always use their built-in serializers",
                                        key);
                                return;
                            }
                            config.put(key, value);
                        });

        String bootstrapServers = kafkaProperties.getBootstrapServers();
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        }
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaJsonSerializer.class.getName());

        validate(config);
        return config;
    }

    private static void validate(Properties config) {
        for (String key : INLINE_CERTIFICATE_CONFIGS) {
            if (config.containsKey(key)) {
                throw new ConfigException(
                        "Kafka producer setting '"
                                + key
                                + "' is not supported: certificates and keys must be given as"
                                + " files with "
                                + SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG
                                + " / "
                                + SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG);
            }
        }

        for (String key : STORE_LOCATION_CONFIGS) {
            String location = config.getProperty(key);
            if (location != null && !Files.isReadable(Path.of(location))) {
                throw new ConfigException(
                        "Kafka producer setting '"
                                + key
                                + "' points to '"
                                + location
                                + "', which does not exist or is not readable");
            }
        }

        // Checks types and allowed values (e.g. security.protocol) without connecting.
        ProducerConfig.configDef().parse(config);
    }

    private static String defaultClientId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            LOGGER.warn(
                    "Could not resolve local host name, using Kafka client id '{}'",
                    DEFAULT_CLIENT_ID,
                    e);
            return DEFAULT_CLIENT_ID;
        }
    }

    private synchronized KafkaProducer<String, Object> getKafkaProducer() {
        if (kafkaProducer == null) {
            try {
                kafkaProducer =
                        new KafkaProducer<>(
                                producerConfig,
                                new StringSerializer(),
                                new KafkaJsonSerializer<>());
            } catch (Exception e) {
                LOGGER.error("Failed to create producer.", e);
                throw e;
            }
        }
        return kafkaProducer;
    }

    @Override
    public <V> void produce(String key, V message, String topic) throws Exception {
        KafkaProducer<String, Object> producer = getKafkaProducer();
        final ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, message);

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

        producer.flush();
    }

    @Override
    public synchronized void close() {
        if (kafkaProducer != null) {
            kafkaProducer.close(CLOSE_TIMEOUT);
            kafkaProducer = null;
        }
    }
}
