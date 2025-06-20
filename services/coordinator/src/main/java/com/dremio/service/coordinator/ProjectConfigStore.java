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
package com.dremio.service.coordinator;

import com.dremio.service.Service;
import com.dremio.service.coordinator.proto.ProjectConfig;

/** Interface for saving and retrieving coordinator settings that are applied to cordinator. */
public interface ProjectConfigStore extends Service {
  // get the project config of the coordinator
  ProjectConfig get();

  // save the project config of the coordinator
  void put(ProjectConfig projectConfig);

  void patch(ProjectConfig projectConfig);

  /** NO_OP implementation */
  public static final ProjectConfigStore NO_OP =
      new ProjectConfigStore() {
        @Override
        public void start() throws Exception {}

        @Override
        public void close() throws Exception {}

        @Override
        public ProjectConfig get() {
          return null;
        }

        @Override
        public void put(ProjectConfig projectConfig) {}

        @Override
        public void patch(ProjectConfig projectConfig) {}
      };
}
