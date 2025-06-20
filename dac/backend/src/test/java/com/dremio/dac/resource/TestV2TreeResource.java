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
package com.dremio.dac.resource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.ws.rs.WebApplicationException;
import org.junit.Test;

public class TestV2TreeResource {

  @Test
  public void testDisableAssignReference() {
    assertThatThrownBy(() -> new V2TreeResource(null, null).assignReference(null, "main", null))
        .isInstanceOf(WebApplicationException.class);
  }

  @Test
  public void testDisableTransplantCommitsIntoBranch() {
    assertThatThrownBy(
            () -> new V2TreeResource(null, null).transplantCommitsIntoBranch("main", null))
        .isInstanceOf(WebApplicationException.class);
  }

  @Test
  public void testDisableCommitMultipleOperations() {
    assertThatThrownBy(() -> new V2TreeResource(null, null).commitMultipleOperations("main", null))
        .isInstanceOf(WebApplicationException.class);
  }
}
