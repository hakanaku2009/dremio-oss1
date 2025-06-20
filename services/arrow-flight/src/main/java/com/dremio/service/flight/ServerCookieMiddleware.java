/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.service.flight;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;
import org.apache.arrow.flight.CallHeaders;
import org.apache.arrow.flight.CallInfo;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.RequestContext;

/**
 * ServerCookieMiddleware allows a FlightServer to retrieve cookies from the request as well as set
 * outgoing cookies
 */
@SuppressWarnings("checkstyle:FinalClass")
public class ServerCookieMiddleware implements FlightServerMiddleware {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ServerCookieMiddleware.class);
  private final RequestContext requestContext;
  private final Map<String, String> cookieValues;
  private final CallHeaders incomingHeaders;

  /** Factory to construct @see com.dremio.service.flight.ServerCookieMiddlewares */
  public static class Factory implements FlightServerMiddleware.Factory<ServerCookieMiddleware> {
    /** Construct a factory for receiving call headers. */
    public Factory() {}

    @Override
    public ServerCookieMiddleware onCallStarted(
        CallInfo callInfo, CallHeaders incomingHeaders, RequestContext context) {
      return new ServerCookieMiddleware(callInfo, incomingHeaders, context);
    }
  }

  private ServerCookieMiddleware(
      CallInfo callInfo, CallHeaders incomingHeaders, RequestContext requestContext) {
    this.incomingHeaders = incomingHeaders;
    this.requestContext = requestContext;
    this.cookieValues = new HashMap<>();
  }

  /** Retrieve the headers for this call. */
  public CallHeaders headers() {
    return this.incomingHeaders;
  }

  public void addCookie(@NotNull String key, @NotNull String value) {
    // Add to the internal map of values
    this.cookieValues.put(key, value);
    logger.debug("ServerCookieMiddleware.addCookie {} {} = {}", this, key, value);
  }

  /**
   * Add a cookie with associated attributes.
   *
   * @param key the key of the cookie
   * @param value the value of the cookie
   * @param attributes the additional attributes of the cookie
   */
  public void addCookie(
      @NotNull String key, @NotNull String value, @NotNull Map<String, String> attributes) {
    final String attributeList =
        attributes.entrySet().stream()
            .map((entry) -> String.format("%s=%s", entry.getKey(), entry.getValue()))
            .collect(Collectors.joining(";"));
    final String newValue = String.format("%s;%s", value, attributeList);
    // Add to the internal map of values
    this.cookieValues.put(key, newValue);
  }

  public RequestContext getRequestContext() {
    return this.requestContext;
  }

  @Override
  public void onBeforeSendingHeaders(CallHeaders outgoingHeaders) {
    // if internal values are not empty
    if (cookieValues.isEmpty()) {
      logger.debug("ServerCookieMiddleware.onBeforeSendingHeaders {} cookieValues is empty", this);
      return;
    }

    final String cookies =
        cookieValues.entrySet().stream()
            .map((entry) -> String.format("%s=%s", entry.getKey(), entry.getValue()))
            .collect(Collectors.joining(";"));

    // set it in the header
    outgoingHeaders.insert("Set-Cookie", cookies);
    logger.debug(
        "ServerCookieMiddleware.onBeforeSendingHeaders {} cookieValues {} outgoingHeaders {}",
        this,
        cookies,
        outgoingHeaders);
  }

  @Override
  public void onCallCompleted(CallStatus status) {}

  @Override
  public void onCallErrored(Throwable err) {}
}
