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
package com.dremio.service.reflection.refresh;

import com.dremio.exec.store.CatalogService;
import com.dremio.service.reflection.ReflectionSettings;
import com.dremio.service.reflection.descriptor.DescriptorHelper;
import com.dremio.service.reflection.store.DependenciesStore;
import com.dremio.service.reflection.store.MaterializationStore;

/**
 * A package private interface that allows sharing of ReflectionService assets for the
 * RefreshHandler.
 */
public interface RefreshHelper {
  ReflectionSettings getReflectionSettings();

  MaterializationStore getMaterializationStore();

  CatalogService getCatalogService();

  DependenciesStore getDependenciesStore();

  DescriptorHelper getDescriptorHelper();
}
