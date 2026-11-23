# ADR-0001: Secure connections for the Kafka status-event producer

- **Status:** Accepted
- **Date:** 2026-11-23
- **Module:** `kafka` (`com.netflix.conductor.kafka.status.events`)

## Context

Conductor can publish task and workflow status changes to Kafka
(`conductor.message-publisher.type=kafka`). The events contain task and workflow input and
output, which can include sensitive data.

The producer was built in `KafkaEventServiceImpl` from a fixed set of settings:
`bootstrap.servers`, `acks`, `client.id` and the serializers. `KafkaProperties` exposed only
those and the topic settings, and nothing else was passed to the Kafka client. As a result
the producer always used Kafka's default `security.protocol=PLAINTEXT`:

- events crossed the network unencrypted;
- the broker could not authenticate Conductor;
- there was no configuration option to change either.

Deployments need to connect to brokers that require one of:

| Mode | Encryption | Broker authenticated | Conductor authenticated by |
|---|---|---|---|
| SSL | Yes | Yes | Nothing |
| mTLS (SSL with client certificate) | Yes | Yes | Client certificate |
| SASL_SSL | Yes | Yes | SASL (PLAIN, SCRAM, OAUTHBEARER, GSSAPI) |
| SASL_PLAINTEXT | No | No | SASL |

Certificates come either as PKCS12 keystores (common in Java setups) or as PEM files (common
with Kubernetes secrets and cert-manager).

The project uses `kafka-clients` 3.9.1, which already supports all four modes and PEM
keystores and truststores (KIP-651). The gap is in Conductor, not in the library.

## Decision

### 1. Pass Kafka producer settings through from configuration

`KafkaProperties` gains a `producer` map bound from
`conductor.message-publisher.kafka.producer.*`. Keys are Kafka producer setting names and
are passed to the Kafka client as they are. This is the same approach the
`kafka-event-queue` module uses (`conductor.event-queues.kafka.producer.*`).

The map is `Map<String, String>` rather than `Map<String, Object>`. Spring Boot keeps a
dotted key such as `security.protocol` as a single key for scalar-valued maps, but splits it
into nested maps for `Object` values, which would make the setting invisible to Kafka.

The producer settings are built in one place, `KafkaEventServiceImpl.buildProducerConfig`, in
this order:

1. Defaults: `acks=all`, `client.id` = local host name (falling back to
   `conductor-status-producer` if it cannot be resolved).
2. The user's `producer.*` settings, which may override the defaults.
3. `bootstrap.servers` from the existing `bootstrapServers` setting, when not blank.
4. The key and value serializers, which cannot be overridden because the event format
   depends on `KafkaJsonSerializer`. Attempts to set them are ignored with a warning.

With no `producer.*` settings the resulting configuration is the same as before, so existing
deployments are unaffected.

### 2. Certificates and keys only as files

Keystores and truststores, in PKCS12 or PEM format, are given by file path
(`ssl.keystore.location`, `ssl.truststore.location`). The inline PEM settings
`ssl.keystore.key`, `ssl.keystore.certificate.chain` and `ssl.truststore.certificates` are
refused, and Conductor fails to start with a message pointing to the `*.location` settings.
The message names the setting but never includes its value.

### 3. Validate at startup, connect on first use

`KafkaEventServiceImpl`'s constructor builds and validates the settings, so a bad
configuration fails Conductor's startup rather than every status event:

- inline certificate settings are refused;
- each keystore and truststore location must exist and be readable;
- Kafka's own `ProducerConfig.configDef().parse(...)` checks types and allowed values
  (for example `security.protocol`) without connecting or logging values;
- settings that are not Kafka producer settings are logged as a warning by key name only.
  They are still passed through, as Kafka itself ignores unknown settings.

The `KafkaProducer` is still created on the first event, as before. Creating it at startup
would require the bootstrap address to resolve at startup.

### 4. One producer per service instance, closed on shutdown

The producer moves from a `static` field to an instance field, so service instances with
different settings no longer share whichever producer was created first. Conductor creates
one service bean, so runtime behaviour is unchanged. The service implements `AutoCloseable`;
Spring calls `close()` on shutdown, which closes the producer and waits at most 10 seconds
for events still being sent, so a Kafka outage cannot hold up shutdown indefinitely.

