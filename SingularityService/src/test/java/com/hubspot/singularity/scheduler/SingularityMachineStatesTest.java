package com.hubspot.singularity.scheduler;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskState;
import org.junit.Assert;
import org.junit.Test;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.hubspot.singularity.MachineState;
import com.hubspot.singularity.SingularityMachineStateHistoryUpdate;
import com.hubspot.singularity.SingularitySlave;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SlavePlacement;
import com.hubspot.singularity.api.SingularityMachineChangeRequest;
import com.hubspot.singularity.data.AbstractMachineManager.StateChangeResult;

public class SingularityMachineStatesTest extends SingularitySchedulerTestBase {

  @Inject
  protected SingularityDeadSlavePoller deadSlavePoller;

  public SingularityMachineStatesTest() {
    super(false);
  }

  @Test
  public void testDeadSlavesArePurged() {
    SingularitySlave liveSlave = new SingularitySlave("1", "h1", "r1", ImmutableMap.of("uniqueAttribute", "1"));
    SingularitySlave deadSlave = new SingularitySlave("2", "h1", "r1", ImmutableMap.of("uniqueAttribute", "2"));

    final long now = System.currentTimeMillis();

    liveSlave = liveSlave.changeState(new SingularityMachineStateHistoryUpdate("1", MachineState.ACTIVE, 100, Optional.<String> absent(), Optional.<String> absent()));
    deadSlave = deadSlave.changeState(new SingularityMachineStateHistoryUpdate("2", MachineState.DEAD, now - TimeUnit.HOURS.toMillis(10), Optional.<String> absent(), Optional.<String> absent()));

    slaveManager.saveObject(liveSlave);
    slaveManager.saveObject(deadSlave);

    deadSlavePoller.runActionOnPoll();

    Assert.assertEquals(1, slaveManager.getObjectsFiltered(MachineState.ACTIVE).size());
    Assert.assertEquals(1, slaveManager.getObjectsFiltered(MachineState.DEAD).size());

    configuration.setDeleteDeadSlavesAfterHours(1);

    deadSlavePoller.runActionOnPoll();

    Assert.assertEquals(1, slaveManager.getObjectsFiltered(MachineState.ACTIVE).size());
    Assert.assertEquals(0, slaveManager.getObjectsFiltered(MachineState.DEAD).size());
  }

