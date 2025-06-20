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
package com.dremio.dac.server;

import static com.dremio.common.utils.PathUtils.getPathJoiner;
import static com.dremio.dac.explore.model.InitialPreviewResponse.INITIAL_RESULTSET_SIZE;
import static com.dremio.dac.proto.model.dataset.DataType.DATE;
import static com.dremio.dac.proto.model.dataset.DataType.FLOAT;
import static com.dremio.dac.proto.model.dataset.DataType.INTEGER;
import static com.dremio.dac.server.FamilyExpectation.CLIENT_ERROR;
import static com.dremio.service.namespace.NamespaceTestUtils.addFolder;
import static com.dremio.service.namespace.NamespaceTestUtils.addSpace;
import static com.dremio.service.namespace.dataset.DatasetVersion.newVersion;
import static java.util.Arrays.asList;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.dremio.dac.daemon.TestSpacesStoragePlugin;
import com.dremio.dac.explore.DatasetsResource;
import com.dremio.dac.explore.model.Column;
import com.dremio.dac.explore.model.CreateFromSQL;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetSummary;
import com.dremio.dac.explore.model.DatasetUI;
import com.dremio.dac.explore.model.InitialPreviewResponse;
import com.dremio.dac.explore.model.InitialRunResponse;
import com.dremio.dac.explore.model.InitialUntitledRunResponse;
import com.dremio.dac.explore.model.ParentDatasetUI;
import com.dremio.dac.explore.model.VersionContextReq;
import com.dremio.dac.model.folder.FolderModel;
import com.dremio.dac.model.job.JobDataFragment;
import com.dremio.dac.model.job.JobFilterItems;
import com.dremio.dac.model.job.QueryError;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.dac.model.sources.SourceUI;
import com.dremio.dac.model.spaces.Home;
import com.dremio.dac.model.spaces.Space;
import com.dremio.dac.model.spaces.SpaceName;
import com.dremio.dac.model.spaces.SpacePath;
import com.dremio.dac.model.usergroup.UserLogin;
import com.dremio.dac.model.usergroup.UserLoginSession;
import com.dremio.dac.proto.model.dataset.DataType;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.service.errors.InvalidQueryException;
import com.dremio.dac.service.source.SourceService;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.store.dfs.NASConf;
import com.dremio.service.job.proto.QueryType;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.space.proto.SpaceConfig;
import com.dremio.service.users.SimpleUser;
import com.dremio.service.users.User;
import com.dremio.service.users.UserService;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** tests for the DAC REST API */
public class TestServer extends BaseTestServer {

  private DatasetsResource datasetsResource;

  @ClassRule public static final TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setup() throws Exception {
    clearAllDataExceptUser();
  }

