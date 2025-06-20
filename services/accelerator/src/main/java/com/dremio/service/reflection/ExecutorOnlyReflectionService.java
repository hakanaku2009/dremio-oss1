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
package com.dremio.service.reflection;

import com.dremio.service.DirectProvider;
import com.dremio.service.reflection.descriptor.MaterializationCacheViewer;
import com.dremio.service.reflection.proto.MaterializationId;
import javax.inject.Provider;

/**
 * Executor-only {@link ReflectionService} implementation with the main purpose of exposing a {@link
 * MaterializationCacheViewer} to the system tables
 */
public class ExecutorOnlyReflectionService extends ReflectionService.BaseReflectionService {

  /** Executor-only implementation of the cache viewer that always returns true */
  private static final MaterializationCacheViewer ALWAYS_CACHED_VIEWER =
      new MaterializationCacheViewer() {
        @Override
        public boolean isCached(MaterializationId id) {
          return true;
        }

        @Override
        public boolean isInitialized() {
          return true;
        }
      };

  @Override
  public Provider<MaterializationCacheViewer> getCacheViewerProvider() {
    return DirectProvider.wrap(ALWAYS_CACHED_VIEWER);
  }
}
