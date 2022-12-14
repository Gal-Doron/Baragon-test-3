package com.hubspot.baragon.service.managers;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.elasticloadbalancing.model.Instance;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.baragon.data.BaragonLoadBalancerDatastore;
import com.hubspot.baragon.models.AgentCheckInResponse;
import com.hubspot.baragon.models.BaragonAgentMetadata;
import com.hubspot.baragon.models.BaragonGroup;
import com.hubspot.baragon.models.RegisterBy;
import com.hubspot.baragon.models.TrafficSource;
import com.hubspot.baragon.models.TrafficSourceState;
import com.hubspot.baragon.models.TrafficSourceType;
import com.hubspot.baragon.service.config.ElbConfiguration;
import com.hubspot.baragon.service.elb.ApplicationLoadBalancer;
import com.hubspot.baragon.service.elb.ClassicLoadBalancer;
import com.hubspot.baragon.service.elb.ElasticLoadBalancer;
import com.hubspot.baragon.service.exceptions.NoMatchingElbForVpcException;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ElbManager {
  private static final Logger LOG = LoggerFactory.getLogger(ElbManager.class);

  private final ApplicationLoadBalancer applicationLoadBalancer;
  private final ClassicLoadBalancer classicLoadBalancer;

  private final Optional<ElbConfiguration> configuration;
  private final BaragonLoadBalancerDatastore loadBalancerDatastore;

  @Inject
  public ElbManager(
    ApplicationLoadBalancer applicationLoadBalancer,
    ClassicLoadBalancer classicLoadBalancer,
    BaragonLoadBalancerDatastore loadBalancerDatastore,
    Optional<ElbConfiguration> configuration
  ) {
    this.applicationLoadBalancer = applicationLoadBalancer;
    this.classicLoadBalancer = classicLoadBalancer;

    this.configuration = configuration;
    this.loadBalancerDatastore = loadBalancerDatastore;
  }

  public boolean isElbConfigured() {
    return (configuration.isPresent() && configuration.get().isEnabled());
  }

  public boolean isActiveAndHealthy(
    Optional<BaragonGroup> group,
    BaragonAgentMetadata agent
  ) {
    for (TrafficSource source : group.get().getTrafficSources()) {
      if (
        getLoadBalancer(source.getType())
          .isInstanceHealthy(agent.getEc2().getInstanceId().get(), source.getName())
      ) {
        return true;
      }
    }
    return false;
  }

  public AgentCheckInResponse attemptRemoveAgent(
    BaragonAgentMetadata agent,
    Optional<BaragonGroup> group,
    String groupName,
    boolean isStatusCheck
  )
    throws AmazonClientException {
    TrafficSourceState state = TrafficSourceState.DONE;
    long maxWaitTime = 0L;
    Optional<String> maybeExceptions = Optional.absent();
    if (isElbEnabledAgent(agent, group, groupName)) {
      boolean anyCompatible = false;
      StringBuilder message = new StringBuilder();
      for (TrafficSource source : group.get().getTrafficSources()) {
        if (
          source.getRegisterBy() == RegisterBy.PRIVATE_IP &&
          !agent.getEc2().getPrivateIp().isPresent()
        ) {
          message.append(
            String.format(
              "No private ip present to register by for source %s ",
              source.getName()
            )
          );
          continue;
        } else if (
          source.getRegisterBy() == RegisterBy.INSTANCE_ID &&
          !agent.getEc2().getInstanceId().isPresent()
        ) {
          message.append(
            String.format(
              "No instance id present to register by for source %s ",
              source.getName()
            )
          );
          continue;
        }
        anyCompatible = true;
        String id = source.getRegisterBy() == RegisterBy.PRIVATE_IP
          ? agent.getEc2().getPrivateIp().get()
          : agent.getEc2().getInstanceId().get();
        Instance instance = source.getRegisterBy() == RegisterBy.PRIVATE_IP
          ? null
          : new Instance(agent.getEc2().getInstanceId().get());
        AgentCheckInResponse response = isStatusCheck
          ? getLoadBalancer(source.getType())
            .checkRemovedInstance(id, source.getName(), agent.getAgentId())
          : getLoadBalancer(source.getType())
            .removeInstance(instance, id, source.getName(), agent.getAgentId());
        if (response.getState().ordinal() > state.ordinal()) {
          state = response.getState();
        }
        if (response.getExceptionMessage().isPresent()) {
          maybeExceptions =
            Optional.of(
              maybeExceptions.or("") + response.getExceptionMessage().get() + "\n"
            );
        }
        if (response.getWaitTime() > maxWaitTime) {
          maxWaitTime = response.getWaitTime();
        }
      }
      if (!anyCompatible) {
        return new AgentCheckInResponse(
          TrafficSourceState.ERROR,
          Optional.of(message.toString()),
          maxWaitTime
        );
      }
    }
    return new AgentCheckInResponse(state, maybeExceptions, maxWaitTime);
  }

  public AgentCheckInResponse attemptAddAgent(
    BaragonAgentMetadata agent,
    Optional<BaragonGroup> group,
    String groupName,
    boolean isStatusCheck
  )
    throws AmazonClientException, NoMatchingElbForVpcException {
    TrafficSourceState state = TrafficSourceState.DONE;
    Optional<String> maybeVpcException = Optional.absent();
    long maxWaitTime = 0L;
    if (isElbEnabledAgent(agent, group, groupName)) {
      boolean anyCompatible = false;
      StringBuilder message = new StringBuilder();
      for (TrafficSource source : group.get().getTrafficSources()) {
        if (
          source.getRegisterBy() == RegisterBy.PRIVATE_IP &&
          !agent.getEc2().getPrivateIp().isPresent()
        ) {
          message.append(
            String.format(
              "No private ip present to register by for source %s ",
              source.getName()
            )
          );
          continue;
        } else if (
          source.getRegisterBy() == RegisterBy.INSTANCE_ID &&
          !agent.getEc2().getInstanceId().isPresent()
        ) {
          message.append(
            String.format(
              "No instance id present to register by for source %s ",
              source.getName()
            )
          );
          continue;
        }
        anyCompatible = true;
        String id = source.getRegisterBy() == RegisterBy.PRIVATE_IP
          ? agent.getEc2().getPrivateIp().get()
          : agent.getEc2().getInstanceId().get();
        Instance instance = source.getRegisterBy() == RegisterBy.PRIVATE_IP
          ? null
          : new Instance(agent.getEc2().getInstanceId().get());
        AgentCheckInResponse response = isStatusCheck
          ? getLoadBalancer(source.getType())
            .checkRegisteredInstance(instance, id, source, agent)
          : getLoadBalancer(source.getType())
            .registerInstance(instance, id, source.getName(), agent);
        if (response.getExceptionMessage().isPresent()) {
          maybeVpcException =
            Optional.of(
              maybeVpcException.or("") + response.getExceptionMessage().get() + "\n"
            );
        }
        if (response.getState().ordinal() > state.ordinal()) {
          state = response.getState();
        }
        if (response.getWaitTime() > maxWaitTime) {
          maxWaitTime = response.getWaitTime();
        }
      }
      if (maybeVpcException.isPresent() && configuration.get().isFailWhenNoElbForVpc()) {
        throw new NoMatchingElbForVpcException(maybeVpcException.get());
      }
      if (!anyCompatible) {
        return new AgentCheckInResponse(
          TrafficSourceState.ERROR,
          Optional.of(message.toString()),
          maxWaitTime
        );
      }
    }
    return new AgentCheckInResponse(state, maybeVpcException, maxWaitTime);
  }

  public boolean isElbEnabledAgent(
    BaragonAgentMetadata agent,
    Optional<BaragonGroup> group,
    String groupName
  ) {
    if (group.isPresent()) {
      if (!group.get().getTrafficSources().isEmpty()) {
        if (
          agent.getEc2().getInstanceId().isPresent() &&
          registerBySourcePresent(group.get(), RegisterBy.INSTANCE_ID)
        ) {
          return true;
        } else if (
          agent.getEc2().getPrivateIp().isPresent() &&
          registerBySourcePresent(group.get(), RegisterBy.PRIVATE_IP)
        ) {
          return true;
        } else {
          LOG.debug(
            "No instance id or private ip for agent {}, can't add to ELB",
            agent.getAgentId()
          );
        }
      } else {
        LOG.debug(
          "No traffic sources for group {}, not adding agent {} to an ELB",
          group.get().getName(),
          agent.getAgentId()
        );
      }
    } else {
      LOG.debug("Group {} not found for agent {}", groupName, agent.getAgentId());
    }
    return false;
  }

  private boolean registerBySourcePresent(BaragonGroup group, RegisterBy registerBy) {
    return group
      .getTrafficSources()
      .stream()
      .anyMatch(g -> g.getRegisterBy() == registerBy);
  }

  public void syncAll() {
    Collection<BaragonGroup> groups = loadBalancerDatastore.getLoadBalancerGroups();
    classicLoadBalancer.syncAll(groups);
    applicationLoadBalancer.syncAll(groups);
  }

  private ElasticLoadBalancer getLoadBalancer(TrafficSourceType type) {
    switch (type) {
      case ALB_TARGET_GROUP:
        return applicationLoadBalancer;
      case CLASSIC:
      default:
        return classicLoadBalancer;
    }
  }
}
