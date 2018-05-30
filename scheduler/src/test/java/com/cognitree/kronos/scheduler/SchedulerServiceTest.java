/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cognitree.kronos.scheduler;

import com.cognitree.kronos.ServiceProvider;
import com.cognitree.kronos.TestUtil;
import com.cognitree.kronos.executor.handlers.TestTaskHandler;
import com.cognitree.kronos.model.Task;
import com.cognitree.kronos.model.TaskDependencyInfo;
import com.cognitree.kronos.store.MockTaskStore;
import com.cognitree.kronos.util.DateTimeUtil;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.ArrayList;
import java.util.List;

import static com.cognitree.kronos.TestUtil.prepareDependencyInfo;
import static com.cognitree.kronos.TestUtil.waitForTaskToFinishExecution;
import static com.cognitree.kronos.model.FailureMessage.FAILED_TO_RESOLVE_DEPENDENCY;
import static com.cognitree.kronos.model.FailureMessage.TIMED_OUT;
import static com.cognitree.kronos.model.Task.Status.*;
import static com.cognitree.kronos.model.TaskDependencyInfo.Mode.all;
import static java.util.concurrent.TimeUnit.MINUTES;

@FixMethodOrder(MethodSorters.JVM)
public class SchedulerServiceTest extends ApplicationTest {

    @Test
    @Ignore
    public void testSchedulerInitialization() {
        Assert.assertEquals(MockTaskStore.getTask("mockTaskOne-1").getStatus(), SUCCESSFUL);
        Assert.assertEquals(MockTaskStore.getTask("mockTaskOne-2").getStatus(), FAILED);
        Assert.assertEquals(MockTaskStore.getTask("mockTaskOne-2").getStatusMessage(), TIMED_OUT);

        final Task mockTaskTwo = MockTaskStore.getTask("mockTaskTwo");
        Assert.assertEquals(mockTaskTwo.getStatus(), RUNNING);
        TestTaskHandler.finishExecution(mockTaskTwo.getId());
        waitForTaskToFinishExecution(500);
        Assert.assertEquals(mockTaskTwo.getStatus(), SUCCESSFUL);
        final Task mockTaskThree = MockTaskStore.getTask("mockTaskThree");
        Assert.assertEquals(FAILED, mockTaskThree.getStatus());
        Assert.assertEquals(FAILED_TO_RESOLVE_DEPENDENCY, mockTaskThree.getStatusMessage());
        final Task mockTaskFour = MockTaskStore.getTask("mockTaskFour");
        waitForTaskToFinishExecution(500);
        Assert.assertEquals(SUCCESSFUL, mockTaskFour.getStatus());
    }

    @Test
    public void testAddTask() {
        Task taskOne = TestUtil.getTaskBuilder().setName("taskOne").setType("test").build();
        ServiceProvider.getTaskSchedulerService().add(taskOne);
        Assert.assertEquals(6, taskProvider.size());
        waitForTaskToFinishExecution(500);
        Assert.assertEquals(SUCCESSFUL, taskOne.getStatus());
    }

    @Test
    public void testAddDuplicateTask() {
        Task taskOne = TestUtil.getTaskBuilder().setName("taskOne").setType("test").build();
        ServiceProvider.getTaskSchedulerService().add(taskOne);
        ServiceProvider.getTaskSchedulerService().add(taskOne);
        Assert.assertEquals(6, taskProvider.size());
        waitForTaskToFinishExecution(500);
        Assert.assertEquals(SUCCESSFUL, taskOne.getStatus());
    }

    @Test
    public void testTaskScheduledForExecution() {
        final long createdAt = System.currentTimeMillis();
        Task taskOne = TestUtil.getTaskBuilder().setName("taskOne").setType("test").setCreatedAt(createdAt).build();
        Task taskTwo = TestUtil.getTaskBuilder().setName("taskTwo").setType("test").waitForCallback(true)
                .setCreatedAt(createdAt).build();
        List<TaskDependencyInfo> dependencyInfos = new ArrayList<>();
        dependencyInfos.add(prepareDependencyInfo("taskOne", all, "1d"));
        dependencyInfos.add(prepareDependencyInfo("taskTwo", all, "1d"));
        Task taskThree = TestUtil.getTaskBuilder().setName("taskThree").setType("test").setDependsOn(dependencyInfos)
                .waitForCallback(true).setCreatedAt(createdAt + 5).build();

        ServiceProvider.getTaskSchedulerService().add(taskOne);
        Assert.assertTrue(taskProvider.isReadyForExecution(taskOne));
        waitForTaskToFinishExecution(500);
        ServiceProvider.getTaskSchedulerService().add(taskTwo);
        Assert.assertTrue(taskProvider.isReadyForExecution((taskTwo)));
        waitForTaskToFinishExecution(500);
        ServiceProvider.getTaskSchedulerService().add(taskThree);
        Assert.assertEquals(RUNNING, taskTwo.getStatus());
        Assert.assertEquals(WAITING, taskThree.getStatus());
        Assert.assertFalse(taskProvider.isReadyForExecution(taskThree));
        // inform handler to finish execution of taskTwo
        TestTaskHandler.finishExecution(taskTwo.getId());
        waitForTaskToFinishExecution(500);
        Assert.assertEquals(SUCCESSFUL, taskTwo.getStatus());

        Assert.assertTrue(taskProvider.isReadyForExecution(taskThree));
        Assert.assertEquals(RUNNING, taskThree.getStatus());
        waitForTaskToFinishExecution(500);
        TestTaskHandler.finishExecution(taskThree.getId());
        waitForTaskToFinishExecution(500);
        Assert.assertEquals(SUCCESSFUL, taskThree.getStatus());
    }

