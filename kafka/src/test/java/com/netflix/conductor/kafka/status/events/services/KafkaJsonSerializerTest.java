/*
 * Copyright 2026 Conductor Authors.
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
import java.util.List;

import org.junit.Test;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KafkaJsonSerializerTest {

    private final ObjectMapper reader = new ObjectMapper();

    @Test
    public void serializesTaskEventWithTheFieldsConsumersRead() throws IOException {
        JsonNode json = serialize(timedOutTask());

        assertEquals("task-1", json.path("taskId").asText());
        assertEquals("workflow-1", json.path("workflowInstanceId").asText());
        assertEquals("TIMED_OUT", json.path("status").asText());
        assertEquals("cxo_task", json.path("workflowTask").path("name").asText());
        assertEquals("SIMPLE", json.path("workflowTask").path("type").asText());
    }

    @Test
    public void keepsTaskDefinitionOnlyUnderWorkflowTask() throws IOException {
        JsonNode json = serialize(timedOutTask());

        // Same shape as 3.21.21, where TaskModel.getTaskDefinition() was @JsonIgnore.
        assertFalse(json.has("taskDefinition"));
        assertTrue(json.path("workflowTask").has("taskDefinition"));
    }

    @Test
    public void serializesWorkflowEventWithTheFieldsConsumersRead() throws IOException {
        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName("cxo_workflow");
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId("workflow-1");
        workflow.setWorkflowDefinition(workflowDef);
        workflow.setStatus(WorkflowModel.Status.COMPLETED);
        workflow.setTasks(List.of(timedOutTask()));

        JsonNode json = serialize(workflow);

        assertEquals("workflow-1", json.path("workflowId").asText());
        assertEquals("cxo_workflow", json.path("workflowName").asText());
        assertEquals("COMPLETED", json.path("status").asText());
        assertEquals("cxo_workflow", json.path("workflowDefinition").path("name").asText());
        assertEquals("cxo_task", json.path("tasks").path(0).path("taskDefName").asText());
        assertFalse(json.path("tasks").path(0).has("taskDefinition"));
    }

    private JsonNode serialize(Object event) throws IOException {
        return reader.readTree(new KafkaJsonSerializer<>().serialize("topic", event));
    }

    private static TaskModel timedOutTask() {
        WorkflowTask workflowTask = new WorkflowTask();
        workflowTask.setName("cxo_task");
        workflowTask.setType("SIMPLE");
        workflowTask.setTaskDefinition(new TaskDef("cxo_task"));
        TaskModel task = new TaskModel();
        task.setTaskId("task-1");
        task.setTaskDefName("cxo_task");
        task.setWorkflowInstanceId("workflow-1");
        task.setStatus(TaskModel.Status.TIMED_OUT);
        task.setWorkflowTask(workflowTask);
        return task;
    }
}
