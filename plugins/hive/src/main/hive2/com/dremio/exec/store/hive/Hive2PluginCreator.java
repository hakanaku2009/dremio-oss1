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
package com.dremio.exec.store.hive;

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import javax.inject.Provider;
import org.pf4j.Extension;
import org.pf4j.PluginManager;

/** PF4J extension for creating Hive2 StoragePlugin instances. */
@Extension
public class Hive2PluginCreator implements StoragePluginCreator {

  @Override
  public HiveStoragePlugin createStoragePlugin(
      PluginManager pf4jManager,
      HiveStoragePluginConfig config,
      PluginSabotContext context,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    final HiveConfFactory confFactory = new HiveConfFactory();
    return new HiveStoragePlugin(
        confFactory.createHiveConf(config), pf4jManager, context, name, pluginIdProvider);
  }
}
