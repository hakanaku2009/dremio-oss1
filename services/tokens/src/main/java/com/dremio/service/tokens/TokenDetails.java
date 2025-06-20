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
package com.dremio.service.tokens;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;

/** Details of a token. */
public final class TokenDetails {
  public final String token;
  public final String username;
  public final long expiresAt;
  private final List<String> scopes;

  private TokenDetails(String token, String username, long expiresAt) {
    this.token = checkNotNull(token);
    this.username = checkNotNull(username);
    this.expiresAt = expiresAt;
    this.scopes = null;
  }

  private TokenDetails(String token, String username, long expiresAt, List<String> scopes) {
    this.token = checkNotNull(token);
    this.username = checkNotNull(username);
    this.expiresAt = expiresAt;
    this.scopes = scopes;
  }

  public List<String> getScopes() {
    return scopes != null ? List.copyOf(scopes) : List.of();
  }

  public static TokenDetails of(String token, String username, long expiresAt) {
    return new TokenDetails(token, username, expiresAt);
  }

  public static TokenDetails of(
      String token, String username, long expiresAt, List<String> scopes) {
    return new TokenDetails(token, username, expiresAt, scopes);
  }
}
