/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.runtime.instance;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.common.base.Function;
import com.typesafe.config.ConfigFactory;

import gobblin.runtime.api.GobblinInstanceDriver;
import gobblin.runtime.api.JobExecutionDriver;
import gobblin.runtime.api.JobExecutionLauncher;
import gobblin.runtime.api.JobExecutionResult;
import gobblin.runtime.api.JobLifecycleListener;
import gobblin.runtime.api.JobSpec;
import gobblin.runtime.instance.DefaultGobblinInstanceDriverImpl.JobSpecRunnable;
import gobblin.runtime.std.DefaultJobLifecycleListenerImpl;
import gobblin.runtime.std.FilteredJobLifecycleListener;
import gobblin.runtime.std.JobSpecFilter;
import gobblin.testing.AssertWithBackoff;

/**
 * Unit tests for {@link StandardGobblinInstanceLauncher}
 */
public class TestStandardGobblinInstanceLauncher {

  @Test
  /** Test running of a job when submitted directly to the execution driver*/
  public void testDirectToExecutionDriver() throws Exception {
    StandardGobblinInstanceLauncher.Builder instanceLauncherBuilder =
        StandardGobblinInstanceLauncher.builder()
        .withInstanceName("testDirectToExecutionDriver");
    instanceLauncherBuilder.driver();
    StandardGobblinInstanceLauncher instanceLauncher =
        instanceLauncherBuilder.build();
    instanceLauncher.startAsync();
    instanceLauncher.awaitRunning(50, TimeUnit.MILLISECONDS);

    JobSpec js1 = JobSpec.builder()
        .withConfig(ConfigFactory.parseResources("gobblin/runtime/instance/SimpleHelloWorldJob.jobconf"))
        .build();
    GobblinInstanceDriver instance = instanceLauncher.getDriver();
    final JobExecutionLauncher.StandardMetrics launcherMetrics =
        instance.getJobLauncher().getMetrics();

    AssertWithBackoff asb = new AssertWithBackoff().timeoutMs(100);

    checkLaunchJob(instanceLauncher, js1, instance);
    Assert.assertEquals(launcherMetrics.getNumJobsLaunched().getCount(), 1);
    Assert.assertEquals(launcherMetrics.getNumJobsCompleted().getCount(), 1);
    // Need to use assert with backoff because of race conditions with the callback that updates the
    // metrics
    asb.assertEquals(new Function<Void, Long>() {
      @Override public Long apply(Void input) {
        return launcherMetrics.getNumJobsCommitted().getCount();
      }
    }, 1l, "numJobsCommitted==1");
    Assert.assertEquals(launcherMetrics.getNumJobsFailed().getCount(), 0);
    Assert.assertEquals(launcherMetrics.getNumJobsRunning().getValue().intValue(), 0);

    checkLaunchJob(instanceLauncher, js1, instance);
    Assert.assertEquals(launcherMetrics.getNumJobsLaunched().getCount(), 2);
    Assert.assertEquals(launcherMetrics.getNumJobsCompleted().getCount(), 2);
    asb.assertEquals(new Function<Void, Long>() {
      @Override public Long apply(Void input) {
        return launcherMetrics.getNumJobsCommitted().getCount();
      }
    }, 2l, "numJobsCommitted==2");
    Assert.assertEquals(launcherMetrics.getNumJobsFailed().getCount(), 0);
    Assert.assertEquals(launcherMetrics.getNumJobsRunning().getValue().intValue(), 0);
  }


  private void checkLaunchJob(StandardGobblinInstanceLauncher instanceLauncher, JobSpec js1,
      GobblinInstanceDriver instance) throws TimeoutException, InterruptedException {
    JobExecutionDriver jobDriver = instance.getJobLauncher().launchJob(js1);
    jobDriver.startAsync();
    JobExecutionResult jobResult = jobDriver.get(5, TimeUnit.SECONDS);

    Assert.assertTrue(jobResult.isSuccessful());

    instanceLauncher.stopAsync();
    instanceLauncher.awaitTerminated(100, TimeUnit.MILLISECONDS);
    Assert.assertEquals(instance.getMetrics().getUpFlag().getValue().intValue(), 0);
    Assert.assertEquals(instance.getMetrics().getUptimeMs().getValue().longValue(), 0);
  }


