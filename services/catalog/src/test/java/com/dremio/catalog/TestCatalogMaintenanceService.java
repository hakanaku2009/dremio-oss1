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
package com.dremio.catalog;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dremio.options.OptionManager;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TestCatalogMaintenanceService {
  @Mock private OptionManager optionManager;

  @Mock private CatalogMaintenanceTask task;

  private CatalogMaintenanceService service;

  @BeforeEach
  public void setUp() {
    service = new CatalogMaintenanceService(() -> optionManager, () -> ImmutableList.of(task));
  }

  @Test
  public void testTaskInvoked() throws Exception {
    service.start();
    verify(task, times(1)).start();
  }

  @Test
  public void testTaskStopped() throws Exception {
    service.start();
    service.close();
    verify(task, times(1)).stop();
  }
}
