package com.hubspot.baragon.service.managers;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.hubspot.baragon.data.BaragonStateDatastore;
import com.hubspot.baragon.models.BaragonRequest;
import com.hubspot.baragon.models.BaragonRequestBuilder;
import com.hubspot.baragon.models.BaragonResponse;
import com.hubspot.baragon.models.BaragonService;
import com.hubspot.baragon.models.BaragonServiceState;
import com.hubspot.baragon.models.RequestAction;
import com.hubspot.baragon.models.UpstreamInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ServiceManager {
  private final BaragonStateDatastore stateDatastore;
  private final RequestManager requestManager;

  @Inject
  public ServiceManager(
    BaragonStateDatastore stateDatastore,
    RequestManager requestManager
  ) {
    this.stateDatastore = stateDatastore;
    this.requestManager = requestManager;
  }

  public Optional<BaragonServiceState> getService(String serviceId) {
    final Optional<BaragonService> maybeServiceInfo = stateDatastore.getService(
      serviceId
    );

    if (!maybeServiceInfo.isPresent()) {
      return Optional.absent();
    }

    try {
      return Optional.of(
        new BaragonServiceState(
          maybeServiceInfo.get(),
          stateDatastore.getUpstreams(serviceId)
        )
      );
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  public BaragonResponse enqueueReloadServiceConfigs(
    String serviceId,
    boolean noValidate
  ) {
    String requestId = String.format(
      "%s-%s-%s",
      serviceId,
      System.currentTimeMillis(),
      "RELOAD"
    );
    Optional<BaragonService> maybeService = stateDatastore.getService(serviceId);
    if (maybeService.isPresent()) {
      try {
        return requestManager.enqueueRequest(
          buildReloadRequest(maybeService.get(), requestId, noValidate)
        );
      } catch (Exception e) {
        return BaragonResponse.failure(requestId, e.getMessage());
      }
    } else {
      return BaragonResponse.serviceNotFound(requestId, serviceId);
    }
  }

  public BaragonResponse enqueuePurgeCache(String serviceId) {
    String requestId = String.format(
      "%s-%s-%s",
      serviceId,
      System.currentTimeMillis(),
      "PURGE_CACHE"
    );
    Optional<BaragonService> maybeService = stateDatastore.getService(serviceId);
    if (maybeService.isPresent()) {
      try {
        return requestManager.enqueueRequest(
          buildPurgeCacheRequest(maybeService.get(), requestId)
        );
      } catch (Exception e) {
        return BaragonResponse.failure(requestId, e.getMessage());
      }
    } else {
      return BaragonResponse.serviceNotFound(requestId, serviceId);
    }
  }

  public BaragonResponse enqueueRemoveService(
    String serviceId,
    boolean noValidate,
    boolean noReload
  ) {
    String requestId = String.format(
      "%s-%s-%s",
      serviceId,
      System.currentTimeMillis(),
      "DELETE"
    );
    Optional<BaragonService> maybeService = stateDatastore.getService(serviceId);
    if (maybeService.isPresent()) {
      try {
        return requestManager.enqueueRequest(
          buildRemoveRequest(maybeService.get(), requestId, noValidate, noReload)
        );
      } catch (Exception e) {
        return BaragonResponse.failure(requestId, e.getMessage());
      }
    } else {
      return BaragonResponse.serviceNotFound(requestId, serviceId);
    }
  }

  private BaragonRequest buildRemoveRequest(
    BaragonService service,
    String requestId,
    boolean noValidate,
    boolean noReload
  )
    throws Exception {
    List<UpstreamInfo> empty = Collections.emptyList();
    List<UpstreamInfo> remove;
    remove = new ArrayList<>(stateDatastore.getUpstreams(service.getServiceId()));
    return new BaragonRequestBuilder()
      .setLoadBalancerRequestId(requestId)
      .setLoadBalancerService(service)
      .setAddUpstreams(empty)
      .setRemoveUpstreams(remove)
      .setReplaceUpstreams(empty)
      .setAction(Optional.of(RequestAction.DELETE))
      .setNoValidate(noValidate)
      .setNoReload(noReload)
      .build();
  }

  private BaragonRequest buildReloadRequest(
    BaragonService service,
    String requestId,
    boolean noValidate
  ) {
    List<UpstreamInfo> empty = Collections.emptyList();
    return new BaragonRequestBuilder()
      .setLoadBalancerRequestId(requestId)
      .setLoadBalancerService(service)
      .setAddUpstreams(empty)
      .setRemoveUpstreams(empty)
      .setReplaceUpstreams(empty)
      .setAction(Optional.of(RequestAction.RELOAD))
      .setNoValidate(noValidate)
      .setNoReload(false)
      .build();
  }

  private BaragonRequest buildPurgeCacheRequest(
    BaragonService service,
    String requestId
  ) {
    List<UpstreamInfo> empty = Collections.emptyList();
    return new BaragonRequestBuilder()
      .setLoadBalancerRequestId(requestId)
      .setLoadBalancerService(service)
      .setAddUpstreams(empty)
      .setRemoveUpstreams(empty)
      .setReplaceUpstreams(empty)
      .setAction(Optional.of(RequestAction.PURGE_CACHE))
      .setNoValidate(false)
      .setNoReload(false)
      .build();
  }
}
