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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.netflix.conductor.kafka.status.events.config.KafkaProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaEventServiceImplTest {

    private static final String JAAS_PLAIN =
            "org.apache.kafka.common.security.plain.PlainLoginModule required"
                    + " username=\"conductor\" password=\"secret\";";
    private static final String JAAS_SCRAM =
            "org.apache.kafka.common.security.scram.ScramLoginModule required"
                    + " username=\"conductor\" password=\"secret\";";

    @TempDir Path tempDir;

    private final List<LogEvent> warnings = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void captureWarnings() {
        appender =
                new AbstractAppender("capture", null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(LogEvent event) {
                        if (event.getLevel() == Level.WARN
                                && KafkaEventServiceImpl.class
                                        .getName()
                                        .equals(event.getLoggerName())) {
                            warnings.add(event.toImmutable());
                        }
                    }
                };
        appender.start();
        loggerConfig().addAppender(appender, Level.WARN, null);
        loggerContext().updateLoggers();
    }

    @AfterEach
    void stopCapturingWarnings() {
        loggerConfig().removeAppender(appender.getName());
        loggerContext().updateLoggers();
        appender.stop();
    }

    @Test
    void defaultsToUnencryptedConnection() {
        Properties config = KafkaEventServiceImpl.buildProducerConfig(properties(Map.of()));

        assertEquals("all", config.get(ProducerConfig.ACKS_CONFIG));
        assertNotNull(config.get(ProducerConfig.CLIENT_ID_CONFIG));
        assertEquals("kafka:9092", config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals(
                StringSerializer.class.getName(),
                config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(
                KafkaJsonSerializer.class.getName(),
                config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertFalse(config.containsKey("security.protocol"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void sslWithPkcs12Truststore() throws IOException {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SSL",
                        "ssl.truststore.type", "PKCS12",
                        "ssl.truststore.location", file("truststore.p12"),
                        "ssl.truststore.password", "changeit");

        assertPassedThrough(producer);
    }

    @Test
    void sslWithPemTruststore() throws IOException {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SSL",
                        "ssl.truststore.type", "PEM",
                        "ssl.truststore.location", file("ca.crt"));

        assertPassedThrough(producer);
    }

    @Test
    void mtlsWithPkcs12() throws IOException {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SSL",
                        "ssl.truststore.type", "PKCS12",
                        "ssl.truststore.location", file("truststore.p12"),
                        "ssl.truststore.password", "changeit",
                        "ssl.keystore.type", "PKCS12",
                        "ssl.keystore.location", file("client.p12"),
                        "ssl.keystore.password", "changeit");

        assertPassedThrough(producer);
    }

    @Test
    void mtlsWithPem() throws IOException {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SSL",
                        "ssl.truststore.type", "PEM",
                        "ssl.truststore.location", file("ca.crt"),
                        "ssl.keystore.type", "PEM",
                        "ssl.keystore.location", file("client.pem"));

        assertPassedThrough(producer);
    }

    @Test
    void saslSslWithPlain() throws IOException {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SASL_SSL",
                        "sasl.mechanism", "PLAIN",
                        "sasl.jaas.config", JAAS_PLAIN,
                        "ssl.truststore.type", "PEM",
                        "ssl.truststore.location", file("ca.crt"));

        assertPassedThrough(producer);
    }

    @Test
    void saslSslWithScram() throws IOException {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SASL_SSL",
                        "sasl.mechanism", "SCRAM-SHA-512",
                        "sasl.jaas.config", JAAS_SCRAM,
                        "ssl.truststore.type", "PKCS12",
                        "ssl.truststore.location", file("truststore.p12"),
                        "ssl.truststore.password", "changeit");

        assertPassedThrough(producer);
    }

    @Test
    void saslPlaintext() {
        Map<String, String> producer =
                Map.of(
                        "security.protocol", "SASL_PLAINTEXT",
                        "sasl.mechanism", "PLAIN",
                        "sasl.jaas.config", JAAS_PLAIN);

        assertPassedThrough(producer);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssl.keystore.key",
                "ssl.keystore.certificate.chain",
                "ssl.truststore.certificates"
            })
    void rejectsInlineCertificates(String key) {
        String pem = "-----BEGIN PRIVATE KEY-----\nMIIsecret\n-----END PRIVATE KEY-----";

        ConfigException e =
                assertThrows(
                        ConfigException.class,
                        () -> new KafkaEventServiceImpl(properties(Map.of(key, pem))));

        assertTrue(e.getMessage().contains(key), e.getMessage());
        assertTrue(e.getMessage().contains("ssl.keystore.location"), e.getMessage());
        assertFalse(e.getMessage().contains("MIIsecret"), "must not echo the key material");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ssl.keystore.location", "ssl.truststore.location"})
    void rejectsMissingStoreFile(String key) {
        String missing = tempDir.resolve("missing.p12").toString();

        ConfigException e =
                assertThrows(
                        ConfigException.class,
                        () -> new KafkaEventServiceImpl(properties(Map.of(key, missing))));

        assertTrue(e.getMessage().contains(key), e.getMessage());
        assertTrue(e.getMessage().contains(missing), e.getMessage());
    }

    @Test
    void rejectsInvalidSecurityProtocol() {
        assertThrows(
                ConfigException.class,
                () -> new KafkaEventServiceImpl(properties(Map.of("security.protocol", "MTLS"))));
    }

    @Test
    void warnsAboutUnknownSettingsAndKeepsThem() {
        Properties config =
                KafkaEventServiceImpl.buildProducerConfig(
                        properties(Map.of("ssl.keystore.locaton", "/tmp/client.p12")));

        assertEquals("/tmp/client.p12", config.get("ssl.keystore.locaton"));
        assertTrue(
                warnings.stream()
                        .anyMatch(
                                event ->
                                        event.getMessage()
                                                .getFormattedMessage()
                                                .contains("ssl.keystore.locaton")),
                "expected a warning naming the unknown setting");
    }

    @Test
    void serializersCannotBeOverridden() {
        Properties config =
                KafkaEventServiceImpl.buildProducerConfig(
                        properties(
                                Map.of(
                                        "key.serializer", "com.example.Custom",
                                        "value.serializer", "com.example.Custom")));

        assertEquals(
                StringSerializer.class.getName(),
                config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(
                KafkaJsonSerializer.class.getName(),
                config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(2, warnings.size());
    }

    @Test
    void producerSettingsOverrideDefaults() {
        Properties config =
                KafkaEventServiceImpl.buildProducerConfig(
                        properties(Map.of("acks", "1", "client.id", "conductor-a")));

        assertEquals("1", config.get(ProducerConfig.ACKS_CONFIG));
        assertEquals("conductor-a", config.get(ProducerConfig.CLIENT_ID_CONFIG));
    }

    @Test
    void bootstrapServersSettingWinsOverProducerSetting() {
        Properties config =
                KafkaEventServiceImpl.buildProducerConfig(
                        properties(Map.of("bootstrap.servers", "other:9092")));

        assertEquals("kafka:9092", config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    void producerBootstrapServersUsedWhenSettingIsBlank() {
        KafkaProperties properties = properties(Map.of("bootstrap.servers", "other:9092"));
        properties.setBootstrapServers("");

        Properties config = KafkaEventServiceImpl.buildProducerConfig(properties);

        assertEquals("other:9092", config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    private void assertPassedThrough(Map<String, String> producer) {
        Properties config = KafkaEventServiceImpl.buildProducerConfig(properties(producer));

        producer.forEach((key, value) -> assertEquals(value, config.get(key), key));
        // Kafka accepts the full set of settings, including its cross-setting checks.
        new ProducerConfig(config);
        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
    }

    private KafkaProperties properties(Map<String, String> producer) {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers("kafka:9092");
        properties.setProducer(new HashMap<>(producer));
        return properties;
    }

    private String file(String name) throws IOException {
        return Files.writeString(tempDir.resolve(name), "placeholder").toString();
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LogManager.getContext(false);
    }

    private static org.apache.logging.log4j.core.config.LoggerConfig loggerConfig() {
        return loggerContext()
                .getConfiguration()
                .getLoggerConfig(KafkaEventServiceImpl.class.getName());
    }
}
