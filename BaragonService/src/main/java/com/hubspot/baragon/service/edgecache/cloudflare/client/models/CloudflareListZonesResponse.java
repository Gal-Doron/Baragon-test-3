package com.hubspot.baragon.service.edgecache.cloudflare.client.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudflareListZonesResponse
  extends CloudflareResponse<List<CloudflareZone>> {

  @JsonCreator
  public CloudflareListZonesResponse(
    @JsonProperty("success") Boolean success,
    @JsonProperty("errors") List<CloudflareError> errors,
    @JsonProperty("messages") List<String> messages,
    @JsonProperty("result_info") CloudflareResultInfo resultInfo,
    @JsonProperty("result") List<CloudflareZone> result
  ) {
    super(success, errors, messages, resultInfo, result);
  }
}
