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
package com.dremio.service.nessie.upgrade.kvstore;

import com.dremio.datastore.api.KVStore;
import com.dremio.datastore.api.KVStoreCreationFunction;
import com.dremio.datastore.api.StoreBuildingFactory;
import com.dremio.datastore.format.Format;

/** Creates a Nessie KV store. */
public abstract class AbstractNessieStoreBuilder
    implements KVStoreCreationFunction<String, byte[]> {

  private final String tableName;

  protected AbstractNessieStoreBuilder(String tableName) {
    this.tableName = tableName;
  }

  public String getTableName() {
    return tableName;
  }

  @Override
  public KVStore<String, byte[]> build(StoreBuildingFactory factory) {
    return factory
        .<String, byte[]>newStore()
        .name(tableName)
        .keyFormat(Format.ofString())
        .valueFormat(Format.ofBytes())
        .build();
  }
}
