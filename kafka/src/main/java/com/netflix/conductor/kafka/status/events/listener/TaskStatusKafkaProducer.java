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

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.listener.TaskStatusListener;
import com.netflix.conductor.kafka.status.events.config.KafkaProperties;
import com.netflix.conductor.kafka.status.events.services.KafkaEventService;
import com.netflix.conductor.model.TaskModel;

public class TaskStatusKafkaProducer implements TaskStatusListener {

    private final Logger LOGGER = LoggerFactory.getLogger(TaskStatusKafkaProducer.class);
    private final KafkaProperties kafkaProperties;
    private final KafkaEventService kafkaEventService;

    public TaskStatusKafkaProducer(
            KafkaEventService kafkaEventService, KafkaProperties kafkaProperties) {
        this.kafkaEventService = kafkaEventService;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public void onTaskCompleted(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskScheduled(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskInProgress(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskCanceled(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskFailed(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskFailedWithTerminalError(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskCompletedWithErrors(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskTimedOut(TaskModel task) {
        produceMessage(task);
    }

    @Override
    public void onTaskSkipped(TaskModel task) {
        produceMessage(task);
    }

    private boolean IsStatusEnabled(TaskModel task) {
        return kafkaProperties.getAllowedTaskStatuses().contains(task.getStatus().name());
    }

    private void produceMessage(TaskModel task) {
        try {
            if (IsStatusEnabled(task)) {
                kafkaEventService.produce(
                        UUID.randomUUID().toString(), task, kafkaProperties.getTaskStatusTopic());
            }

        } catch (Exception e) {
            LOGGER.error(
                    "Failed to produce message to topic: {}. Exception: {}",
                    kafkaProperties.getTaskStatusTopic(),
                    e);
            throw new RuntimeException(e);
        }
    }
}
