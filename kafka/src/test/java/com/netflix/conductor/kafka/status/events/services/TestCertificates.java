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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throwaway certificates for the container tests, generated per run with the JDK's keytool: a test
 * CA, a broker certificate signed by it, a client certificate signed by it, and an unrelated CA
 * that the broker certificate does not chain to.
 */
final class TestCertificates {

    static final String PASSWORD = "changeit";

    private final Path dir;

    private TestCertificates(Path dir) {
        this.dir = dir;
    }

    /**
     * @param brokerHosts host names or IP addresses the broker certificate must be valid for
     */
    static TestCertificates generate(Path dir, List<String> brokerHosts) throws Exception {
        TestCertificates certs = new TestCertificates(dir);
        certs.createCa("ca", "Conductor Test CA");
        certs.createSigned("broker", "localhost", brokerHosts);
        certs.createSigned("client", "conductor", List.of());
        certs.writePemKeystore("client.p12", "client", "client.pem");
        certs.createTruststore("ca", "truststore.p12");

        certs.createCa("other-ca", "Unrelated CA");
        certs.createTruststore("other-ca", "other-truststore.p12");
        return certs;
    }

    /** PKCS12 keystore with the broker's key and certificate chain. */
    Path brokerKeystore() {
        return dir.resolve("broker.p12");
    }

    /** PKCS12 keystore with the client's key and certificate chain. */
    Path clientKeystoreP12() {
        return dir.resolve("client.p12");
    }

    /** PEM file with the client's PKCS#8 private key followed by its certificate chain. */
    Path clientKeystorePem() {
        return dir.resolve("client.pem");
    }

    /** PKCS12 truststore with the test CA. */
    Path truststoreP12() {
        return dir.resolve("truststore.p12");
    }

    /** PEM file with the test CA certificate. */
    Path truststorePem() {
        return dir.resolve("ca.crt");
    }

    /** PKCS12 truststore with a CA that did not sign the broker certificate. */
    Path untrustedTruststoreP12() {
        return dir.resolve("other-truststore.p12");
    }

    private void createCa(String alias, String commonName) throws Exception {
        keytool(
                "-genkeypair",
                "-alias",
                alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=" + commonName,
                "-ext",
                "bc:c",
                "-validity",
                "2",
                "-keystore",
                alias + ".p12",
                "-storetype",
                "PKCS12",
                "-storepass",
                PASSWORD,
                "-keypass",
                PASSWORD);
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                alias,
                "-keystore",
                alias + ".p12",
                "-storepass",
                PASSWORD,
                "-file",
                alias + ".crt");
    }

    private void createSigned(String alias, String commonName, List<String> hosts)
            throws Exception {
        String keystore = alias + ".p12";
        keytool(
                "-genkeypair",
                "-alias",
                alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=" + commonName,
                "-validity",
                "2",
                "-keystore",
                keystore,
                "-storetype",
                "PKCS12",
                "-storepass",
                PASSWORD,
                "-keypass",
                PASSWORD);
        keytool(
                "-certreq",
                "-alias",
                alias,
                "-keystore",
                keystore,
                "-storepass",
                PASSWORD,
                "-file",
                alias + ".csr");

        List<String> gencert =
                new ArrayList<>(
                        List.of(
                                "-gencert",
                                "-alias",
                                "ca",
                                "-keystore",
                                "ca.p12",
                                "-storepass",
                                PASSWORD,
                                "-infile",
                                alias + ".csr",
                                "-outfile",
                                alias + ".crt",
                                "-rfc",
                                "-validity",
                                "2",
                                "-ext",
                                "KU=digitalSignature,keyEncipherment",
                                "-ext",
                                "EKU=serverAuth,clientAuth"));
        if (!hosts.isEmpty()) {
            gencert.add("-ext");
            gencert.add(
                    "SAN=" + String.join(",", hosts.stream().map(TestCertificates::san).toList()));
        }
        keytool(gencert.toArray(String[]::new));

        // Install the CA-signed certificate so the key entry carries the full chain.
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                "ca",
                "-file",
                "ca.crt",
                "-keystore",
                keystore,
                "-storepass",
                PASSWORD);
        keytool(
                "-importcert",
                "-alias",
                alias,
                "-file",
                alias + ".crt",
                "-keystore",
                keystore,
                "-storepass",
                PASSWORD);
        keytool("-delete", "-alias", "ca", "-keystore", keystore, "-storepass", PASSWORD);
    }

    private void createTruststore(String caAlias, String truststore) throws Exception {
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                caAlias,
                "-file",
                caAlias + ".crt",
                "-keystore",
                truststore,
                "-storetype",
                "PKCS12",
                "-storepass",
                PASSWORD);
    }

    private void writePemKeystore(String pkcs12, String alias, String pemFile) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve(pkcs12))) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        Key key = keyStore.getKey(alias, PASSWORD.toCharArray());

        // Java encodes RSA private keys as PKCS#8, the format Kafka requires for PEM keys.
        StringBuilder pem = new StringBuilder(pem("PRIVATE KEY", key.getEncoded()));
        for (Certificate certificate : keyStore.getCertificateChain(alias)) {
            pem.append(pem("CERTIFICATE", certificate.getEncoded()));
        }
        Files.writeString(dir.resolve(pemFile), pem);
    }

    private static String pem(String type, byte[] der) {
        String body =
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static String san(String host) {
        return (host.matches("[0-9.]+") || host.contains(":") ? "ip:" : "dns:") + host;
    }

    private void keytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));

        Process process =
                new ProcessBuilder(command)
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException(
                    "keytool " + String.join(" ", args) + " failed:\n" + output);
        }
    }
}
