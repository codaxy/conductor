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
package com.netflix.conductor.kafka.status.events.listener;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.listener.WorkflowStatusListener;
import com.netflix.conductor.kafka.status.events.Event;
import com.netflix.conductor.kafka.status.events.config.KafkaProperties;
import com.netflix.conductor.kafka.status.events.services.KafkaEventService;
import com.netflix.conductor.model.WorkflowModel;

public class WorkflowStatusKafkaProducer implements WorkflowStatusListener {

    private final Logger LOGGER = LoggerFactory.getLogger(TaskStatusKafkaProducer.class);
    private final KafkaProperties kafkaProperties;
    private final KafkaEventService kafkaEventService;

    public WorkflowStatusKafkaProducer(
            KafkaEventService kafkaEventService, KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
        this.kafkaEventService = kafkaEventService;
    }

    @Override
    public void onWorkflowCompletedIfEnabled(WorkflowModel workflow) {
        if (workflow.getWorkflowDefinition().isWorkflowStatusListenerEnabled()
                || kafkaProperties.isAlwaysPublishWorkflowStatusEnabled()) {
            onWorkflowCompleted(workflow);
        }
    }

    @Override
    public void onWorkflowTerminatedIfEnabled(WorkflowModel workflow) {
        if (workflow.getWorkflowDefinition().isWorkflowStatusListenerEnabled()
                || kafkaProperties.isAlwaysPublishWorkflowStatusEnabled()) {
            onWorkflowTerminated(workflow);
        }
    }

    @Override
    public void onWorkflowFinalizedIfEnabled(WorkflowModel workflow) {
        if (workflow.getWorkflowDefinition().isWorkflowStatusListenerEnabled()
                || kafkaProperties.isAlwaysPublishWorkflowStatusEnabled()) {
            onWorkflowFinalized(workflow);
        }
    }

    @Override
    public void onWorkflowFinalized(WorkflowModel workflow) {
        produceMessage(workflow);
    }

    @Override
    public void onWorkflowCompleted(WorkflowModel workflow) {
        produceMessage(workflow);
    }

    @Override
    public void onWorkflowTerminated(WorkflowModel workflow) {
        produceMessage(workflow);
    }

    private void produceMessage(WorkflowModel workflowModel) {
        try {
            Event<WorkflowModel> event = new Event<>(workflowModel);
            event.setEventType("Workflow." + workflowModel.getStatus().name());
            event.setEventTime(OffsetDateTime.now());
            kafkaEventService.produce(
                    event.getEventId(), event, kafkaProperties.getWorkflowStatusTopic());

        } catch (Exception e) {
            LOGGER.error(
                    "Failed to produce message to topic: {}. Exception: {}",
                    kafkaProperties.getWorkflowStatusTopic(),
                    e);
            throw new RuntimeException(e);
        }
    }
}