    @Test
    public void testTaskTimeout() {
        final long createdAt = System.currentTimeMillis();
        Task taskOne = TestUtil.getTaskBuilder().setName("taskOne").setType("test").setMaxExecutionTime("5s")
                .waitForCallback(true).setCreatedAt(createdAt).build();
        Task taskTwo = TestUtil.getTaskBuilder().setName("taskTwo").setType("test").setCreatedAt(createdAt).build();
        List<TaskDependencyInfo> dependencyInfos = new ArrayList<>();
        dependencyInfos.add(prepareDependencyInfo("taskOne", all, "1d"));
        dependencyInfos.add(prepareDependencyInfo("taskTwo", all, "1d"));
        Task taskThree = TestUtil.getTaskBuilder().setName("taskThree").setType("test").setDependsOn(dependencyInfos)
                .setCreatedAt(createdAt + 5).build();
        ServiceProvider.getTaskSchedulerService().add(taskOne);
        ServiceProvider.getTaskSchedulerService().add(taskTwo);
        ServiceProvider.getTaskSchedulerService().add(taskThree);
        waitForTaskToFinishExecution(1000);
        Assert.assertEquals(RUNNING, taskOne.getStatus());
        waitForTaskToFinishExecution(5000);
        TestTaskHandler.finishExecution(taskOne.getId());
        Assert.assertEquals(FAILED, taskOne.getStatus());
        Assert.assertEquals(TIMED_OUT, taskOne.getStatusMessage());
        Assert.assertEquals(SUCCESSFUL, taskTwo.getStatus());
        Assert.assertEquals(FAILED, taskThree.getStatus());
        Assert.assertEquals(FAILED_TO_RESOLVE_DEPENDENCY, taskThree.getStatusMessage());
    }

    @Test
    public void testTaskCleanup() {
        // set task created at time to a lower value than the task purge interval configured in app.yaml
        final long createdAt = System.currentTimeMillis() -
                DateTimeUtil.resolveDuration(applicationConfig.getTaskPurgeInterval()) - MINUTES.toMillis(1);
        Task independentTask = TestUtil.getTaskBuilder().setName("independentTask").setType("test")
                .waitForCallback(true).setCreatedAt(createdAt).build();
        ServiceProvider.getTaskSchedulerService().add(independentTask);
        waitForTaskToFinishExecution(500);

        Task taskOne = TestUtil.getTaskBuilder().setName("taskOne").setType("test").waitForCallback(true)
                .setCreatedAt(createdAt).build();
        Task taskTwo = TestUtil.getTaskBuilder().setName("taskTwo").setType("test").waitForCallback(true)
                .setCreatedAt(createdAt).build();

        List<TaskDependencyInfo> dependencyInfos = new ArrayList<>();
        dependencyInfos.add(prepareDependencyInfo("taskOne", all, "1d"));
        dependencyInfos.add(prepareDependencyInfo("taskTwo", all, "1d"));

        Task taskThree = TestUtil.getTaskBuilder().setName("taskThree").setType("test").setDependsOn(dependencyInfos)
                .waitForCallback(true).setCreatedAt(createdAt + 5).build();
        Task taskFour = TestUtil.getTaskBuilder().setName("taskFour").setType("test").setDependsOn(dependencyInfos)
                .waitForCallback(true).setCreatedAt(createdAt + 5).build();
        ServiceProvider.getTaskSchedulerService().add(taskOne);
        ServiceProvider.getTaskSchedulerService().add(taskTwo);
        ServiceProvider.getTaskSchedulerService().add(taskThree);
        ServiceProvider.getTaskSchedulerService().add(taskFour);
        waitForTaskToFinishExecution(500);

        Assert.assertEquals(10, taskProvider.size());
        ServiceProvider.getTaskSchedulerService().deleteStaleTasks();
        // no task should be purged as they are all in RUNNING state
        Assert.assertEquals(10, taskProvider.size());
        TestTaskHandler.finishExecution(independentTask.getId());
        waitForTaskToFinishExecution(500);
        ServiceProvider.getTaskSchedulerService().deleteStaleTasks();
        Assert.assertEquals(9, taskProvider.size());
        TestTaskHandler.finishExecution(taskOne.getId());
        TestTaskHandler.finishExecution(taskTwo.getId());
        TestTaskHandler.finishExecution(taskThree.getId());
        waitForTaskToFinishExecution(500);
        ServiceProvider.getTaskSchedulerService().deleteStaleTasks();
        Assert.assertEquals(9, taskProvider.size());
        TestTaskHandler.finishExecution(taskFour.getId());
        waitForTaskToFinishExecution(500);
        // All tasks should be removed as they have reached the final state
        ServiceProvider.getTaskSchedulerService().deleteStaleTasks();
        Assert.assertEquals(5, taskProvider.size());
    }
}
