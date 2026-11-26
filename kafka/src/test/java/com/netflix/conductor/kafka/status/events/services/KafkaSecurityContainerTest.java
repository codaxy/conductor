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

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.netflix.conductor.kafka.status.events.config.KafkaProperties;
import com.netflix.conductor.kafka.status.events.listener.TaskStatusKafkaProducer;
import com.netflix.conductor.model.TaskModel;

import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.MTLS_PORT;
import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.PLAINTEXT_PORT;
import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.SASL_PASSWORD;
import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.SASL_PLAINTEXT_PORT;
import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.SASL_SSL_PORT;
import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.SASL_USER;
import static com.netflix.conductor.kafka.status.events.services.SecureKafkaContainer.SSL_PORT;
import static com.netflix.conductor.kafka.status.events.services.TestCertificates.PASSWORD;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes status events through {@link KafkaEventServiceImpl} to a real broker over each
 * supported security mode, using the settings a user would put under {@code
 * conductor.message-publisher.kafka.producer.*}.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaSecurityContainerTest {

    @TempDir static Path certDir;

    private static TestCertificates certs;
    private static SecureKafkaContainer kafka;

    @BeforeAll
    static void startKafka() throws Exception {
        String dockerHost = DockerClientFactory.instance().dockerHostIpAddress();
        certs = TestCertificates.generate(certDir, List.of("localhost", "127.0.0.1", dockerHost));
        kafka = new SecureKafkaContainer(certs);
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void sslWithPkcs12Truststore() throws Exception {
        assertDelivered(SSL_PORT, pkcs12Truststore(Map.of("security.protocol", "SSL")));
    }

    @Test
    void sslWithPemTruststore() throws Exception {
        assertDelivered(SSL_PORT, pemTruststore(Map.of("security.protocol", "SSL")));
    }

    @Test
    void mtlsWithPkcs12() throws Exception {
        Map<String, String> settings = pkcs12Truststore(Map.of("security.protocol", "SSL"));
        settings.put("ssl.keystore.type", "PKCS12");
        settings.put("ssl.keystore.location", certs.clientKeystoreP12().toString());
        settings.put("ssl.keystore.password", PASSWORD);

        assertDelivered(MTLS_PORT, settings);
    }

    @Test
    void mtlsWithPem() throws Exception {
        Map<String, String> settings = pemTruststore(Map.of("security.protocol", "SSL"));
        settings.put("ssl.keystore.type", "PEM");
        settings.put("ssl.keystore.location", certs.clientKeystorePem().toString());

        assertDelivered(MTLS_PORT, settings);
    }

    @Test
    void saslSslWithPlain() throws Exception {
        assertDelivered(SASL_SSL_PORT, pemTruststore(sasl("SASL_SSL", "PLAIN", SASL_PASSWORD)));
    }

    @Test
    void saslSslWithScram() throws Exception {
        assertDelivered(
                SASL_SSL_PORT, pkcs12Truststore(sasl("SASL_SSL", "SCRAM-SHA-512", SASL_PASSWORD)));
    }

    @Test
    void saslPlaintextWithPlain() throws Exception {
        assertDelivered(SASL_PLAINTEXT_PORT, sasl("SASL_PLAINTEXT", "PLAIN", SASL_PASSWORD));
    }

    @Test
    void mtlsRejectsClientWithoutCertificate() {
        Throwable cause =
                sendExpectingFailure(
                        MTLS_PORT, pkcs12Truststore(Map.of("security.protocol", "SSL")));

        // With TLS 1.3 the broker rejects the missing certificate after the client's side of the
        // handshake, so the client may only see the connection drop and time out.
        assertTrue(
                cause instanceof AuthenticationException || cause instanceof TimeoutException,
                () -> "unexpected failure: " + cause);
    }

    @Test
    void saslRejectsWrongPassword() {
        Throwable cause =
                sendExpectingFailure(
                        SASL_SSL_PORT,
                        pkcs12Truststore(sasl("SASL_SSL", "SCRAM-SHA-512", "wrong-password")));

        assertInstanceOf(AuthenticationException.class, cause);
    }

    @Test
    void sslRejectsUntrustedBrokerCertificate() {
        Map<String, String> settings = new HashMap<>(Map.of("security.protocol", "SSL"));
        settings.put("ssl.truststore.type", "PKCS12");
        settings.put("ssl.truststore.location", certs.untrustedTruststoreP12().toString());
        settings.put("ssl.truststore.password", PASSWORD);

        assertInstanceOf(AuthenticationException.class, sendExpectingFailure(SSL_PORT, settings));
    }

    /** Publishes a task status event the way Conductor does and reads it back over PLAINTEXT. */
    private void assertDelivered(int port, Map<String, String> settings) throws Exception {
        String topic = "task-status-" + UUID.randomUUID();
        KafkaProperties properties = properties(port, settings);
        properties.setTaskStatusTopic(topic);
        properties.setAllowedTaskStatuses(TaskModel.Status.COMPLETED.name());

        TaskModel task = new TaskModel();
        task.setTaskId(UUID.randomUUID().toString());
        task.setTaskType("SIMPLE");
        task.setStatus(TaskModel.Status.COMPLETED);
        task.setWorkflowInstanceId(UUID.randomUUID().toString());

        try (KafkaEventServiceImpl service = new KafkaEventServiceImpl(properties)) {
            new TaskStatusKafkaProducer(service, properties).onTaskCompleted(task);
        }

        String event = readOne(topic);
        assertNotNull(event, "no status event was delivered to " + topic);
        assertTrue(event.contains(task.getTaskId()), event);
    }

    /** Sends with the same settings the service builds and returns why delivery failed. */
    private Throwable sendExpectingFailure(int port, Map<String, String> settings) {
        Properties config = KafkaEventServiceImpl.buildProducerConfig(properties(port, settings));

        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            ExecutionException e =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    producer.send(new ProducerRecord<>("rejected", "key", "value"))
                                            .get(30, TimeUnit.SECONDS));
            return e.getCause();
        }
    }

    private String readOne(String topic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(PLAINTEXT_PORT));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(500))) {
                    return record.value();
                }
            }
            return null;
        }
    }

    private static KafkaProperties properties(int port, Map<String, String> settings) {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(kafka.bootstrapServers(port));
        Map<String, String> producer = new HashMap<>(settings);
        // Fail fast instead of blocking for the 60s default when a mode is misconfigured.
        producer.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "15000");
        properties.setProducer(producer);
        return properties;
    }

    private static Map<String, String> sasl(String protocol, String mechanism, String password) {
        String loginModule =
                mechanism.equals("PLAIN")
                        ? "org.apache.kafka.common.security.plain.PlainLoginModule"
                        : "org.apache.kafka.common.security.scram.ScramLoginModule";
        return Map.of(
                "security.protocol", protocol,
                "sasl.mechanism", mechanism,
                "sasl.jaas.config",
                        loginModule
                                + " required username=\""
                                + SASL_USER
                                + "\" password=\""
                                + password
                                + "\";");
    }

    private static Map<String, String> pkcs12Truststore(Map<String, String> settings) {
        Map<String, String> result = new HashMap<>(settings);
        result.put("ssl.truststore.type", "PKCS12");
        result.put("ssl.truststore.location", certs.truststoreP12().toString());
        result.put("ssl.truststore.password", PASSWORD);
        return result;
    }

    private static Map<String, String> pemTruststore(Map<String, String> settings) {
        Map<String, String> result = new HashMap<>(settings);
        result.put("ssl.truststore.type", "PEM");
        result.put("ssl.truststore.location", certs.truststorePem().toString());
        return result;
    }
}
