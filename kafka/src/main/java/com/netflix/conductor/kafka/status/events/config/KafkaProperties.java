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

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("conductor.message-publisher.kafka")
public class KafkaProperties {
    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public String getTaskStatusTopic() {
        return taskStatusTopic;
    }

    public void setTaskStatusTopic(String taskStatusTopic) {
        this.taskStatusTopic = taskStatusTopic;
    }

    public String getWorkflowStatusTopic() {
        return workflowStatusTopic;
    }

    public void setWorkflowStatusTopic(String workflowStatusTopic) {
        this.workflowStatusTopic = workflowStatusTopic;
    }

    public List<String> getAllowedTaskStatuses() {
        return Arrays.asList(this.allowedTaskStatuses.split(","));
    }

    public void setAllowedTaskStatuses(String allowedTaskStatuses) {
        this.allowedTaskStatuses = allowedTaskStatuses;
    }

    public boolean isAlwaysPublishWorkflowStatusEnabled() {
        return alwaysPublishWorkflowStatusEnabled;
    }

    public void setAlwaysPublishWorkflowStatusEnabled(boolean alwaysPublishWorkflowStatusEnabled) {
        this.alwaysPublishWorkflowStatusEnabled = alwaysPublishWorkflowStatusEnabled;
    }

    private boolean alwaysPublishWorkflowStatusEnabled = true;
    private String allowedTaskStatuses;
    private String bootstrapServers = "";
    private String taskStatusTopic = "conductor-task-status";
    private String workflowStatusTopic = "conductor-workflow-status";
}
