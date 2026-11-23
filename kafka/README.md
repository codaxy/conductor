# Community contributed system tasks

## Published Artifacts

Group: `com.netflix.conductor`

| Published Artifact | Description |
| ----------- | ----------- | 
| conductor-task | Community contributed tasks  |

**Note**: If you are using `condutor-contribs` as a dependency, the task module is already included, you do not need to include it separately.

## JsonJQTransform
JSON_JQ_TRANSFORM_TASK is a System task that allows processing of JSON data that is supplied to the task, by using the
popular JQ processing tool’s query expression language.


```json
"type" : "JSON_JQ_TRANSFORM_TASK"
```
Check the [JQ Manual](https://stedolan.github.io/jq/manual/v1.5/), and the
[JQ Playground](https://jqplay.org/) for more information on JQ, and also
[AI JQ Playground](https://jq.getport.io/) for building JQ with AI.

### Use Cases

JSON is a popular format of choice for data-interchange. It is widely used in web and server applications, document
storage, API I/O etc. It’s also used within Conductor to define workflow and task definitions and passing data and state
between tasks and workflows. This makes a tool like JQ a natural fit for processing task related data. Some common
usages within Conductor includes, working with HTTP task, JOIN tasks or standalone tasks that try to transform data from
the output of one task to the input of another.

### Configuration

Here is an example of a _`JSON_JQ_TRANSFORM`_ task. The `inputParameters` attribute is expected to have a value object
that has the following

1. A list of key value pair objects denoted key1/value1, key2/value2 in the example below. Note the key1/value1 are
   arbitrary names used in this example.

2. A key with the name `queryExpression`, whose value is a JQ expression. The expression will operate on the value of
   the `inputParameters` attribute. In the example below, the `inputParameters` has 2 inner objects named by attributes
   `key1` and `key2`, each of which has an object that is named `value1` and `value2`. They have an associated array of
   strings as values, `"a", "b"` and `"c", "d"`. The expression `key3: (.key1.value1 + .key2.value2)` concats the 2
   string arrays into a single array against an attribute named `key3`

```json
{
  "name": "jq_example_task",
  "taskReferenceName": "my_jq_example_task",
  "type": "JSON_JQ_TRANSFORM",
  "inputParameters": {
    "key1": {
      "value1": [
        "a",
        "b"
      ]
    },
    "key2": {
      "value2": [
        "c",
        "d"
      ]
    },
    "queryExpression": "{ key3: (.key1.value1 + .key2.value2) }"
  }
}
```

The execution of this example task above will provide the following output. The `resultList` attribute stores the full
list of the `queryExpression` result. The `result` attribute stores the first element of the resultList. An
optional `error`
attribute along with a string message will be returned if there was an error processing the query expression.

```json
{
  "result": {
    "key3": [
      "a",
      "b",
      "c",
      "d"
    ]
  },
  "resultList": [
    {
      "key3": [
        "a",
        "b",
        "c",
        "d"
      ]
    }
  ]
}
```

#### Input Configuration

| Attribute      | Description |
| ----------- | ----------- |
| name      | Task Name. A unique name that is descriptive of the task function      |
| taskReferenceName   | Task Reference Name. A unique reference to this task. There can be multiple references of a task within the same workflow definition        |
| type   | Task Type. In this case, JSON_JQ_TRANSFORM        |
| inputParameters   | The input parameters that will be supplied to this task. The parameters will be a JSON object of atleast 2 attributes, one of which will be called queryExpression. The others are user named attributes. These attributes will be accessible by the JQ query processor        |
| inputParameters/user-defined-key(s)   | User defined key(s) along with values.          |
| inputParameters/queryExpression   | A JQ query expression        |

#### Output Configuration

| Attribute      | Description |
| ----------- | ----------- |
| result   | The first results returned by the JQ expression     |
| resultList   | A List of results returned by the JQ expression        |
| error | An optional error message, indicating that the JQ query failed processing |


## Kafka Publish Task
A Kafka Publish task is used to push messages to another microservice via Kafka.

```json
"type" : "KAFKA_PUBLISH"
```

### Examples

Sample Task

```json
{
  "name": "call_kafka",
  "taskReferenceName": "call_kafka",
  "inputParameters": {
    "kafka_request": {
      "topic": "userTopic",
      "value": "Message to publish",
      "bootStrapServers": "localhost:9092",
      "headers": {
    "x-Auth":"Auth-key"    
      },
      "key": "123",
      "keySerializer": "org.apache.kafka.common.serialization.IntegerSerializer"
    }
  },
  "type": "KAFKA_PUBLISH"
}
```

The task expects an input parameter named `"kafka_request"` as part
of the task's input with the following details:

1. `"bootStrapServers"` - bootStrapServers for connecting to given kafka.
2. `"key"` - Key to be published.
3. `"keySerializer"` - Serializer used for serializing the key published to kafka.
   One of the following can be set :
   a. org.apache.kafka.common.serialization.IntegerSerializer
   b. org.apache.kafka.common.serialization.LongSerializer
   c. org.apache.kafka.common.serialization.StringSerializer.
   Default is String serializer.
4. `"value"` - Value published to kafka
5. `"requestTimeoutMs"` - Request timeout while publishing to kafka.
   If this value is not given the value is read from the property
   kafka.publish.request.timeout.ms. If the property is not set the value
   defaults to 100 ms.
6. `"maxBlockMs"` - maxBlockMs while publishing to kafka. If this value is
   not given the value is read from the property kafka.publish.max.block.ms.
   If the property is not set the value defaults to 500 ms.
7. `"headers"` - A map of additional kafka headers to be sent along with
   the request.
8. `"topic"` - Topic to publish.

The producer created in the kafka task is cached. By default
the cache size is 10 and expiry time is 120000 ms. To change the
defaults following can be modified
kafka.publish.producer.cache.size,
kafka.publish.producer.cache.time.ms respectively.

#### Kafka Task Output

Task status transitions to `COMPLETED`.

The task is marked as `FAILED` if the message could not be published to
the Kafka queue.
## Kafka Status Events

Conductor can publish task and workflow status changes to Kafka topics.

```properties
conductor.message-publisher.type=kafka
conductor.task-status-listener.type=kafka
conductor.workflow-status-listener.type=kafka
conductor.message-publisher.kafka.bootstrapServers=kafka:9092
conductor.message-publisher.kafka.taskStatusTopic=conductor-task-status
conductor.message-publisher.kafka.workflowStatusTopic=conductor-workflow-status
conductor.message-publisher.kafka.allowedTaskStatuses=COMPLETED,FAILED
```

### Security

By default the producer connects without encryption or authentication (`PLAINTEXT`).
Any [Kafka producer setting](https://kafka.apache.org/39/documentation/#producerconfigs)
can be set under `conductor.message-publisher.kafka.producer.*`, which is how SSL, mTLS,
SASL_SSL and SASL_PLAINTEXT are enabled. Examples below omit the
`conductor.message-publisher.kafka.producer.` prefix for brevity; every line needs it.

Conductor checks these settings at startup and fails to start if a value is invalid or a
keystore or truststore file is missing or unreadable. Unknown settings are logged as a warning.
`key.serializer` and `value.serializer` cannot be changed.

#### SSL (encryption, broker authenticated)

```properties
security.protocol=SSL
ssl.truststore.type=PKCS12
ssl.truststore.location=/etc/conductor/kafka/truststore.p12
ssl.truststore.password=${KAFKA_TRUSTSTORE_PASSWORD}
```

With a PEM file holding the CA certificate(s):

```properties
security.protocol=SSL
ssl.truststore.type=PEM
ssl.truststore.location=/etc/conductor/kafka/ca.crt
```

The truststore can be omitted if the broker's certificate is signed by a CA the JVM already
trusts.

#### mTLS (SSL with a client certificate)

The broker listener must have `ssl.client.auth=required`. Add a keystore to the SSL settings:

```properties
ssl.keystore.type=PKCS12
ssl.keystore.location=/etc/conductor/kafka/client.p12
ssl.keystore.password=${KAFKA_KEYSTORE_PASSWORD}
```

or, in PEM format:

```properties
ssl.keystore.type=PEM
ssl.keystore.location=/etc/conductor/kafka/client.pem
# Only when the private key is encrypted:
# ssl.key.password=${KAFKA_KEY_PASSWORD}
```

A PEM keystore file contains the private key in PKCS#8 format (`BEGIN PRIVATE KEY` or
`BEGIN ENCRYPTED PRIVATE KEY`) followed by the client certificate and any intermediate
certificates. Do not set `ssl.keystore.password` for PEM.

#### SASL_SSL (encryption, user name and password)

```properties
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="conductor" password="${KAFKA_PASSWORD}";
ssl.truststore.type=PEM
ssl.truststore.location=/etc/conductor/kafka/ca.crt
```

For `PLAIN`, use `sasl.mechanism=PLAIN` and
`org.apache.kafka.common.security.plain.PlainLoginModule`. `SCRAM-SHA-256` works the same way
as `SCRAM-SHA-512`.

#### SASL_PLAINTEXT (user name and password, no encryption)

```properties
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="conductor" password="${KAFKA_PASSWORD}";
```

Status events, and with `PLAIN` the password, cross the network unencrypted. Use it only on a
trusted network.

#### Certificates and keys

- Certificates and keys must be files. Inline PEM settings (`ssl.keystore.key`,
  `ssl.keystore.certificate.chain`, `ssl.truststore.certificates`) are not supported and
  stop Conductor from starting.
- Supply passwords and `sasl.jaas.config` credentials through environment variables or a
  secret store using `${...}` placeholders rather than writing them into the properties file.
- The producer reads certificates and credentials once. After renewing a certificate or
  changing a password, restart Conductor.

Converting between formats with `openssl`:

```shell
# PEM key and certificate to a PKCS12 keystore
openssl pkcs12 -export -inkey client.key -in client.crt -certfile ca.crt -out client.p12

# PKCS12 keystore to a PEM keystore file (unencrypted PKCS#8 key followed by certificates)
openssl pkcs12 -in client.p12 -nocerts -nodes | openssl pkcs8 -topk8 -nocrypt > client.pem
openssl pkcs12 -in client.p12 -nokeys >> client.pem

# Older "BEGIN RSA PRIVATE KEY" files to PKCS#8
openssl pkcs8 -topk8 -nocrypt -in client.key -out client-pkcs8.key
```
