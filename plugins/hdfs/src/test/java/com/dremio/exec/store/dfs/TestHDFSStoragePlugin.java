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
package com.dremio.exec.store.dfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.server.SabotContext;
import com.dremio.options.OptionManager;
import com.dremio.test.DremioTest;
import java.util.Arrays;
import javax.inject.Provider;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

/** Unit tests for {@code HDFSStoragePlugin} */
public class TestHDFSStoragePlugin extends DremioTest {

  @Test
  public void testHdfsConfApplied() throws Exception {
    final HDFSConf conf = new HDFSConf();
    conf.hostname = "localhost";
    conf.shortCircuitFlag = HDFSConf.ShortCircuitFlag.ENABLED;
    conf.shortCircuitSocketPath = "/tmp/dn.sock";
    conf.propertyList = Arrays.asList(new Property("foo", "bar"));

    SabotContext context = mock(SabotContext.class);
    OptionManager optionManager = mock(OptionManager.class);
    when(context.getClasspathScan()).thenReturn(DremioTest.CLASSPATH_SCAN_RESULT);
    final FileSystemWrapper fileSystemWrapper =
        (fs, storageId, pluginConf, operatorContext, enableAsync, isMetadataEnabled) -> fs;
    when(context.getFileSystemWrapper()).thenReturn(fileSystemWrapper);
    when(context.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(PlannerSettings.VALUES_CAST_ENABLED))
        .thenReturn((PlannerSettings.VALUES_CAST_ENABLED.getDefault().getBoolVal()));

    Provider<StoragePluginId> idProvider =
        () -> {
          return new StoragePluginId(null, conf, null);
        };
    try (HDFSStoragePlugin fileSystemPlugin =
        new HDFSStoragePlugin(conf, context, "test-plugin", idProvider)) {
      fileSystemPlugin.start();

      final Configuration fsConf = fileSystemPlugin.getFsConf();
      assertThat(fsConf.get("dfs.client.read.shortcircuit")).isEqualTo("true");
      assertThat(fsConf.get("dfs.domain.socket.path")).isEqualTo("/tmp/dn.sock");
      assertThat(fsConf.get("foo")).isEqualTo("bar");
    }
  }
}
