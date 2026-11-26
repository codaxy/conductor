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

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.github.dockerjava.api.command.InspectContainerResponse;

/**
 * Single-node KRaft broker with one listener per security mode. The broker is started by a script
 * copied in once Docker has assigned host ports, so the advertised listeners can use them.
 */
class SecureKafkaContainer extends GenericContainer<SecureKafkaContainer> {

    /** Matches the kafka-clients version the project uses. */
    static final DockerImageName IMAGE = DockerImageName.parse("apache/kafka:3.9.1");

    /** Unauthenticated; used by the tests only to read messages back. */
    static final int PLAINTEXT_PORT = 9092;

    /** SSL without client certificates. */
    static final int SSL_PORT = 9094;

    /** SSL that requires a client certificate signed by the test CA. */
    static final int MTLS_PORT = 9095;

    /** SASL_SSL with PLAIN and SCRAM-SHA-512. */
    static final int SASL_SSL_PORT = 9096;

    /** SASL_PLAINTEXT with PLAIN. */
    static final int SASL_PLAINTEXT_PORT = 9097;

    static final String SASL_USER = "conductor";
    static final String SASL_PASSWORD = "conductor-secret";

    private static final int CONTROLLER_PORT = 9093;
    private static final int BROKER_PORT = 9098;
    private static final String SECRETS = "/etc/kafka/secrets";
    private static final String STARTER = "/tmp/start-kafka.sh";

    SecureKafkaContainer(TestCertificates certs) {
        super(IMAGE);
        withExposedPorts(PLAINTEXT_PORT, SSL_PORT, MTLS_PORT, SASL_SSL_PORT, SASL_PLAINTEXT_PORT);
        withCopyFileToContainer(
                MountableFile.forHostPath(certs.brokerKeystore(), 0644), SECRETS + "/broker.p12");
        withCopyFileToContainer(
                MountableFile.forHostPath(certs.truststoreP12(), 0644),
                SECRETS + "/truststore.p12");
        withCommand("sh", "-c", "while [ ! -f " + STARTER + " ]; do sleep 0.1; done; " + STARTER);
        waitingFor(
                Wait.forLogMessage(".*Kafka Server started.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    String bootstrapServers(int port) {
        return getHost() + ":" + getMappedPort(port);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        String brokerHost = containerInfo.getConfig().getHostName();
        copyFileToContainer(
                Transferable.of(serverProperties(brokerHost), 0644), "/tmp/server.properties");
        copyFileToContainer(Transferable.of(starterScript(), 0755), STARTER);
    }

    private String starterScript() {
        String scram = "SCRAM-SHA-512=[name=" + SASL_USER + ",password=" + SASL_PASSWORD + "]";
        return String.join(
                "\n",
                "#!/bin/bash",
                "set -e",
                "/opt/kafka/bin/kafka-storage.sh format -c /tmp/server.properties"
                        + " -t \"$(/opt/kafka/bin/kafka-storage.sh random-uuid)\""
                        + " --add-scram '"
                        + scram
                        + "'",
                "exec /opt/kafka/bin/kafka-server-start.sh /tmp/server.properties",
                "");
    }

    private String serverProperties(String brokerHost) {
        String plainUsers =
                "org.apache.kafka.common.security.plain.PlainLoginModule required"
                        + " user_"
                        + SASL_USER
                        + "=\""
                        + SASL_PASSWORD
                        + "\";";
        return String.join(
                "\n",
                "process.roles=broker,controller",
                "node.id=1",
                "controller.quorum.voters=1@localhost:" + CONTROLLER_PORT,
                "controller.listener.names=CONTROLLER",
                "inter.broker.listener.name=BROKER",
                "listeners="
                        + String.join(
                                ",",
                                "CONTROLLER://localhost:" + CONTROLLER_PORT,
                                "BROKER://0.0.0.0:" + BROKER_PORT,
                                "PLAINTEXT://0.0.0.0:" + PLAINTEXT_PORT,
                                "SSLONEWAY://0.0.0.0:" + SSL_PORT,
                                "MTLS://0.0.0.0:" + MTLS_PORT,
                                "SASLSSL://0.0.0.0:" + SASL_SSL_PORT,
                                "SASLPLAIN://0.0.0.0:" + SASL_PLAINTEXT_PORT),
                "advertised.listeners="
                        + String.join(
                                ",",
                                "BROKER://" + brokerHost + ":" + BROKER_PORT,
                                "PLAINTEXT://" + bootstrapServers(PLAINTEXT_PORT),
                                "SSLONEWAY://" + bootstrapServers(SSL_PORT),
                                "MTLS://" + bootstrapServers(MTLS_PORT),
                                "SASLSSL://" + bootstrapServers(SASL_SSL_PORT),
                                "SASLPLAIN://" + bootstrapServers(SASL_PLAINTEXT_PORT)),
                "listener.security.protocol.map=CONTROLLER:PLAINTEXT,BROKER:PLAINTEXT,"
                        + "PLAINTEXT:PLAINTEXT,SSLONEWAY:SSL,MTLS:SSL,SASLSSL:SASL_SSL,"
                        + "SASLPLAIN:SASL_PLAINTEXT",
                "log.dirs=/tmp/kraft-logs",
                "num.partitions=1",
                "auto.create.topics.enable=true",
                "offsets.topic.replication.factor=1",
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1",
                "group.initial.rebalance.delay.ms=0",
                // TLS, shared by the SSL and SASL_SSL listeners
                "ssl.keystore.type=PKCS12",
                "ssl.keystore.location=" + SECRETS + "/broker.p12",
                "ssl.keystore.password=" + TestCertificates.PASSWORD,
                "ssl.truststore.type=PKCS12",
                "ssl.truststore.location=" + SECRETS + "/truststore.p12",
                "ssl.truststore.password=" + TestCertificates.PASSWORD,
                "listener.name.ssloneway.ssl.client.auth=none",
                "listener.name.mtls.ssl.client.auth=required",
                // SASL
                "listener.name.saslssl.sasl.enabled.mechanisms=PLAIN,SCRAM-SHA-512",
                "listener.name.saslssl.plain.sasl.jaas.config=" + plainUsers,
                "listener.name.saslssl.scram-sha-512.sasl.jaas.config="
                        + "org.apache.kafka.common.security.scram.ScramLoginModule required;",
                "listener.name.saslplain.sasl.enabled.mechanisms=PLAIN",
                "listener.name.saslplain.plain.sasl.jaas.config=" + plainUsers,
                "");
    }
}