  @Test
  /** Test running of a job when submitted directly to the scheduler */
  public void testDirectToScheduler() throws Exception {
    StandardGobblinInstanceLauncher.Builder instanceLauncherBuilder =
        StandardGobblinInstanceLauncher.builder()
        .withInstanceName("testDirectToScheduler");
    instanceLauncherBuilder.driver();
    StandardGobblinInstanceLauncher instanceLauncher =
        instanceLauncherBuilder.build();
    instanceLauncher.startAsync();
    instanceLauncher.awaitRunning(50, TimeUnit.MILLISECONDS);

    JobSpec js1 = JobSpec.builder()
        .withConfig(ConfigFactory.parseResources("gobblin/runtime/instance/SimpleHelloWorldJob.jobconf"))
        .build();
    final StandardGobblinInstanceDriver instance =
        (StandardGobblinInstanceDriver)instanceLauncher.getDriver();

    final ArrayBlockingQueue<JobExecutionDriver> jobDrivers = new ArrayBlockingQueue<>(1);

    JobLifecycleListener js1Listener = new FilteredJobLifecycleListener(
        JobSpecFilter.eqJobSpecURI(js1.getUri()),
        new DefaultJobLifecycleListenerImpl(instance.getLog()) {
            @Override public void onJobLaunch(JobExecutionDriver jobDriver) {
              super.onJobLaunch(jobDriver);
              try {
                jobDrivers.offer(jobDriver, 100, TimeUnit.MILLISECONDS);
              } catch (InterruptedException e) {
                instance.getLog().error("Offer interrupted.");
              }
            }
          });
    instance.registerWeakJobLifecycleListener(js1Listener);

    JobSpecRunnable js1Runnable = instance.createJobSpecRunnable(js1);
    instance.getJobScheduler().scheduleOnce(js1, js1Runnable);

    JobExecutionDriver jobDriver = jobDrivers.poll(10, TimeUnit.SECONDS);
    Assert.assertNotNull(jobDriver);
    JobExecutionResult jobResult = jobDriver.get(5, TimeUnit.SECONDS);

    Assert.assertTrue(jobResult.isSuccessful());

    instanceLauncher.stopAsync();
    instanceLauncher.awaitTerminated(50, TimeUnit.MILLISECONDS);
  }


  @Test
  /** Test running of a job using the standard path of submitting to the job catalog */
  public void testSubmitToJobCatalog() throws Exception {
    StandardGobblinInstanceLauncher.Builder instanceLauncherBuilder =
        StandardGobblinInstanceLauncher.builder()
        .withInstanceName("testSubmitToJobCatalog");
    instanceLauncherBuilder.driver();
    StandardGobblinInstanceLauncher instanceLauncher =
        instanceLauncherBuilder.build();
    instanceLauncher.startAsync();
    instanceLauncher.awaitRunning(50, TimeUnit.MILLISECONDS);

    JobSpec js1 = JobSpec.builder()
        .withConfig(ConfigFactory.parseResources("gobblin/runtime/instance/SimpleHelloWorldJob.jobconf"))
        .build();
    final StandardGobblinInstanceDriver instance =
        (StandardGobblinInstanceDriver)instanceLauncher.getDriver();

    final ArrayBlockingQueue<JobExecutionDriver> jobDrivers = new ArrayBlockingQueue<>(1);

    JobLifecycleListener js1Listener = new FilteredJobLifecycleListener(
        JobSpecFilter.eqJobSpecURI(js1.getUri()),
        new DefaultJobLifecycleListenerImpl(instance.getLog()) {
            @Override public void onJobLaunch(JobExecutionDriver jobDriver) {
              super.onJobLaunch(jobDriver);
              try {
                jobDrivers.offer(jobDriver, 100, TimeUnit.MILLISECONDS);
              } catch (InterruptedException e) {
                instance.getLog().error("Offer interrupted.");
              }
            }
          });
    instance.registerWeakJobLifecycleListener(js1Listener);

    instance.getMutableJobCatalog().put(js1);

    JobExecutionDriver jobDriver = jobDrivers.poll(10, TimeUnit.SECONDS);
    Assert.assertNotNull(jobDriver);
    JobExecutionResult jobResult = jobDriver.get(5, TimeUnit.SECONDS);

    Assert.assertTrue(jobResult.isSuccessful());

    instanceLauncher.stopAsync();
    instanceLauncher.awaitTerminated(50, TimeUnit.MILLISECONDS);
  }



}
