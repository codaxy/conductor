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
package com.netflix.conductor.core.execution.mapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.netflix.conductor.common.config.ObjectMapperProvider;
import com.netflix.conductor.common.metadata.tasks.TaskType;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.core.execution.DeciderService;
import com.netflix.conductor.core.execution.tasks.SystemTaskRegistry;
import com.netflix.conductor.core.listener.TaskStatusListenerStub;
import com.netflix.conductor.core.utils.ExternalPayloadStorageUtils;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.core.utils.ParametersUtils;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Codaxy fork: a sub-workflow started by a dynamic fork keeps its per-item input at the top level
 * of the task input, as in 3.21.21. The CxO ordering engines read it there (e.g. {@code item_id})
 * to find the order items that failed.
 */
public class ForkJoinDynamicSubWorkflowInputTest {

    @Test
    public void subWorkflowKeepsItsItemInputAtTheTopLevel() {
        TaskModel subWorkflowTask = forkOrderItem(Map.of("item_id", "1", "item_action", "Add"));

        assertEquals("1", subWorkflowTask.getInputData().get("item_id"));
        assertEquals("Add", subWorkflowTask.getInputData().get("item_action"));
        assertEquals(
                Map.of("item_id", "1", "item_action", "Add"),
                subWorkflowTask.getInputData().get("workflowInput"));
    }

    @Test
    public void itemInputNeverOverridesTheSubWorkflowSettings() {
        TaskModel subWorkflowTask = forkOrderItem(Map.of("item_id", "1", "priority", 5));

        assertEquals("1", subWorkflowTask.getInputData().get("item_id"));
        assertNull(subWorkflowTask.getInputData().get("priority"));
    }

    /** Forks one order item the way ConductorSharp builds a dynamic fork. */
    private static TaskModel forkOrderItem(Map<String, Object> itemInput) {
        WorkflowTask fork = new WorkflowTask();
        fork.setName("FORK_JOIN_DYNAMIC");
        fork.setTaskReferenceName("fork_order_items");
        fork.setType(TaskType.FORK_JOIN_DYNAMIC.name());
        fork.setDynamicForkTasksParam("dynamic_tasks");
        fork.setDynamicForkTasksInputParamName("dynamic_tasks_i");
        fork.setInputParameters(
                Map.of(
                        "dynamic_tasks", "${workflow.input.dynamic_tasks}",
                        "dynamic_tasks_i", "${workflow.input.dynamic_tasks_i}"));
        WorkflowTask join = new WorkflowTask();
        join.setName("JOIN");
        join.setTaskReferenceName("join_order_items");
        join.setType(TaskType.JOIN.name());

        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName("som_order");
        workflowDef.setTasks(List.of(fork, join));
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowDefinition(workflowDef);
        workflow.setInput(
                Map.of(
                        "dynamic_tasks",
                        List.of(
                                Map.of(
                                        "name",
                                        "som_order_item",
                                        "taskReferenceName",
                                        "1",
                                        "type",
                                        TaskType.SUB_WORKFLOW.name(),
                                        "subWorkflowParam",
                                        Map.of("name", "som_order_item", "version", 1))),
                        "dynamic_tasks_i",
                        Map.of("1", itemInput)));

        return newDeciderService().getTasksToBeScheduled(workflow, fork, 0).stream()
                .filter(task -> TaskType.TASK_TYPE_SUB_WORKFLOW.equals(task.getTaskType()))
                .findFirst()
                .orElseThrow();
    }

    private static DeciderService newDeciderService() {
        ObjectMapper objectMapper = new ObjectMapperProvider().getObjectMapper();
        ParametersUtils parametersUtils = new ParametersUtils(objectMapper);
        MetadataDAO metadataDAO = mock(MetadataDAO.class);
        SystemTaskRegistry systemTaskRegistry = new SystemTaskRegistry(Set.of());
        Map<String, TaskMapper> taskMappers = new HashMap<>();
        taskMappers.put(
                TaskType.FORK_JOIN_DYNAMIC.name(),
                new ForkJoinDynamicTaskMapper(
                        new IDGenerator(),
                        parametersUtils,
                        objectMapper,
                        metadataDAO,
                        systemTaskRegistry));
        taskMappers.put(
                TaskType.SUB_WORKFLOW.name(),
                new SubWorkflowTaskMapper(parametersUtils, metadataDAO));
        return new DeciderService(
                new IDGenerator(),
                parametersUtils,
                metadataDAO,
                mock(ExternalPayloadStorageUtils.class),
                systemTaskRegistry,
                taskMappers,
                new HashMap<>(),
                Duration.ofMinutes(60),
                new TaskStatusListenerStub());
    }
}
