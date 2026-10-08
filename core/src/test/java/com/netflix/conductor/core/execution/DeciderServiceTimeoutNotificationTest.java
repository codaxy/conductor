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
package com.netflix.conductor.core.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.netflix.conductor.common.config.ObjectMapperProvider;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.tasks.TaskDef.TimeoutPolicy;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.core.exception.TerminateWorkflowException;
import com.netflix.conductor.core.execution.tasks.SystemTaskRegistry;
import com.netflix.conductor.core.listener.TaskStatusListener;
import com.netflix.conductor.core.utils.ExternalPayloadStorageUtils;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.core.utils.ParametersUtils;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Codaxy fork: the decider must report the task timeouts it detects to the {@link
 * TaskStatusListener}. The ordering engines cancel running workers on these TIMED_OUT events.
 * Upstream closed this as already fixed (conductor-oss/conductor#416), but 3.32.5 still lacked it.
 */
public class DeciderServiceTimeoutNotificationTest {

    private RecordingTaskStatusListener listener;
    private DeciderService deciderService;

    @Before
    public void setUp() {
        listener = new RecordingTaskStatusListener();
        deciderService = newDeciderService(listener);
    }

    @Test
    public void notifiesOnceWhenTaskTimeoutRetriesTheTask() {
        TaskModel task = startedTask(2_000);

        deciderService.checkTaskTimeout(taskTimeoutDef(TimeoutPolicy.RETRY), task);

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertEquals(1, listener.timedOut);
    }

    @Test
    public void notifiesOnceWhenTaskTimeoutTerminatesTheWorkflow() {
        TaskModel task = startedTask(2_000);

        assertThrows(
                TerminateWorkflowException.class,
                () ->
                        deciderService.checkTaskTimeout(
                                taskTimeoutDef(TimeoutPolicy.TIME_OUT_WF), task));

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertEquals(1, listener.timedOut);
    }

    @Test
    public void notifiesOnceWhenPollTimeoutExpires() {
        TaskDef taskDef = new TaskDef("cxo_task");
        taskDef.setTimeoutPolicy(TimeoutPolicy.RETRY);
        taskDef.setPollTimeoutSeconds(1);
        TaskModel task = new TaskModel();
        task.setTaskType("cxo_task");
        task.setStatus(TaskModel.Status.SCHEDULED);
        task.setScheduledTime(System.currentTimeMillis() - 2_000);

        deciderService.checkTaskPollTimeout(taskDef, task);

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertEquals(1, listener.timedOut);
    }

    @Test
    public void notifiesOnceWhenTotalTimeoutExpires() {
        TaskDef taskDef = new TaskDef("cxo_task");
        taskDef.setTimeoutPolicy(TimeoutPolicy.RETRY);
        taskDef.setTotalTimeoutSeconds(60);
        TaskModel task = startedTask(2_000);
        task.setFirstScheduledTime(System.currentTimeMillis() - 120_000);

        deciderService.checkTotalTimeout(taskDef, task);

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertEquals(1, listener.timedOut);
    }

    @Test
    public void notifiesOnceWhenResponseTimeoutExpiresDuringDecide() {
        TaskDef taskDef = new TaskDef("cxo_task");
        taskDef.setResponseTimeoutSeconds(1);
        taskDef.setTimeoutSeconds(300);
        taskDef.setRetryCount(0);
        WorkflowTask workflowTask = simpleWorkflowTask(taskDef);
        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName("cxo_workflow");
        workflowDef.setTasks(List.of(workflowTask));
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId("workflow-1");
        workflow.setWorkflowDefinition(workflowDef);
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        TaskModel task = startedTask(2_000);
        task.setTaskId("task-1");
        task.setTaskDefName("cxo_task");
        task.setReferenceTaskName(workflowTask.getTaskReferenceName());
        task.setWorkflowInstanceId("workflow-1");
        task.setWorkflowTask(workflowTask);
        task.setScheduledTime(System.currentTimeMillis() - 2_000);
        task.setUpdateTime(System.currentTimeMillis() - 2_000);
        workflow.setTasks(new ArrayList<>(List.of(task)));

        try {
            deciderService.decide(workflow);
        } catch (TerminateWorkflowException retriesExhausted) {
            // retryCount is 0, so the timed out task ends the workflow
        }

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertEquals(1, listener.timedOut);
    }

    @Test
    public void doesNotNotifyForAlertOnlyTimeouts() {
        TaskModel task = startedTask(2_000);

        deciderService.checkTaskTimeout(taskTimeoutDef(TimeoutPolicy.ALERT_ONLY), task);

        assertEquals(TaskModel.Status.IN_PROGRESS, task.getStatus());
        assertEquals(0, listener.timedOut);
    }

    @Test
    public void doesNotNotifyWhenTheTaskDefinitionDisablesStatusEvents() {
        TaskDef taskDef = taskTimeoutDef(TimeoutPolicy.RETRY);
        taskDef.setTaskStatusListenerEnabled(false);
        TaskModel task = startedTask(2_000);
        task.setWorkflowTask(simpleWorkflowTask(taskDef));

        deciderService.checkTaskTimeout(taskDef, task);

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
        assertEquals(0, listener.timedOut);
    }

    @Test
    public void timesOutTheTaskEvenWhenTheListenerFails() {
        DeciderService decider = newDeciderService(new FailingTaskStatusListener());
        TaskModel task = startedTask(2_000);

        decider.checkTaskTimeout(taskTimeoutDef(TimeoutPolicy.RETRY), task);

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
    }

    @Test
    public void terminatesTheWorkflowEvenWhenTheListenerFails() {
        DeciderService decider = newDeciderService(new FailingTaskStatusListener());
        TaskModel task = startedTask(2_000);

        assertThrows(
                TerminateWorkflowException.class,
                () -> decider.checkTaskTimeout(taskTimeoutDef(TimeoutPolicy.TIME_OUT_WF), task));

        assertEquals(TaskModel.Status.TIMED_OUT, task.getStatus());
    }

    private static DeciderService newDeciderService(TaskStatusListener taskStatusListener) {
        return new DeciderService(
                new IDGenerator(),
                new ParametersUtils(new ObjectMapperProvider().getObjectMapper()),
                mock(MetadataDAO.class),
                mock(ExternalPayloadStorageUtils.class),
                new SystemTaskRegistry(Set.of()),
                new HashMap<>(),
                new HashMap<>(),
                Duration.ofMinutes(60),
                taskStatusListener);
    }

    private static TaskDef taskTimeoutDef(TimeoutPolicy timeoutPolicy) {
        TaskDef taskDef = new TaskDef("cxo_task");
        taskDef.setTimeoutPolicy(timeoutPolicy);
        taskDef.setTimeoutSeconds(1);
        return taskDef;
    }

    private static TaskModel startedTask(long startedMillisAgo) {
        TaskModel task = new TaskModel();
        task.setTaskType("cxo_task");
        task.setStatus(TaskModel.Status.IN_PROGRESS);
        task.setStartTime(System.currentTimeMillis() - startedMillisAgo);
        return task;
    }

    private static WorkflowTask simpleWorkflowTask(TaskDef taskDef) {
        WorkflowTask workflowTask = new WorkflowTask();
        workflowTask.setName(taskDef.getName());
        workflowTask.setTaskReferenceName(taskDef.getName());
        workflowTask.setType("SIMPLE");
        workflowTask.setTaskDefinition(taskDef);
        return workflowTask;
    }

    private static class RecordingTaskStatusListener implements TaskStatusListener {
        private int timedOut;

        @Override
        public void onTaskTimedOut(TaskModel task) {
            timedOut++;
        }
    }

    private static class FailingTaskStatusListener implements TaskStatusListener {
        @Override
        public void onTaskTimedOut(TaskModel task) {
            throw new IllegalStateException("publisher unavailable");
        }
    }
}
