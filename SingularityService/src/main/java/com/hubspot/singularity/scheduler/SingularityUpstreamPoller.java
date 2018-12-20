package com.hubspot.singularity.scheduler;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hubspot.singularity.SingularityAction;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DisasterManager;

@Singleton
public class SingularityUpstreamPoller extends SingularityLeaderOnlyPoller {
  private static final Logger LOG = LoggerFactory.getLogger(SingularityUpstreamPoller.class);
  private final SingularityUpstreamChecker upstreamChecker;
  private final DisasterManager disasterManager;

  @Inject
  SingularityUpstreamPoller(SingularityConfiguration configuration, SingularityUpstreamChecker upstreamChecker, DisasterManager disasterManager) {
    super(configuration.getCheckUpstreamsEverySeconds(), TimeUnit.SECONDS, true);

    this.upstreamChecker = upstreamChecker;
    this.disasterManager = disasterManager;
  }

  @Override
  public void runActionOnPoll() {
    if (!disasterManager.isDisabled(SingularityAction.RUN_UPSTREAM_POLLER)) {
      LOG.info("Checking upstreams");
      upstreamChecker.syncUpstreams();
    } else {
      LOG.warn("Upstream poller is currently disabled");
    }
  }
}