  @Test
  public void testSourcesOCC() throws Exception {
    SourceUI source = new SourceUI();
    source.setName("src1");
    source.setCtime(1000L);

    final NASConf config1 = new NASConf();
    config1.path = folder.getRoot().getAbsolutePath();
    source.setConfig(config1);
    String sourceResource = "source/src1";

    File v1 = folder.newFolder();
    File v2 = folder.newFolder();

    doc("create source 1");
    final SourceUI putSource1 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(sourceResource))
                .buildPut(Entity.json(source)),
            SourceUI.class);
    putSource1.setTag(waitForSourceModificationAndGetNewTag(putSource1.getName()));

    doc("update source 1");
    ((NASConf) putSource1.getConfig()).path = v1.getAbsolutePath();
    final SourceUI putSource2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(sourceResource))
                .buildPut(Entity.json(putSource1)),
            SourceUI.class);
    assertEquals(((NASConf) putSource1.getConfig()).path, ((NASConf) putSource2.getConfig()).path);

    doc("update source 1 based on previous version");
    ((NASConf) putSource1.getConfig()).path = v2.getAbsolutePath();
    expectStatus(
        CONFLICT,
        getBuilder(getHttpClient().getAPIv2().path(sourceResource))
            .buildPut(Entity.json(putSource1)),
        UserExceptionMapper.ErrorMessageWithContext.class);

    doc("delete with missing version");
    final GenericErrorMessage errorDelete =
        expectStatus(
            BAD_REQUEST,
            getBuilder(getHttpClient().getAPIv2().path(sourceResource)).buildDelete(),
            GenericErrorMessage.class);
    assertThat(errorDelete.getErrorMessage())
        .isEqualTo(GenericErrorMessage.MISSING_VERSION_PARAM_MSG);

    doc("delete with bad version");
    long badVersion = 1234L;
    String expectedErrorMessage =
        String.format(
            "Cannot delete source \"%s\", version provided \"%s\" is different from version found \"%s\"",
            source.getName(), badVersion, putSource2.getTag());
    final GenericErrorMessage errorDelete2 =
        expectStatus(
            CONFLICT,
            getBuilder(getHttpClient().getAPIv2().path(sourceResource).queryParam("version", 1234L))
                .buildDelete(),
            GenericErrorMessage.class);
    assertThat(errorDelete2.getErrorMessage()).isEqualTo(expectedErrorMessage);

    doc("delete");
    expectSuccess(
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(sourceResource)
                    .queryParam("version", putSource2.getTag()))
            .buildDelete());
  }

  @Test // fix for DX-1469
  public void testNASSubDirectory() throws Exception {
    File dataSetDir = new File((System.getProperty("user.dir") + "/src/test/resources/datasets"));
    // get all subdirs
    String[] directories =
        dataSetDir.list(
            new FilenameFilter() {
              @Override
              public boolean accept(File current, String name) {
                return new File(current, name).isDirectory();
              }
            });
    int dataSetFiles = dataSetDir.listFiles().length - directories.length;

    // create a NAS space that points to some sub-folder of the file system
    final SourceService sourceService = getSourceService();
    {
      SourceUI sourceUI = new SourceUI();
      sourceUI.setName("nas_sub");
      final NASConf nas = new NASConf();
      nas.path = System.getProperty("user.dir") + "/src/test/resources/datasets";
      sourceUI.setConfig(nas);
      sourceService.registerSourceWithRuntime(
          sourceUI, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    }

    final SourceUI source =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("source/nas_sub")).buildGet(),
            SourceUI.class);
    final NamespaceTree tree = source.getContents();

    // make sure we didn't get the root's content
    assertEquals(
        "source should only list the content of the subfolder",
        directories.length,
        tree.getFolders().size());
    assertEquals(
        "source should only list the content of the subfolder",
        dataSetFiles,
        tree.getFiles().size());

    assertNull(
        expectSuccess(
                getBuilder(
                        getHttpClient()
                            .getAPIv2()
                            .path("source/nas_sub")
                            .queryParam("includeContents", false))
                    .buildGet(),
                SourceUI.class)
            .getContents());
  }

  @Test
  public void testInvalidSpace() throws Exception {
    expectError(
        CLIENT_ERROR,
        getBuilder(getHttpClient().getAPIv2().path("space/A.B"))
            .buildPut(Entity.json(new Space(null, "A.B", null, null, null, 0, null))),
        ValidationErrorMessage.class);
  }

  @Test
  public void testValidSpace() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "AB", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
  }

  @Test
  public void testInvalidSource() throws Exception {
    SourceUI sourceUI = new SourceUI();
    sourceUI.setName("A.B");
    NASConf sourceConfig = new NASConf();
    sourceConfig.path = "/";
    sourceUI.setConfig(sourceConfig);
    expectError(
        CLIENT_ERROR,
        getBuilder(getHttpClient().getAPIv2().path("source/A.B")).buildPut(Entity.json(sourceUI)),
        ValidationErrorMessage.class);
  }

  @Test
  public void testValidSource() throws Exception {
    SourceUI sourceUI = new SourceUI();
    sourceUI.setName("AB");

    NASConf sourceConfig = new NASConf();
    sourceConfig.path = "/";
    sourceUI.setConfig(sourceConfig);

    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("source/AB")).buildPut(Entity.json(sourceUI)));
  }

  @Test
  public void testSpaces() throws Exception {
    final NamespaceService namespaceService = getNamespaceService();
    final SpaceConfig config1 = new SpaceConfig();
    final SpaceConfig config2 = new SpaceConfig();

    config1.setName("space1");
    config1.setDescription("space1");

    config2.setName("space2");
    config2.setDescription("space2");

    namespaceService.addOrUpdateSpace(
        new SpacePath(new SpaceName(config2.getName())).toNamespaceKey(), config2);
    namespaceService.addOrUpdateSpace(
        new SpacePath(new SpaceName(config1.getName())).toNamespaceKey(), config1);

    final Space space1 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/space1")).buildGet(), Space.class);
    assertEquals(config1.getName(), space1.getName());

    final Space space2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/space2")).buildGet(), Space.class);
    assertEquals(config2.getName(), space2.getName());

    final com.dremio.dac.api.Space space3 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
                .buildPost(
                    Entity.json(new com.dremio.dac.api.Space(null, "space3", null, null, null))),
            new GenericType<com.dremio.dac.api.Space>() {});
    assertEquals("space3", space3.getName());

    final UserService userService = getUserService();
    User dt =
        SimpleUser.newBuilder()
            .setUserName("user")
            .setCreatedAt(System.currentTimeMillis())
            .setEmail("user@mail.com")
            .setFirstName("User")
            .setLastName("Anonymous")
            .build();
    dt = userService.createUser(dt, "user1234");
    UserLoginSession uls =
        expectSuccess(
            getHttpClient()
                .getAPIv2()
                .path("/login")
                .request(JSON)
                .buildPost(Entity.json(new UserLogin("user", "user1234"))),
            UserLoginSession.class);

    final com.dremio.dac.api.Space space4 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
                .buildPost(
                    Entity.json(new com.dremio.dac.api.Space(null, "space4", null, null, null))),
            new GenericType<com.dremio.dac.api.Space>() {});
    assertEquals("space4", space4.getName());

    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "test1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "test2", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    final JobFilterItems spaces2 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path("jobs/filters/spaces")
                        .queryParam("filter", "test"))
                .buildGet(),
            JobFilterItems.class);
    assertEquals(spaces2.toString(), 2, spaces2.getItems().size());

    final JobFilterItems spaces3 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path("jobs/filters/spaces")
                        .queryParam("filter", "space")
                        .queryParam("limit", 3))
                .buildGet(),
            JobFilterItems.class);
    assertEquals(spaces3.toString(), 3, spaces3.getItems().size());

    userService.deleteUser(dt.getUserName(), dt.getVersion());
  }

  @Test
  public void testDataGrid() throws Exception {
    try (AutoCloseable ignored =
        withSystemOption(ExecConstants.PARQUET_AUTO_CORRECT_DATES_VALIDATOR, true)) {
      TestSpacesStoragePlugin.setup();

      WebTarget pathA =
          getHttpClient().getAPIv2().path(getPathJoiner().join("dataset", "testA.dsA3"));
      DatasetUI datasetUIA = expectSuccess(getBuilder(pathA).buildGet(), DatasetUI.class);

      InitialPreviewResponse previewResponseA =
          expectSuccess(
              getBuilder(
                      getHttpClient()
                          .getAPIv2()
                          .path(
                              getPathJoiner()
                                  .join(
                                      "dataset",
                                      "testA.dsA3",
                                      "version",
                                      datasetUIA.getDatasetVersion(),
                                      "preview")))
                  .buildGet(),
              InitialPreviewResponse.class);

      waitForJobComplete(previewResponseA.getJobId().getId());
      final JobDataFragment dataA =
          getHttpClient().getDatasetApi().getJobData(previewResponseA, 0, INITIAL_RESULTSET_SIZE);

      assertEquals(10, dataA.getReturnedRowCount());
      assertEquals(4, dataA.getColumns().size());
      assertEquals(
          asList(
                  new Column("l_orderkey", INTEGER, 0),
                  new Column("revenue", FLOAT, 1),
                  new Column("o_orderdate", DATE, 2),
                  new Column("o_shippriority", INTEGER, 3))
              .toString(),
          dataA.getColumns().toString());

      DatasetUI datasetUIB =
          expectSuccess(
              getBuilder(
                      getHttpClient()
                          .getAPIv2()
                          .path(getPathJoiner().join("dataset", "testB.dsB1")))
                  .buildGet(),
              DatasetUI.class);

      InitialPreviewResponse previewResponseB =
          expectSuccess(
              getBuilder(
                      getHttpClient()
                          .getAPIv2()
                          .path(
                              getPathJoiner()
                                  .join(
                                      "dataset",
                                      "testB.dsB1",
                                      "version",
                                      datasetUIB.getDatasetVersion(),
                                      "preview")))
                  .buildGet(),
              InitialPreviewResponse.class);

      waitForJobComplete(previewResponseB.getJobId().getId());
      final JobDataFragment dataB =
          getHttpClient().getDatasetApi().getJobData(previewResponseB, 0, INITIAL_RESULTSET_SIZE);

      assertEquals(INITIAL_RESULTSET_SIZE, dataB.getReturnedRowCount());
      assertEquals(2, dataB.getColumns().size());
      assertEquals(DataType.INTEGER, dataB.getColumns().get(0).getType());
      assertEquals(DataType.INTEGER, dataB.getColumns().get(1).getType());
      TestSpacesStoragePlugin.cleanup();

      final JobDataFragment dataBMore =
          getHttpClient().getDatasetApi().getJobData(previewResponseB, 20L, 200L);
      assertEquals(200, dataBMore.getReturnedRowCount());
      assertEquals(2, dataBMore.getColumns().size());
      assertEquals(DataType.INTEGER, dataBMore.getColumns().get(0).getType());
      assertEquals(DataType.INTEGER, dataBMore.getColumns().get(1).getType());
    }
  }

  @Test
  public void testFolderOCC() throws Exception {

    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "s1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    String spaceResource = "space/s1/folder/f1";
    doc("create folder 1");
    final FolderModel postFolder1 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/"))
                .buildPost(Entity.json("{\"name\": \"f1\"}")),
            FolderModel.class);

    // Currently we use the same method to create new and update existing entries. This doesn't help
    // throwing proper errors to client
    //    doc("create folder 1 again");
    //    GenericErrorMessage errorPut = expectStatus(CONFLICT,
    // getBuilder(getAPIv2().path(spaceResource)).buildPut(Entity.json("")),
    // GenericErrorMessage.class);
    //    assertErrorMessage(errorPut, "tried to create, found previous version 0");

    doc("delete with missing version");
    final GenericErrorMessage errorDelete =
        expectStatus(
            BAD_REQUEST,
            getBuilder(getHttpClient().getAPIv2().path(spaceResource)).buildDelete(),
            GenericErrorMessage.class);
    assertThat(errorDelete.getErrorMessage())
        .isEqualTo(GenericErrorMessage.MISSING_VERSION_PARAM_MSG);

    doc("delete with bad version");
    long badVersion = 1234L;
    String expectedErrorMessage =
        String.format(
            "Cannot delete folder \"%s\", version provided \"%s\" is different from version found \"%s\"",
            postFolder1.getName(), badVersion, postFolder1.getVersion());
    final GenericErrorMessage errorDelete2 =
        expectStatus(
            CONFLICT,
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(spaceResource)
                        .queryParam("version", badVersion))
                .buildDelete(),
            GenericErrorMessage.class);
    assertThat(errorDelete2.getErrorMessage()).isEqualTo(expectedErrorMessage);

    doc("delete");
    expectSuccess(
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(spaceResource)
                    .queryParam("version", postFolder1.getVersion()))
            .buildDelete());
  }

  @Test
  public void testFolder() throws Exception {
    // create spaces.
    doc("create spaces");
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "s1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "s2", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "s3", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    doc("create folders");
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")),
        FolderModel.class);
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s2/folder/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")),
        FolderModel.class);
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s3/folder/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")),
        FolderModel.class);

    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")),
        FolderModel.class);
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/"))
            .buildPost(Entity.json("{\"name\": \"f2\"}")),
        FolderModel.class);
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")),
        FolderModel.class);
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/f1"))
            .buildPost(Entity.json("{\"name\": \"f2\"}")),
        FolderModel.class);

    doc("get folder config");
    FolderModel s1f1 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1")).buildGet(),
            FolderModel.class);
    assertEquals("f1", s1f1.getName());
    Assert.assertArrayEquals(new String[] {"s1", "f1"}, s1f1.getFullPathList().toArray());

    doc("folder contents");
    NamespaceTree lists1f1 = s1f1.getContents();
    assertEquals(0, lists1f1.getDatasets().size());
    assertEquals(2, lists1f1.getFolders().size());

    doc("folder with no content");
    FolderModel noContents1f1 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path("space/s1/folder/f1")
                        .queryParam("includeContents", false))
                .buildGet(),
            FolderModel.class);
    assertEquals("f1", noContents1f1.getName());
    Assert.assertArrayEquals(new String[] {"s1", "f1"}, noContents1f1.getFullPathList().toArray());
    assertNull(noContents1f1.getContents());

    NamespaceTree lists1f1f2 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f2")).buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(0, lists1f1f2.getDatasets().size());
    assertEquals(0, lists1f1f2.getFolders().size());

    NamespaceTree lists1f1f1f1 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/f1")).buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(0, lists1f1f1f1.getDatasets().size());
    assertEquals(1, lists1f1f1f1.getFolders().size());

    NamespaceTree lists1f1f1f1f2 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/f1/f2"))
                    .buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(0, lists1f1f1f1f2.getDatasets().size());
    assertEquals(0, lists1f1f1f1f2.getFolders().size());

    FolderModel f2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/f1/f2")).buildGet(),
            FolderModel.class);
    expectSuccess(
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("space/s1/folder/f1/f1/f1/f2")
                    .queryParam("version", f2.getVersion()))
            .buildDelete());

    lists1f1f1f1 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/f1")).buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(0, lists1f1f1f1.getDatasets().size());
    assertEquals(0, lists1f1f1f1.getFolders().size());

    doc("create datasets");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(new DatasetPath("s1.ds1"), "cp.\"tpch/supplier.parquet\"");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(new DatasetPath("s2.ds1"), "cp.\"tpch/supplier.parquet\"");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(new DatasetPath("s2.ds2"), "cp.\"tpch/supplier.parquet\"");

    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(
            new DatasetPath("s1.f1.ds1"), "cp.\"tpch/supplier.parquet\"");

    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(
            new DatasetPath("s1.f1.f1.f1.ds1"), "cp.\"tpch/supplier.parquet\"");

    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(
            new DatasetPath("s1.f1.ds2"), "cp.\"tpch/supplier.parquet\"");

    lists1f1f1f1 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f1/f1")).buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(1, lists1f1f1f1.getDatasets().size());
    assertEquals(0, lists1f1f1f1.getFolders().size());

    lists1f1 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1")).buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(2, lists1f1.getDatasets().size());
    assertEquals(2, lists1f1.getFolders().size());

    lists1f1f2 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/f1/f2")).buildGet(),
                FolderModel.class)
            .getContents();
    assertEquals(0, lists1f1f2.getDatasets().size());
    assertEquals(0, lists1f1f2.getFolders().size());

    // List spaces
    // TODO we may be able to list spaces using GET folder on space.
    NamespaceTree lists1 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s1")).buildGet(), Space.class)
            .getContents();
    assertEquals(1, lists1.getDatasets().size());
    assertEquals(1, lists1.getFolders().size());

    NamespaceTree lists2 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s2")).buildGet(), Space.class)
            .getContents();
    assertEquals(2, lists2.getDatasets().size());
    assertEquals(1, lists2.getFolders().size());

    NamespaceTree lists3 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/s3")).buildGet(), Space.class)
            .getContents();
    assertEquals(0, lists3.getDatasets().size());
    assertEquals(1, lists3.getFolders().size());

    assertNull(
        expectSuccess(
                getBuilder(
                        getHttpClient()
                            .getAPIv2()
                            .path("space/s1")
                            .queryParam("includeContents", false))
                    .buildGet(),
                Space.class)
            .getContents());
  }

  @Test
  public void testFolderParentNotFound() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getAPIv3().path("/catalog/"))
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "s1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")),
        FolderModel.class);

    expectStatus(
        Status.BAD_REQUEST,
        getBuilder(getHttpClient().getAPIv2().path("space/s1/folder/wrongfolder/"))
            .buildPost(Entity.json("{\"name\": \"f1\"}")));
  }

  @Test
  public void testSourceTraversal() throws Exception {
    populateInitialData();
    SourceUI source =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("source/LocalFS1")).buildGet(),
            SourceUI.class);
    NamespaceTree ns = source.getContents();

    assertNotNull(source.getId());
    assertTrue(ns.getDatasets().size() + ns.getFolders().size() + ns.getFiles().size() > 0);
  }

  @Test
  @Ignore // TODO DX-3144
  public void testTestApis() {
    doc("Creating test dataset");
    NamespaceService ns = getNamespaceService();
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("/test/create")).buildPost(Entity.json("")));
    assertEquals(4, ns.getSpaces().size());
    assertEquals(1, ns.getHomeSpaces().size());
    doc("Clearing all data");
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("/test/clear")).buildPost(Entity.json("")));
    assertEquals(0, ns.getSpaces().size());
    assertEquals(0, ns.getHomeSpaces().size());
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("/test/create")).buildPost(Entity.json("")));
    assertEquals(4, ns.getSpaces().size());
    assertEquals(1, ns.getHomeSpaces().size());
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("/test/clear")).buildPost(Entity.json("")));
    assertEquals(0, ns.getSpaces().size());
    assertEquals(0, ns.getHomeSpaces().size());
  }

  @Test
  public void testDateObjectSerialization() {
    assertSerialization("/test/getDateTime", "\"2011-02-03T00:00:00\"");
    assertSerialization("/test/getDate", "\"2011-02-03\"");
  }

  private void assertSerialization(String endpoint, String expectedValue) {
    Response response = getBuilder(getHttpClient().getAPIv2().path(endpoint)).buildGet().invoke();
    assertEquals(200, response.getStatus());
    String result = response.readEntity(String.class);
    assertEquals(expectedValue, result);
  }

  @Test
  public void testDatasetJobCount() throws Exception {
    // create home
    final NamespaceService ns = getNamespaceService();
    getPopulator().populateTestUsers();
    addSpace(ns, "space1");
    addFolder(ns, "@" + DEFAULT_USERNAME + ".f1");
    addFolder(ns, "space1.f2");

    DatasetPath datasetPath1 = new DatasetPath("@" + DEFAULT_USERNAME + ".f1.ds1");
    DatasetPath datasetPath2 = new DatasetPath("space1.f2.ds2");
    DatasetPath datasetPath3 = new DatasetPath("space1.ds3");

    doc("create datasets");
    DatasetUI ds1 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(datasetPath1, "cp.\"tpch/supplier.parquet\"");
    DatasetUI ds2 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(datasetPath2, "cp.\"tpch/supplier.parquet\"");
    DatasetUI ds3 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(datasetPath3, "cp.\"tpch/supplier.parquet\"");

    doc("run jobs");
    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder()
            .setSqlQuery(getQueryFromConfig(ds1))
            .setQueryType(QueryType.UI_RUN)
            .setDatasetPath(datasetPath1.toNamespaceKey())
            .setDatasetVersion(ds1.getDatasetVersion())
            .build());
    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder()
            .setSqlQuery(getQueryFromConfig(ds2))
            .setQueryType(QueryType.UI_RUN)
            .setDatasetPath(datasetPath2.toNamespaceKey())
            .setDatasetVersion(ds2.getDatasetVersion())
            .build());
    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder()
            .setSqlQuery(getQueryFromConfig(ds3))
            .setQueryType(QueryType.UI_RUN)
            .setDatasetPath(datasetPath3.toNamespaceKey())
            .setDatasetVersion(ds3.getDatasetVersion())
            .build());
    submitJobAndWaitUntilCompletion(
        JobRequest.newBuilder()
            .setSqlQuery(getQueryFromConfig(ds2))
            .setQueryType(QueryType.UI_RUN)
            .setDatasetPath(datasetPath2.toNamespaceKey())
            .setDatasetVersion(ds2.getDatasetVersion())
            .build());

    doc("get home");
    Home home =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("home/@" + DEFAULT_USERNAME)).buildGet(),
            Home.class);
    assertEquals(1, (long) home.getHomeConfig().getExtendedConfig().getDatasetCount());

    doc("home contents");
    NamespaceTree nst = home.getContents();
    assertEquals(1, nst.getFolders().size());

    doc("get space");
    final Space space1 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/space1")).buildGet(), Space.class);
    assertEquals(2, space1.getDatasetCount());

    doc("get folder");
    FolderModel folder2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("space/space1/folder/f2")).buildGet(),
            FolderModel.class);
    assertEquals("f2", folder2.getName());

    doc("list inside space");
    NamespaceTree lists1f1 =
        expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("space/space1")).buildGet(), Space.class)
            .getContents();
    assertEquals(1, lists1f1.getFolders().size());
  }

  @Test
  public void testDatasetSummary() throws Exception {
    populateInitialData();
    doc("get dataset summary for virtual dataset DG.dsg3");
    DatasetSummary summary =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/datasets/summary/DG/dsg3")).buildGet(),
            DatasetSummary.class);
    assertEquals(6, (int) summary.getDescendants());
    assertEquals(0, (int) summary.getJobCount());
    assertEquals(3, summary.getFields().size());
    assertEquals(Arrays.asList("tag1", "tag2"), summary.getTags());
    assertEquals(summary.getEntityId(), UUID.fromString(summary.getEntityId()).toString());
    assertFalse(summary.getHasReflection());
    assertTrue(summary.getCreatedAt() <= summary.getLastModified());
    assertTrue(summary.getViewSpecVersion() == null);
    assertTrue(summary.getViewDialect() == null);
    doc("get dataset summary for virtual dataset DG.dsg4 with empty tags");
    summary =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/datasets/summary/DG/dsg4")).buildGet(),
            DatasetSummary.class);
    assertEquals(new ArrayList<>(), summary.getTags());

    doc("get dataset summary for physical dataset");
    summary =
        expectSuccess(
            getBuilder(
                    getHttpClient().getAPIv2().path("/datasets/summary/LocalFS1/dac-sample1.json"))
                .buildGet(),
            DatasetSummary.class);
    assertEquals(10, (int) summary.getDescendants());
    assertEquals(0, (int) summary.getJobCount());
    assertEquals(3, summary.getFields().size());
    assertEquals(Arrays.asList("tag3", "tag4"), summary.getTags());
    assertEquals(summary.getEntityId(), UUID.fromString(summary.getEntityId()).toString());
    assertFalse(summary.getHasReflection());
    assertTrue(summary.getCreatedAt() <= summary.getLastModified());
    assertTrue(summary.getViewSpecVersion() == null);
    assertTrue(summary.getViewDialect() == null);
    doc("get dataset summary for physical dataset with empty tags");
    summary =
        expectSuccess(
            getBuilder(
                    getHttpClient().getAPIv2().path("/datasets/summary/LocalFS2/dac-sample2.json"))
                .buildGet(),
            DatasetSummary.class);
    assertEquals(new ArrayList<>(), summary.getTags());
  }

  @Test
  public void testDatasetSummaryWithReferences() throws Exception {
    populateInitialData();
    doc("get dataset summary for dataset DG.dsg3 with version context in query params");
    Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "DG", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    WebTarget webTarget =
        getHttpClient()
            .getAPIv2()
            .path("/datasets/summary/DG/dsg3")
            .queryParam("refType", "BRANCH")
            .queryParam("refValue", "branchtest");
    DatasetSummary summary = expectSuccess(getBuilder(webTarget).buildGet(), DatasetSummary.class);
    assertEquals(6, (int) summary.getDescendants());
    assertEquals(0, (int) summary.getJobCount());
    assertEquals(3, summary.getFields().size());
    assertEquals(summary.getEntityId(), UUID.fromString(summary.getEntityId()).toString());
    assertFalse(summary.getHasReflection());
    assertTrue(summary.getCreatedAt() <= summary.getLastModified());

    references = new HashMap<>();
    references.put("DG", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    webTarget =
        getHttpClient()
            .getAPIv2()
            .path("/datasets/summary/DG/dsg3")
            .queryParam("refType", "TAG")
            .queryParam("refValue", "tagtest");
    summary = expectSuccess(getBuilder(webTarget).buildGet(), DatasetSummary.class);
    assertEquals(6, (int) summary.getDescendants());
    assertEquals(0, (int) summary.getJobCount());
    assertEquals(3, summary.getFields().size());
    assertEquals(summary.getEntityId(), UUID.fromString(summary.getEntityId()).toString());
    assertFalse(summary.getHasReflection());
    assertTrue(summary.getCreatedAt() <= summary.getLastModified());

    references = new HashMap<>();
    references.put(
        "DG",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    webTarget =
        getHttpClient()
            .getAPIv2()
            .path("/datasets/summary/DG/dsg3")
            .queryParam("refType", "COMMIT")
            .queryParam("refValue", "d0628f078890fec234b98b873f9e1f3cd140988a");
    summary = expectSuccess(getBuilder(webTarget).buildGet(), DatasetSummary.class);
    assertEquals(6, (int) summary.getDescendants());
    assertEquals(0, (int) summary.getJobCount());
    assertEquals(3, summary.getFields().size());
    assertEquals(summary.getEntityId(), UUID.fromString(summary.getEntityId()).toString());
    assertFalse(summary.getHasReflection());
    assertTrue(summary.getCreatedAt() <= summary.getLastModified());
    assertThat(summary.getReferences()).usingRecursiveComparison().isEqualTo(references);

    webTarget =
        getHttpClient()
            .getAPIv2()
            .path("/datasets/summary/DG/dsg3")
            .queryParam("refType", "INVALID")
            .queryParam("refValue", "invalid");
    expectStatus(Status.BAD_REQUEST, getBuilder(webTarget).buildGet());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testDatasetParents() throws Exception {
    populateInitialData();
    VirtualDatasetUI dsg10 = getDatasetVersionMutator().get(new DatasetPath("DG.dsg10"));
    doc("get parents for virtual dataset DG.dsg10");
    List<ParentDatasetUI> parents =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(
                            "/dataset/DG.dsg10/version/"
                                + dsg10.getVersion().toString()
                                + "/parents"))
                .buildGet(),
            List.class);
    assertEquals(2, parents.size());
  }

  @Test
  public void testNewUntitledFromSql() throws Exception {
    final String query = "select * from sys.version";
    final Invocation invocation =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_untitled_sql")
                    .queryParam("newVersion", newVersion()))
            .buildPost(
                Entity.entity(new CreateFromSQL(query, null), MediaType.APPLICATION_JSON_TYPE));
    InitialPreviewResponse previewResponse =
        expectSuccess(invocation, InitialPreviewResponse.class);
    assertTrue(previewResponse.isApproximate());
    assertEquals(
        previewResponse.getDataset().getDatasetVersion(),
        previewResponse.getHistory().getCurrentDatasetVersion());
    assertEquals(query, previewResponse.getDataset().getSql());
    assertNull(previewResponse.getDataset().getJobCount());
  }

  @Test
  public void testNewTmpUntitledFromSql() throws Exception {
    final String query = "select * from sys.version";
    final DatasetVersion version = newVersion();
    final Invocation invocation =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_tmp_untitled_sql")
                    .queryParam("newVersion", version))
            .buildPost(
                Entity.entity(new CreateFromSQL(query, null), MediaType.APPLICATION_JSON_TYPE));
    InitialUntitledRunResponse runResponse =
        expectSuccess(invocation, InitialUntitledRunResponse.class);
    assertEquals(Arrays.asList("tmp", "UNTITLED"), runResponse.getDatasetPath());
    assertEquals(version.getVersion(), runResponse.getDatasetVersion());
    assertNotNull(runResponse.getPaginationUrl());
    assertNotNull(runResponse.getJobId());
    assertNotNull(runResponse.getSessionId());
  }

  @Test
  public void testNewUntitledFromSqlWithError() throws Exception {
    final String query = "select * from values(0)";
    final Invocation invocation =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_untitled_sql")
                    .queryParam("newVersion", newVersion()))
            .buildPost(
                Entity.entity(new CreateFromSQL(query, null), MediaType.APPLICATION_JSON_TYPE));
    Response previewResponse = expectStatus(BAD_REQUEST, invocation);
    ApiErrorModel<InvalidQueryException.Details> error =
        previewResponse.readEntity(
            new GenericType<ApiErrorModel<InvalidQueryException.Details>>() {});
    assertEquals(ApiErrorModel.ErrorType.INVALID_QUERY, error.getCode());
    assertEquals(query, error.getDetails().getSql());
    assertEquals(1, error.getDetails().getErrors().size());
    QueryError queryError = error.getDetails().getErrors().get(0);

    assertThat(queryError.getMessage()).contains("Was expecting one of");
    assertEquals(1, queryError.getRange().getStartLine());
    assertEquals(10, queryError.getRange().getStartColumn());
    assertEquals(1, queryError.getRange().getEndLine());
    assertEquals(13, queryError.getRange().getEndColumn());
  }

  @Test
  public void testNewTmpUntitledFromSqlWithError() throws Exception {
    final String query = "select * from values(0)";
    final Invocation invocation =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_tmp_untitled_sql")
                    .queryParam("newVersion", newVersion()))
            .buildPost(
                Entity.entity(new CreateFromSQL(query, null), MediaType.APPLICATION_JSON_TYPE));
    // Even if the query is invalid, the command will return success though
    Response response = expectStatus(OK, invocation);
  }

  @Test
  public void testNewUntitledFromSqlAndRun() throws Exception {
    final String query = "select * from sys.version";
    final Invocation invocation =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_untitled_sql_and_run")
                    .queryParam("newVersion", newVersion()))
            .buildPost(
                Entity.entity(new CreateFromSQL(query, null), MediaType.APPLICATION_JSON_TYPE));
    InitialRunResponse runResponse = expectSuccess(invocation, InitialRunResponse.class);
    assertFalse(runResponse.isApproximate());
    assertEquals(
        runResponse.getDataset().getDatasetVersion(),
        runResponse.getHistory().getCurrentDatasetVersion());
    assertEquals(query, runResponse.getDataset().getSql());
    assertNull(runResponse.getDataset().getJobCount());
  }

  @Test
  public void testNewTmpUntitledFromSqlAndRun() throws Exception {
    final String query = "select * from sys.version";
    final DatasetVersion version = newVersion();
    final Invocation invocation =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_tmp_untitled_sql_and_run")
                    .queryParam("newVersion", version))
            .buildPost(
                Entity.entity(new CreateFromSQL(query, null), MediaType.APPLICATION_JSON_TYPE));
    InitialUntitledRunResponse runResponse =
        expectSuccess(invocation, InitialUntitledRunResponse.class);
    assertEquals(Arrays.asList("tmp", "UNTITLED"), runResponse.getDatasetPath());
    assertEquals(version.getVersion(), runResponse.getDatasetVersion());
    assertNotNull(runResponse.getPaginationUrl());
    assertNotNull(runResponse.getJobId());
    assertNotNull(runResponse.getSessionId());
  }

  @Test
  public void testNewUntitledFromSqlAndRunWithReferences() throws Exception {
    final String query = "select * from sys.version";
    final Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    final Response invoke =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_untitled_sql_and_run")
                    .queryParam("newVersion", newVersion()))
            .buildPost(
                Entity.entity(
                    new CreateFromSQL(query, null, references, null),
                    MediaType.APPLICATION_JSON_TYPE))
            .invoke();
    assertEquals(200, invoke.getStatus());
  }

  @Test
  public void testNewTmpUntitledFromSqlAndRunWithReferences() throws Exception {
    final String query = "select * from sys.version";
    final Map<String, VersionContextReq> references = new HashMap<>();
    references.put(
        "source1", new VersionContextReq(VersionContextReq.VersionContextType.BRANCH, "branch"));
    references.put(
        "source2", new VersionContextReq(VersionContextReq.VersionContextType.TAG, "tag"));
    references.put(
        "source3",
        new VersionContextReq(
            VersionContextReq.VersionContextType.COMMIT,
            "d0628f078890fec234b98b873f9e1f3cd140988a"));
    final Response invoke =
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path("datasets/new_tmp_untitled_sql_and_run")
                    .queryParam("newVersion", newVersion()))
            .buildPost(
                Entity.entity(
                    new CreateFromSQL(query, null, references, null),
                    MediaType.APPLICATION_JSON_TYPE))
            .invoke();
    assertEquals(200, invoke.getStatus());
  }

  @Test
  public void testHeaders() throws Exception {
    final Response invoke =
        getBuilder(getHttpClient().getAPIv3().path("catalog")).buildGet().invoke();
    final MultivaluedMap<String, Object> headers = invoke.getHeaders();
    assertTrue(headers.containsKey("x-content-type-options"));
    assertTrue(headers.containsKey("x-frame-options"));
    assertTrue(headers.containsKey("x-xss-protection"));
    assertTrue(headers.containsKey("content-security-policy"));
  }

  @Test
  public void testGenericResponseHeaders() throws Exception {
    final Response invoke =
        getBuilder(getHttpClient().getAPIv3().path("catalog")).buildGet().invoke();
    final MultivaluedMap<String, Object> headersV3 = invoke.getHeaders();
    assertTrue(headersV3.containsKey(HttpHeaders.CACHE_CONTROL));
    assertEquals(headersV3.getFirst(HttpHeaders.CACHE_CONTROL), "no-cache, no-store");

    final Response invoke2 =
        getBuilder(getHttpClient().getAPIv2().path("source/nas_sub")).buildGet().invoke();
    final MultivaluedMap<String, Object> headersV2 = invoke2.getHeaders();
    assertTrue(headersV2.containsKey(HttpHeaders.CACHE_CONTROL));
    assertEquals(headersV2.getFirst(HttpHeaders.CACHE_CONTROL), "no-cache, no-store");
  }

  @Test
  public void testErrorResponse() throws Exception {
    final Response response =
        getBuilder(getHttpClient().getAPIRoot().path("v2").path("catalog")).buildGet().invoke();
    assertNull(response.getHeaders().get("Server"));
    assertEquals(404, response.getStatus());
  }
}