  @Test
  public void testBasicSlaveAndRackState() {
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 1, "slave1", "host1", Optional.of("rack1"))));
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 1, "slave2", "host2", Optional.of("rack2"))));
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 1, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertEquals(1, slaveManager.getHistory("slave1").size());
    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);

    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().equals(slaveManager.getHistory("slave1").get(0)));

    sms.slaveLost(driver, SlaveID.newBuilder().setValue("slave1").build());

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 1);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 1);

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.DEAD) == 1);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.DEAD) == 1);

    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getState() == MachineState.DEAD);
    Assert.assertTrue(rackManager.getObject("rack1").get().getCurrentState().getState() == MachineState.DEAD);

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 1, "slave3", "host3", Optional.of("rack1"))));

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.DEAD) == 1);

    Assert.assertTrue(rackManager.getHistory("rack1").size() == 3);

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 1, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 3);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);

    sms.slaveLost(driver, SlaveID.newBuilder().setValue("slave1").build());

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.DEAD) == 1);
    Assert.assertTrue(slaveManager.getHistory("slave1").size() == 4);

    sms.slaveLost(driver, SlaveID.newBuilder().setValue("slave1").build());

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.DEAD) == 1);
    Assert.assertTrue(slaveManager.getHistory("slave1").size() == 4);

    slaveManager.deleteObject("slave1");

    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.DEAD) == 0);
    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(slaveManager.getHistory("slave1").isEmpty());
  }

  @Test
  public void testDecommissioning() {
    initRequest();
    initFirstDeploy();

    saveAndSchedule(request.toBuilder().setInstances(Optional.of(2)));

    scheduler.drainPendingQueue(stateCacheProvider.get());

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave2", "host2", Optional.of("rack1"))));
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave3", "host3", Optional.of("rack2"))));
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave4", "host4", Optional.of("rack2"))));


    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }
    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }

    Assert.assertTrue(rackManager.getNumObjectsAtState(MachineState.ACTIVE) == 2);
    Assert.assertTrue(slaveManager.getNumObjectsAtState(MachineState.ACTIVE) == 4);

    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size() == 1);
    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get()).size() == 1);
    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave3").get()).isEmpty());
    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave4").get()).isEmpty());

    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user1")));
    Assert.assertEquals(StateChangeResult.FAILURE_ALREADY_AT_STATE, slaveManager.changeState("slave1", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user1")));
    Assert.assertEquals(StateChangeResult.FAILURE_NOT_FOUND, slaveManager.changeState("slave9231", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user1")));

    Assert.assertEquals(MachineState.STARTING_DECOMMISSION, slaveManager.getObject("slave1").get().getCurrentState().getState());
    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getUser().get().equals("user1"));

    saveAndSchedule(request.toBuilder().setInstances(Optional.of(3)));

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size() == 1);

    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getState() == MachineState.DECOMMISSIONING);
    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getUser().get().equals("user1"));

    cleaner.drainCleanupQueue();

    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getState() == MachineState.DECOMMISSIONING);
    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getUser().get().equals("user1"));

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave4", "host4", Optional.of("rack2"))));
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave3", "host3", Optional.of("rack2"))));

    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave4").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }
    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave3").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }

    // all task should have moved.

    cleaner.drainCleanupQueue();

    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave4").get()).size() == 1);
    Assert.assertTrue(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave3").get()).size() == 1);
    Assert.assertEquals(1, taskManager.getKilledTaskIdRecords().size());

    // kill the task
    statusUpdate(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).get(0), TaskState.TASK_KILLED);

    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getState() == MachineState.DECOMMISSIONED);
    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getUser().get().equals("user1"));

    // let's DECOMMission rack2
    Assert.assertEquals(StateChangeResult.SUCCESS, rackManager.changeState("rack2", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user2")));

    // it shouldn't place any on here, since it's DECOMMissioned
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertEquals(0, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertEquals(0, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());

    slaveResource.activateSlave("slave1", Optional.<SingularityMachineChangeRequest> absent());

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertEquals(1, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());

    Assert.assertTrue(rackManager.getObject("rack2").get().getCurrentState().getState() == MachineState.DECOMMISSIONING);

    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave2", "host2", Optional.of("rack1"))));

    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }
    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }

    cleaner.drainCleanupQueue();

    // kill the tasks
    statusUpdate(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave3").get()).get(0), TaskState.TASK_KILLED);

    Assert.assertTrue(rackManager.getObject("rack2").get().getCurrentState().getState() == MachineState.DECOMMISSIONING);

    statusUpdate(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave4").get()).get(0), TaskState.TASK_KILLED);

    Assert.assertTrue(rackManager.getObject("rack2").get().getCurrentState().getState() == MachineState.DECOMMISSIONED);

  }

  @Test
  public void testEmptyDecommissioning() {
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user1")));

    scheduler.drainPendingQueue(stateCacheProvider.get());
    sms.resourceOffers(driver, Arrays.asList(createOffer(1, 129, "slave1", "host1", Optional.of("rack1"))));

    Assert.assertEquals(MachineState.DECOMMISSIONED, slaveManager.getObject("slave1").get().getCurrentState().getState());
  }

  @Test
  public void testFrozenSlaveTransitions() {
    initRequest();
    initFirstDeploy();

    resourceOffers();

    // test transitions out of frozen
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user1")));
    Assert.assertEquals(StateChangeResult.FAILURE_ALREADY_AT_STATE, slaveManager.changeState("slave1", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user1")));
    Assert.assertEquals(StateChangeResult.FAILURE_ILLEGAL_TRANSITION, slaveManager.changeState("slave1", MachineState.DECOMMISSIONING, Optional.<String> absent(), Optional.of("user1")));
    Assert.assertEquals(StateChangeResult.FAILURE_ILLEGAL_TRANSITION, slaveManager.changeState("slave1", MachineState.DECOMMISSIONED, Optional.<String> absent(), Optional.of("user1")));
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.ACTIVE, Optional.<String> absent(), Optional.of("user1")));

    // test transitions into frozen
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave2", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.FAILURE_ILLEGAL_TRANSITION, slaveManager.changeState("slave2", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave2", MachineState.DECOMMISSIONING, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.FAILURE_ILLEGAL_TRANSITION, slaveManager.changeState("slave2", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave2", MachineState.DECOMMISSIONED, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.FAILURE_ILLEGAL_TRANSITION, slaveManager.changeState("slave2", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave2", MachineState.ACTIVE, Optional.<String> absent(), Optional.of("user2")));
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave2", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user2")));
  }

  @Test
  public void testFrozenSlaveDoesntLaunchTasks() {
    initRequest();
    initFirstDeploy();

    resourceOffers();

    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user1")));

    saveAndSchedule(request.toBuilder().setInstances(Optional.of(2)));

    resourceOffers();

    Assert.assertEquals(0, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());
    Assert.assertEquals(2, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get()).size());
  }

  @Test
  public void testUnfrozenSlaveLaunchesTasks() {
    initRequest();
    initFirstDeploy();

    resourceOffers();

    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user1")));

    saveAndSchedule(request.toBuilder().setInstances(Optional.of(2)).setSlavePlacement(Optional.of(SlavePlacement.SEPARATE)));

    resourceOffers();

    Assert.assertEquals(0, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());
    Assert.assertEquals(1, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get()).size());

    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.ACTIVE, Optional.<String> absent(), Optional.of("user1")));

    resourceOffers();

    Assert.assertEquals(1, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());
    Assert.assertEquals(1, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get()).size());
  }

  @Test
  public void testFrozenSlaveCanBeDecommissioned() {
    initRequest();
    initFirstDeploy();

    saveAndSchedule(request.toBuilder().setInstances(Optional.of(2)));

    resourceOffers();

    // freeze slave1
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.FROZEN, Optional.<String> absent(), Optional.of("user1")));

    // mark tasks as running
    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }
    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }

    // assert Request is spread over the two slaves
    Assert.assertEquals(1, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());
    Assert.assertEquals(1, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get()).size());

    // decommission frozen slave1
    Assert.assertEquals(StateChangeResult.SUCCESS, slaveManager.changeState("slave1", MachineState.STARTING_DECOMMISSION, Optional.<String> absent(), Optional.of("user1")));

    resourceOffers();
    cleaner.drainCleanupQueue();

    // assert slave1 is decommissioning
    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getState() == MachineState.DECOMMISSIONING);

    // mark tasks as running
    for (SingularityTask task : taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get())) {
      statusUpdate(task, TaskState.TASK_RUNNING);
    }

    // all tasks should have moved
    cleaner.drainCleanupQueue();

    // kill decommissioned task
    statusUpdate(taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).get(0), TaskState.TASK_KILLED);

    // assert all tasks on slave2 + slave1 is decommissioned
    Assert.assertEquals(0, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave1").get()).size());
    Assert.assertEquals(2, taskManager.getTasksOnSlave(taskManager.getActiveTaskIds(), slaveManager.getObject("slave2").get()).size());
    Assert.assertTrue(slaveManager.getObject("slave1").get().getCurrentState().getState() == MachineState.DECOMMISSIONED);
  }

}
