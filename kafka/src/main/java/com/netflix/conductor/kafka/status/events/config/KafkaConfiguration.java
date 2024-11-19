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
package com.netflix.conductor.kafka.status.events.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.netflix.conductor.core.listener.TaskStatusListener;
import com.netflix.conductor.core.listener.WorkflowStatusListener;
import com.netflix.conductor.kafka.status.events.listener.TaskStatusKafkaProducer;
import com.netflix.conductor.kafka.status.events.listener.WorkflowStatusKafkaProducer;
import com.netflix.conductor.kafka.status.events.services.KafkaEventService;
import com.netflix.conductor.kafka.status.events.services.KafkaEventServiceImpl;

@Configuration
@EnableConfigurationProperties(KafkaProperties.class)
@ConditionalOnProperty(name = "conductor.message-publisher.type", havingValue = "kafka")
public class KafkaConfiguration {

    @Bean
    public KafkaEventService kafkaEventService(KafkaProperties kafkaProperties) {
        return new KafkaEventServiceImpl(kafkaProperties);
    }

    @ConditionalOnProperty(
            name = "conductor.task-status-listener.type",
            havingValue = "kafka",
            matchIfMissing = false)
    @Bean
    public TaskStatusListener taskStatusPublisherRabbitMQ(
            KafkaEventService kafkaEventService, KafkaProperties kafkaProperties) {
        return new TaskStatusKafkaProducer(kafkaEventService, kafkaProperties);
    }

    @ConditionalOnProperty(
            name = "conductor.workflow-status-listener.type",
            havingValue = "kafka",
            matchIfMissing = false)
    @Bean
    public WorkflowStatusListener workflowStatusListenerRabbitMQ(
            KafkaEventService kafkaEventService, KafkaProperties kafkaProperties) {
        return new WorkflowStatusKafkaProducer(kafkaEventService, kafkaProperties);
    }
}