### 5. Keep kafka-clients at 3.9.1

No library change is needed, so the version stays as it is.

### 6. Test against a real broker

Besides unit tests of the settings builder, `KafkaSecurityContainerTest` runs a single-node
`apache/kafka:3.9.1` broker (matching the client version) with one listener per mode, and
checks each mode end to end through `KafkaEventServiceImpl` and `TaskStatusKafkaProducer`:

- delivery over SSL (PKCS12 and PEM truststores), mTLS (PKCS12 and PEM keystores), SASL_SSL
  (PLAIN and SCRAM-SHA-512) and SASL_PLAINTEXT;
- rejection of a client without a certificate on the mTLS listener, a wrong SASL password,
  and a broker certificate the client does not trust. These show the broker really enforces
  each mode, so the delivery tests cannot pass by accident.

Certificates are generated per run with the JDK's `keytool`, so no private keys are stored in
the repository and no extra library is needed. The tests are skipped when Docker is not
available.

## Alternatives considered

**Dedicated, typed settings** (for example `kafka.security.mode=MTLS`,
`kafka.security.keystore-path`). Easier to discover, but each new Kafka option (another SASL
mechanism, OAuth settings, TLS protocol versions) would need code changes, and the names
would differ from Kafka's documentation. Passing settings through covers every mode with no
further code and matches `kafka-event-queue`.

**Allowing inline PEM settings.** Kafka supports them, but they put private keys into
configuration, which is more often copied, logged or committed than a mounted secret file.
In `.properties` files every line break must also be escaped. Files work in every target
environment, so inline settings were excluded deliberately.

**Creating the producer at startup.** This would also catch unreadable keystores, but the
Kafka client resolves the bootstrap addresses when it is created, so Conductor could not
start before Kafka's DNS name resolves (for example during a Kubernetes rollout). Checking
that store files are readable covers the most common error without that dependency.

**Failing on unknown settings.** Stricter, but would stop Conductor from starting on a
setting from a newer Kafka version or a harmless extra key. A warning naming the key makes
typos visible without that risk.

**Encrypting at the network level instead** (service mesh mTLS, private network). Useful in
addition, but does not help when the broker itself requires TLS or SASL, as managed Kafka
services usually do, and does not give the broker an identity for ACLs.

**Upgrading kafka-clients.** Not needed for any of the modes, and would affect other modules
that share the version.

## Consequences

**Positive**

- Status events can be encrypted, and Conductor can authenticate to the broker by
  certificate or SASL credentials, so broker ACLs can apply to it.
- Any Kafka producer setting can be tuned without code changes.
- Misconfiguration (invalid values, missing files, inline certificates) is reported once at
  startup with a clear message.
- Existing deployments without `producer.*` settings behave exactly as before.

**Negative / to be aware of**

- Certificates and credentials are read once, when the producer is created. A renewed
  certificate or changed password takes effect only after Conductor restarts, which matters
  with short-lived certificates.
- Passwords and `sasl.jaas.config` credentials live in configuration. They should be
  supplied with `${...}` placeholders from environment variables or a secret store.
- A misspelt setting only produces a warning.
- SASL_PLAINTEXT is supported but sends events, and with PLAIN the password, unencrypted.
  The README warns about this.
- The container tests need Docker and add roughly 30 seconds to the module's tests.

**Not covered**

- The `KAFKA_PUBLISH` system task (`KafkaProducerManager`) and the `kafka-event-queue`
  module build their own Kafka clients and are unaffected by this decision.
- Encrypted PEM private keys work through `ssl.key.password`, but the container tests only
  use unencrypted keys.

## References

- User documentation: [`kafka/README.md`](../kafka/README.md), section "Kafka Status Events".
- Implementation: `kafka/src/main/java/com/netflix/conductor/kafka/status/events/`.
- Tests: `kafka/src/test/java/com/netflix/conductor/kafka/status/events/services/`.
- Kafka producer settings: <https://kafka.apache.org/39/documentation/#producerconfigs>
- KIP-651, PEM support for keystores and truststores:
  <https://cwiki.apache.org/confluence/display/KAFKA/KIP-651+-+Support+PEM+format+for+SSL+certificates+and+private+key>
