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
package com.dremio.service.namespace;

import javax.annotation.Nullable;
import org.immutables.value.Value;

/** This class is used to specify a key range of the namespace store for listing entities. */
@Value.Immutable
public interface NamespaceFindByRange {
  /** Defaults to first entity */
  @Nullable
  NamespaceKey getStart();

  /** Defaults to true */
  @Nullable
  Boolean getIsStartInclusive();

  /** Defaults to final entity */
  @Nullable
  NamespaceKey getEnd();

  /** Defaults to true */
  @Nullable
  Boolean getIsEndInclusive();

  /** Defaults to no limit */
  @Nullable
  Integer getMaxResults();
}
