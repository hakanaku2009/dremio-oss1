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

import static com.dremio.dac.explore.model.InitialPreviewResponse.INITIAL_RESULTSET_SIZE;
import static com.dremio.dac.proto.model.dataset.ActionForNonMatchingValue.DELETE_RECORDS;
import static com.dremio.dac.proto.model.dataset.ActionForNonMatchingValue.REPLACE_WITH_NULL;
import static com.dremio.dac.proto.model.dataset.ConvertCase.LOWER_CASE;
import static com.dremio.dac.proto.model.dataset.ConvertCase.UPPER_CASE;
import static com.dremio.dac.proto.model.dataset.DataType.BINARY;
import static com.dremio.dac.proto.model.dataset.DataType.DATE;
import static com.dremio.dac.proto.model.dataset.DataType.DATETIME;
import static com.dremio.dac.proto.model.dataset.DataType.FLOAT;
import static com.dremio.dac.proto.model.dataset.DataType.INTEGER;
import static com.dremio.dac.proto.model.dataset.DataType.STRUCT;
import static com.dremio.dac.proto.model.dataset.DataType.TEXT;
import static com.dremio.dac.proto.model.dataset.DataType.TIME;
import static com.dremio.dac.proto.model.dataset.Direction.FROM_THE_END;
import static com.dremio.dac.proto.model.dataset.Direction.FROM_THE_START;
import static com.dremio.dac.proto.model.dataset.ExtractListRuleType.multiple;
import static com.dremio.dac.proto.model.dataset.ExtractListRuleType.single;
import static com.dremio.dac.proto.model.dataset.IntegerConversionType.CEILING;
import static com.dremio.dac.proto.model.dataset.MatchType.exact;
import static com.dremio.dac.proto.model.dataset.MatchType.regex;
import static com.dremio.dac.proto.model.dataset.MeasureType.Count;
import static com.dremio.dac.proto.model.dataset.MeasureType.Count_Star;
import static com.dremio.dac.proto.model.dataset.OrderDirection.ASC;
import static com.dremio.dac.proto.model.dataset.OrderDirection.DESC;
import static com.dremio.dac.proto.model.dataset.ReplaceSelectionType.CONTAINS;
import static com.dremio.dac.proto.model.dataset.ReplaceSelectionType.STARTS_WITH;
import static com.dremio.dac.proto.model.dataset.ReplaceType.SELECTION;
import static com.dremio.dac.proto.model.dataset.SplitPositionType.ALL;
import static com.dremio.dac.proto.model.dataset.SplitPositionType.FIRST;
import static com.dremio.dac.proto.model.dataset.SplitPositionType.INDEX;
import static com.dremio.dac.proto.model.dataset.TrimType.BOTH;
import static com.dremio.dac.server.FamilyExpectation.CLIENT_ERROR;
import static com.dremio.dac.util.DatasetsUtil.position;
import static com.dremio.service.namespace.dataset.DatasetVersion.newVersion;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.client.Entity.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.dremio.common.util.DremioVersionInfo;
import com.dremio.dac.explore.model.CellPOJO;
import com.dremio.dac.explore.model.Column;
import com.dremio.dac.explore.model.CreateFromSQL;
import com.dremio.dac.explore.model.DataPOJO;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.explore.model.DatasetUI;
import com.dremio.dac.explore.model.DatasetUIWithHistory;
import com.dremio.dac.explore.model.DatasetVersionResourcePath;
import com.dremio.dac.explore.model.ExtractPreviewReq;
import com.dremio.dac.explore.model.FieldTransformationBase;
import com.dremio.dac.explore.model.HistogramValue;
import com.dremio.dac.explore.model.HistoryItem;
import com.dremio.dac.explore.model.InitialDataPreviewResponse;
import com.dremio.dac.explore.model.InitialPendingTransformResponse;
import com.dremio.dac.explore.model.InitialPreviewResponse;
import com.dremio.dac.explore.model.InitialRunResponse;
import com.dremio.dac.explore.model.JoinRecommendations;
import com.dremio.dac.explore.model.PreviewReq;
import com.dremio.dac.explore.model.ReplacePreviewReq;
import com.dremio.dac.explore.model.ReplaceValuesPreviewReq;
import com.dremio.dac.explore.model.TransformBase;
import com.dremio.dac.explore.model.extract.Card;
import com.dremio.dac.explore.model.extract.Cards;
import com.dremio.dac.explore.model.extract.ExtractCards;
import com.dremio.dac.explore.model.extract.ExtractListCards;
import com.dremio.dac.explore.model.extract.MapSelection;
import com.dremio.dac.explore.model.extract.ReplaceCards;
import com.dremio.dac.explore.model.extract.ReplaceCards.ReplaceValuesCard;
import com.dremio.dac.explore.model.extract.Selection;
import com.dremio.dac.model.job.JobData;
import com.dremio.dac.model.job.JobDataFragment;
import com.dremio.dac.model.sources.SourceUI;
import com.dremio.dac.model.sources.UIMetadataPolicy;
import com.dremio.dac.model.spaces.HomeName;
import com.dremio.dac.model.spaces.HomePath;
import com.dremio.dac.model.spaces.Space;
import com.dremio.dac.model.system.NodeInfo;
import com.dremio.dac.options.TableauResourceOptions;
import com.dremio.dac.proto.model.dataset.ConvertCase;
import com.dremio.dac.proto.model.dataset.DataType;
import com.dremio.dac.proto.model.dataset.Dimension;
import com.dremio.dac.proto.model.dataset.Direction;
import com.dremio.dac.proto.model.dataset.ExtractCard;
import com.dremio.dac.proto.model.dataset.ExtractListCard;
import com.dremio.dac.proto.model.dataset.ExtractListRule;
import com.dremio.dac.proto.model.dataset.ExtractMapRule;
import com.dremio.dac.proto.model.dataset.ExtractRule;
import com.dremio.dac.proto.model.dataset.ExtractRuleMultiple;
import com.dremio.dac.proto.model.dataset.ExtractRulePattern;
import com.dremio.dac.proto.model.dataset.ExtractRulePosition;
import com.dremio.dac.proto.model.dataset.ExtractRuleSingle;
import com.dremio.dac.proto.model.dataset.FieldConvertCase;
import com.dremio.dac.proto.model.dataset.FieldConvertDateToNumber;
import com.dremio.dac.proto.model.dataset.FieldConvertDateToText;
import com.dremio.dac.proto.model.dataset.FieldConvertFloatToDecimal;
import com.dremio.dac.proto.model.dataset.FieldConvertFloatToInteger;
import com.dremio.dac.proto.model.dataset.FieldConvertFromJSON;
import com.dremio.dac.proto.model.dataset.FieldConvertListToText;
import com.dremio.dac.proto.model.dataset.FieldConvertNumberToDate;
import com.dremio.dac.proto.model.dataset.FieldConvertTextToDate;
import com.dremio.dac.proto.model.dataset.FieldConvertToJSON;
import com.dremio.dac.proto.model.dataset.FieldConvertToTypeIfPossible;
import com.dremio.dac.proto.model.dataset.FieldExtract;
import com.dremio.dac.proto.model.dataset.FieldExtractList;
import com.dremio.dac.proto.model.dataset.FieldExtractMap;
import com.dremio.dac.proto.model.dataset.FieldReplaceCustom;
import com.dremio.dac.proto.model.dataset.FieldReplacePattern;
import com.dremio.dac.proto.model.dataset.FieldReplaceRange;
import com.dremio.dac.proto.model.dataset.FieldReplaceValue;
import com.dremio.dac.proto.model.dataset.FieldSimpleConvertToType;
import com.dremio.dac.proto.model.dataset.FieldSplit;
import com.dremio.dac.proto.model.dataset.FieldUnnestList;
import com.dremio.dac.proto.model.dataset.IndexType;
import com.dremio.dac.proto.model.dataset.JoinCondition;
import com.dremio.dac.proto.model.dataset.JoinType;
import com.dremio.dac.proto.model.dataset.ListSelection;
import com.dremio.dac.proto.model.dataset.Measure;
import com.dremio.dac.proto.model.dataset.NumberToDateFormat;
import com.dremio.dac.proto.model.dataset.Offset;
import com.dremio.dac.proto.model.dataset.Order;
import com.dremio.dac.proto.model.dataset.OrderDirection;
import com.dremio.dac.proto.model.dataset.ReplacePatternCard;
import com.dremio.dac.proto.model.dataset.ReplacePatternRule;
import com.dremio.dac.proto.model.dataset.SplitRule;
import com.dremio.dac.proto.model.dataset.TransformAddCalculatedField;
import com.dremio.dac.proto.model.dataset.TransformConvertCase;
import com.dremio.dac.proto.model.dataset.TransformDrop;
import com.dremio.dac.proto.model.dataset.TransformExtract;
import com.dremio.dac.proto.model.dataset.TransformField;
import com.dremio.dac.proto.model.dataset.TransformGroupBy;
import com.dremio.dac.proto.model.dataset.TransformJoin;
import com.dremio.dac.proto.model.dataset.TransformRename;
import com.dremio.dac.proto.model.dataset.TransformSort;
import com.dremio.dac.proto.model.dataset.TransformSorts;
import com.dremio.dac.proto.model.dataset.TransformTrim;
import com.dremio.dac.proto.model.dataset.TransformUpdateSQL;
import com.dremio.dac.proto.model.dataset.VirtualDatasetUI;
import com.dremio.dac.resource.SystemResource;
import com.dremio.dac.service.source.SourceService;
import com.dremio.dac.util.DatasetsUtil;
import com.dremio.dac.util.DatasetsUtil.ExtractRuleVisitor;
import com.dremio.dac.util.JSONUtil;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.SourceRefreshOption;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.dfs.NASConf;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.service.jobs.JobRequest;
import com.dremio.service.jobs.SqlQuery;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.proto.NameSpaceContainer.Type;
import com.dremio.service.namespace.space.proto.HomeConfig;
import com.dremio.test.TemporarySystemProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response.Status;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/** Explore integration tests */
public class TestServerExplore extends BaseTestServer {

  @Rule public final TemporarySystemProperties properties = new TemporarySystemProperties();

  @Before
  public void setup() throws Exception {
    clearAllDataExceptUser();
  }

  @Test
  public void testSaveUsingAncestorName() throws Exception {
    setSpace();

    getHttpClient()
        .getDatasetApi()
        .saveAs(
            getHttpClient()
                .getDatasetApi()
                .createDatasetFromParent("cp.\"tpch/supplier.parquet\"")
                .getDataset(),
            new DatasetPath("spacefoo.boo"));
    getHttpClient()
        .getDatasetApi()
        .saveAs(
            getHttpClient().getDatasetApi().createDatasetFromParent("spacefoo.boo").getDataset(),
            new DatasetPath("spacefoo.boo2"));

    // spacefoo.boo is an ancestor, we expect the following request to fail
    getHttpClient()
        .getDatasetApi()
        .saveAsExpectError(
            getHttpClient().getDatasetApi().createDatasetFromParent("spacefoo.boo2").getDataset(),
            new DatasetPath("spacefoo.boo"));
  }

  @Test
  public void testTableauPhysical() throws Exception {
    testBIEndpoint(
        "tableau",
        () -> {
          final OptionManager optionManager = getOptionManager();
          optionManager.setOption(
              OptionValue.createBoolean(
                  OptionValue.OptionType.SYSTEM,
                  TableauResourceOptions.CLIENT_TOOLS_TABLEAU.getOptionName(),
                  true));
          return null;
        });
  }

  @Test
  public void testPowerBIPhysical() throws Exception {
    testBIEndpoint("powerbi", null);
  }

  @Test
  public void testSaveUsingParentName() throws Exception {
    setSpace();

    getHttpClient()
        .getDatasetApi()
        .saveAs(
            getHttpClient()
                .getDatasetApi()
                .createDatasetFromParent("cp.\"tpch/supplier.parquet\"")
                .getDataset(),
            new DatasetPath("spacefoo.boo"));

    // spacefoo.boo is a parent, we expect the following request to fail
    getHttpClient()
        .getDatasetApi()
        .saveAsExpectError(
            getHttpClient().getDatasetApi().createDatasetFromParent("spacefoo.boo").getDataset(),
            new DatasetPath("spacefoo.boo"));
  }

  @Test
  public void testGetDataset() throws Exception {
    setSpace();
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderbaz.datasetbuzz");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"tpch/supplier.parquet\"");

    DatasetUI dataset = getHttpClient().getDatasetApi().getDataset(datasetPath);
    assertEquals("/dataset/" + datasetPath, resourcePath(dataset));
    assertEquals(
        "/dataset/" + datasetPath + "/version/" + dataset.getDatasetVersion(),
        versionedResourcePath(dataset));

    // Fetch the dataset by dataset resource path (gets the last saved version)
    DatasetUI dataset2 = getHttpClient().getDatasetApi().getDataset(dataset.toDatasetPath());

    // getting by its resource path => should be same
    assertEquals(resourcePath(dataset), resourcePath(dataset2));
    assertEquals(versionedResourcePath(dataset), versionedResourcePath(dataset2));

    // Transform the dataset and make sure the version, SQL and versioned resource path has changed
    InitialPreviewResponse transformResponse =
        getHttpClient().getDatasetApi().transform(dataset, new TransformSort("s_name", ASC));
    DatasetUI transformedDataset = transformResponse.getDataset();
    assertNotEquals(versionedResourcePath(dataset), versionedResourcePath(transformedDataset));
    assertNotEquals(dataset.getDatasetVersion(), transformedDataset.getDatasetVersion());
    assertThat(transformedDataset.getSql().toLowerCase()).contains("order by s_name");

    // Make sure the transfer response also includes initial data
    JobDataFragment data = transformResponse.getData();
    String dataString = JSONUtil.toString(data);
    assertEquals(dataString, 7, data.getColumns().size());
    assertEquals(dataString, INITIAL_RESULTSET_SIZE, data.getReturnedRowCount());

    // Preview the data in initial dataset
    final InitialPreviewResponse previewResponse =
        getHttpClient().getDatasetApi().getPreview(dataset);
    waitForJobComplete(previewResponse.getJobId().getId());

    data = getHttpClient().getDatasetApi().getJobData(previewResponse, 0, INITIAL_RESULTSET_SIZE);

    dataString = JSONUtil.toString(data);
    assertEquals(dataString, 7, data.getColumns().size());
    assertEquals(dataString, "s_suppkey", data.getColumns().get(0).getName());
    assertEquals(dataString, DataType.INTEGER, data.getColumns().get(0).getType());
    assertEquals(dataString, 0, data.getColumns().get(0).getIndex());
    assertEquals(dataString, INITIAL_RESULTSET_SIZE, data.getReturnedRowCount());
    assertNotNull(dataString, data.extractString(data.getColumns().get(0).getName(), 0));

    doc("get specific version");
    DatasetUI datasetWithVersion =
        getHttpClient().getDatasetApi().getVersionedDataset(dataset.toDatasetVersionPath());
    assertEquals(resourcePath(dataset), resourcePath(datasetWithVersion));
    assertEquals(versionedResourcePath(dataset), versionedResourcePath(datasetWithVersion));
    assertEquals(dataset.getSql(), datasetWithVersion.getSql());
  }

  @Test
  public void testPagination() throws Exception {
    setSpace();
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderbaz.datasetbuzz");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"json/mixed_example.json\"");
    DatasetUI dataset = getHttpClient().getDatasetApi().getDataset(datasetPath);

    final InitialPreviewResponse previewResponse =
        getHttpClient().getDatasetApi().getPreview(dataset);
    waitForJobComplete(previewResponse.getJobId().getId());

    JobDataFragment data1 = getHttpClient().getDatasetApi().getJobData(previewResponse, 0, 200);
    assertEquals(105, data1.getReturnedRowCount());

    JobDataFragment data2 = getHttpClient().getDatasetApi().getJobData(previewResponse, 5, 3);
    assertEquals(3, data2.getReturnedRowCount());
  }

  @Test
  public void previewDataForPhysicalDataset() throws Exception {
    final NASConf nas = new NASConf();
    nas.path = new File("src/test/resources/datasets").getAbsolutePath();
    SourceUI source = new SourceUI();
    source.setName("testNAS");
    source.setConfig(nas);
    source.setMetadataPolicy(
        UIMetadataPolicy.of(CatalogService.DEFAULT_METADATA_POLICY_WITH_AUTO_PROMOTE));

    final SourceService sourceService = getSourceService();
    sourceService.registerSourceWithRuntime(source, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);

    InitialDataPreviewResponse resp =
        getHttpClient()
            .getDatasetApi()
            .getPreview(new DatasetPath(asList("testNAS", "users.json")));

    assertEquals(3, resp.getData().getReturnedRowCount());
    assertEquals(2, resp.getData().getColumns().size());

    JobDataFragment data1 = getHttpClient().getDatasetApi().getJobData(resp, 0, 2);
    assertEquals(2, data1.getReturnedRowCount());
    assertEquals(2, data1.getColumns().size());
  }

  @Test
  public void previewDataForVirtualDataset() throws Exception {
    setSpace();
    DatasetPath datasetPath = new DatasetPath("spacefoo.previewVirtualDataset");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"json/strings.json\"");

    InitialDataPreviewResponse resp = getHttpClient().getDatasetApi().getPreview(datasetPath);
    assertEquals(7, resp.getData().getReturnedRowCount());
    assertEquals(2, resp.getData().getColumns().size());

    JobDataFragment data1 = getHttpClient().getDatasetApi().getJobData(resp, 0, 2);
    assertEquals(2, data1.getReturnedRowCount());
    assertEquals(2, data1.getColumns().size());
  }

  @Test
  public void testGetNonExistentDataset() {
    expectStatus(
        Status.NOT_FOUND,
        getHttpClient()
            .getDatasetApi()
            .getDatasetInvocation(new DatasetPath("Tpch-Sample.tpch20")));
  }

  @Test
  public void testExtract() throws Exception {
    setSpace();
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderbaz.datasetbuzz");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"tpch/supplier.parquet\"");
    DatasetUI dataset = getHttpClient().getDatasetApi().getDataset(datasetPath);

    doc("get extract cards recommendation");
    Selection selection = new Selection("s_name", "tic tac toe", 4, 3);
    ExtractCards card =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/extract"))
                .buildPost(entity(selection, JSON)),
            ExtractCards.class);

    List<ExtractCard> cards = card.getCards();
    assertFalse(cards.toString(), cards.isEmpty());
    for (ExtractCard c : cards) {
      DatasetsUtil.accept(
          c.getRule(),
          new ExtractRuleVisitor<Void>() {
            @Override
            public Void visit(ExtractRulePattern extractRulePattern) throws Exception {
              assertNotNull(extractRulePattern.getPattern());
              assertTrue(extractRulePattern.getIndex() > 0);
              assertNotNull(extractRulePattern.getIndexType());
              return null;
            }

            @Override
            public Void visit(ExtractRulePosition position) throws Exception {
              assertNotNull(position);
              return null;
            }
          });

      doc("preview extract cards");
      @SuppressWarnings("deprecation") // checking backward compatibility
      ExtractCard card2 =
          expectSuccess(
              getBuilder(
                      getHttpClient()
                          .getAPIv2()
                          .path(versionedResourcePath(dataset) + "/extract_preview"))
                  .buildPost(entity(new ExtractPreviewReq(selection, c.getRule()), JSON)),
              ExtractCard.class);
      assertEquals(c, card2);
    }
    ExtractRule rule =
        DatasetsUtil.position(
            new Offset(1, Direction.FROM_THE_START), new Offset(3, Direction.FROM_THE_END));
    {
      doc("transform extract keep original col");
      DatasetUI dataset2 =
          getHttpClient()
              .getDatasetApi()
              .transform(dataset, new TransformExtract("s_name", "extracted", rule, false))
              .getDataset();
      assertThat(dataset2.getSql().toLowerCase())
          .contains(
              "s_name, case when length(substr(s_name, 2, length(s_name) - 4)) > 0 then substr(s_name, 2, length(s_name) - 4) else null end as extracted");
    }
    {
      doc("transform extract drop original col");
      DatasetUI dataset3 =
          getHttpClient()
              .getDatasetApi()
              .transform(dataset, new TransformExtract("s_name", "extracted", rule, true))
              .getDataset();
      assertThat(dataset3.getSql().toLowerCase())
          .contains(
              "s_suppkey, case when length(substr(s_name, 2, length(s_name) - 4)) > 0 then substr(s_name, 2, length(s_name) - 4) else null end as extracted");
    }
    ExtractRule rule2 = DatasetsUtil.pattern("\\d+", 2, IndexType.INDEX);
    {
      doc("transform extract with pattern");
      DatasetUI dataset4 =
          getHttpClient()
              .getDatasetApi()
              .transform(dataset, new TransformExtract("s_name", "extracted", rule2, true))
              .getDataset();
      assertThat(dataset4.getSql().toLowerCase())
          .contains("extract_pattern(s_name, \'\\d+\', 2, \'index\') as extracted");
    }
  }

  @Test
  public void testReplaceFlow() throws Exception {
    setSpace();
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderbaz.replace");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"json/replace_example.json\"");
    DatasetUI dataset = getHttpClient().getDatasetApi().getDataset(datasetPath);

    doc("get replace cards recommendation");
    Selection selection = new Selection("address", "tic tac toe", 4, 3);
    ReplaceCards card =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/replace"))
                .buildPost(entity(selection, JSON)),
            ReplaceCards.class);
    List<Card<ReplacePatternRule>> cards = card.getCards();
    assertFalse(cards.toString(), cards.isEmpty());
    assertEquals(2, cards.size());
    List<String> actual = new ArrayList<>();
    for (Card<ReplacePatternRule> c : cards) {
      actual.add(c.getDescription());
    }
    ReplaceValuesCard valuesCard = card.getValues();
    List<HistogramValue> values = valuesCard.getAvailableValues();
    validateHgValue(null, 1, values);
    validateHgValue("4840 E Indian School Rd Ste 101 Phoenix, AZ 85018", 1, values);
    validateHgValue("202 McClure St Dravosburg, PA 15034", 1, values);
    validateHgValue("1530 Hamilton Rd Bethel Park, PA 15234", 1, values);
    validateHgValue("301 S Hills Vlg Pittsburg, PA 15241", 1, values);

    assertEquals(asList("Contains tac", "Contains tac ignore case"), actual);

    ReplacePatternRule rule =
        new ReplacePatternRule(STARTS_WITH).setIgnoreCase(false).setSelectionPattern("foo");
    {
      doc("show replace card after edit");
      @SuppressWarnings("deprecation") // checking backward compatibility
      ReplacePatternCard card2 =
          expectSuccess(
              getBuilder(
                      getHttpClient()
                          .getAPIv2()
                          .path(versionedResourcePath(dataset) + "/replace_preview"))
                  .buildPost(entity(new ReplacePreviewReq(selection, rule), JSON)),
              ReplacePatternCard.class);
      assertEquals("Starts with foo", card2.getDescription());
    }
    FieldReplacePattern replace =
        new FieldReplacePattern(rule, SELECTION).setReplacementValue("bar");
    {
      doc("transform replace preview");
      TransformBase transform = new TransformField("address", "foo", true, replace.wrap());
      InitialPendingTransformResponse preview =
          getHttpClient().getDatasetApi().transformPeek(dataset, transform);

      assertEquals(asList("foo"), preview.getHighlightedColumns());
      assertEquals(asList("address"), preview.getDeletedColumns());
    }
    {
      doc("transform replace");
      DatasetUI dataset2 =
          getHttpClient()
              .getDatasetApi()
              .transform(dataset, new TransformField("address", "foo", false, replace.wrap()))
              .getDataset();
      assertThat(dataset2.getSql().toLowerCase())
          .contains(
              "case when regexp_like(address, '^\\qfoo\\e.*?') then regexp_replace(address, '^\\qfoo\\e', 'bar') else address end as foo"
                  .toLowerCase());
    }
    {
      doc("show replace values card after edit");
      ReplaceValuesPreviewReq replaceReqBody =
          new ReplaceValuesPreviewReq(
              selection,
              Arrays.asList(
                  "1530 Hamilton Rd Bethel Park, PA 15234",
                  "301 S Hills Vlg Pittsburg, PA 15241",
                  null),
              false);
      ReplaceValuesCard card2 =
          expectSuccess(
              getBuilder(
                      getHttpClient()
                          .getAPIv2()
                          .path(versionedResourcePath(dataset) + "/replace_values_preview"))
                  .buildPost(entity(replaceReqBody, JSON)),
              ReplaceValuesCard.class);
      assertEquals(3, card2.getMatchedValues());
    }
  }

  // helper method to check the given value with count exists in list of histogram values
  private void validateHgValue(String value, int count, List<HistogramValue> hgValues) {
    boolean found = false;
    for (HistogramValue hgValue : hgValues) {

      if ((value == null ? hgValue.getValue() == null : value.equals(hgValue.getValue()))
          && count == hgValue.getCount()) {
        found = true;
        break;
      }
    }
    assertTrue("Value " + value + " not found in " + hgValues.toString(), found);
  }

  @Test
  public void testReplaceFlowUsers() throws Exception {
    // user is a special sql keyword
    setSpace();
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderbaz.replaceUsers");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"json/users.json\"");
    DatasetUI dataset = getHttpClient().getDatasetApi().getDataset(datasetPath);

    doc("get replace cards recommendation");
    Selection selection = new Selection("user", "abc", 1, 1);
    ReplaceCards card =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/replace"))
                .buildPost(entity(selection, JSON)),
            ReplaceCards.class);
    List<Card<ReplacePatternRule>> cards = card.getCards();
    List<String> actual = new ArrayList<>();
    for (Card<ReplacePatternRule> c : cards) {
      actual.add(c.getDescription());
    }
    assertEquals(asList("Contains b", "Contains b ignore case"), actual);

    ReplacePatternRule rule =
        new ReplacePatternRule(STARTS_WITH).setIgnoreCase(false).setSelectionPattern("a");

    FieldReplacePattern replace =
        new FieldReplacePattern(rule, SELECTION).setReplacementValue("bar");
    {
      doc("transform replace");
      DatasetUI dataset2 =
          getHttpClient()
              .getDatasetApi()
              .transform(dataset, new TransformField("user", "foo", false, replace.wrap()))
              .getDataset();
      assertThat(dataset2.getSql().toLowerCase())
          .contains(
              "case when regexp_like(\"json/users.json\".\"user\", '^\\qa\\e.*?') then regexp_replace(\"json/users.json\".\"user\", '^\\qa\\e', 'bar') else \"json/users.json\".\"user\" end as foo"
                  .toLowerCase());
    }
  }

  @Test
  public void testDropCol() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("drop", "cp.\"tpch/supplier.parquet\"");
    DatasetUI transformedDataset =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformDrop("s_name"))
            .getDataset();
    assertThat(transformedDataset.getSql().toLowerCase()).doesNotContain("s_name");
  }

  @Test
  public void testRenameCol() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("rename", "cp.\"tpch/supplier.parquet\"");
    DatasetUI transformedDataset =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformRename("s_name", "foo2"))
            .getDataset();
    assertThat(transformedDataset.getSql().toLowerCase()).contains("s_name as foo2");
  }

  @Test
  public void testConvertCase() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("convertCase", "cp.\"tpch/supplier.parquet\"");
    DatasetUI transformedDataset =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformConvertCase("s_name", UPPER_CASE, "foo", true))
            .getDataset();
    assertThat(transformedDataset.getSql().toLowerCase()).contains("upper(s_name) as foo");
  }

  @Test
  public void testTrim() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("trim", "cp.\"tpch/supplier.parquet\"");
    DatasetUI transformedDataset =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformTrim("s_name", BOTH, "foo", true))
            .getDataset();
    assertThat(transformedDataset.getSql().toLowerCase())
        .contains("trim(both ' ' from s_name) as foo");
  }

  @Test
  public void testBadTransform() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("bad", "cp.\"tpch/supplier.parquet\"");
    ValidationErrorMessage result =
        expectError(
            CLIENT_ERROR,
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/transformAndPreview"))
                .buildPost(entity(new TransformDrop(null), JSON)),
            ValidationErrorMessage.class);
    assertEquals(
        asList("must not be blank"),
        result.getValidationErrorMessages().getFieldErrorMessages().get("droppedColumnName"));
  }

  @Test
  public void testBadSelection() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("badSelection", "cp.\"json/extract_list.json\"");
    Selection selection = new Selection(null, null, -1, -1);
    ValidationErrorMessage err =
        expectError(
            CLIENT_ERROR,
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/split"))
                .buildPost(entity(selection, JSON)),
            ValidationErrorMessage.class);
    assertEquals(
        asList("must not be null"),
        err.getValidationErrorMessages().getFieldErrorMessages().get("colName"));
    assertEquals(
        asList("must be greater than or equal to 0"),
        err.getValidationErrorMessages().getFieldErrorMessages().get("offset"));
    assertEquals(
        asList("must be greater than or equal to 0"),
        err.getValidationErrorMessages().getFieldErrorMessages().get("length"));
    assertEquals(
        err.getValidationErrorMessages().getFieldErrorMessages().toString(),
        3,
        err.getValidationErrorMessages().getFieldErrorMessages().size());
  }

  @Test
  public void testCalculatedField() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("calculatedField", "cp.\"tpch/supplier.parquet\"");
    DatasetUI transformedDataset =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformAddCalculatedField("baz", "s_address", "1", false))
            .getDataset();

    assertThat(transformedDataset.getSql().toLowerCase()).contains(" 1 as baz");
  }

  @Test
  public void testDateToText() throws Exception {
    testConvert(
        "to_char(l_commitdate, 'MM.DD.YYYY') as foo",
        new FieldConvertDateToText("MM.DD.YYYY"),
        "l_commitdate",
        "cp.\"tpch/lineitem.parquet\"");
  }

  @Test
  public void testTextToDateTimeDefault() throws Exception {
    JobDataFragment data =
        testConvert(
            "to_timestamp(l_commitdate, 'YYYY-MM-DD') as foo",
            new FieldConvertTextToDate("YYYY-MM-DD"),
            "l_commitdate",
            "cp.\"tpch/lineitem.parquet\"");
    assertEquals(DATETIME, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToDateTime() throws Exception {
    JobDataFragment data =
        testConvert(
            "to_timestamp(l_commitdate, 'YYYY-MM-DD') as foo",
            new FieldConvertTextToDate("YYYY-MM-DD").setDesiredType(DATETIME),
            "l_commitdate",
            "cp.\"tpch/lineitem.parquet\"");
    assertEquals(DATETIME, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToDate() throws Exception {
    JobDataFragment data =
        testConvert(
            "to_date(l_commitdate, 'YYYY-MM-DD') as foo",
            new FieldConvertTextToDate("YYYY-MM-DD").setDesiredType(DATE),
            "l_commitdate",
            "cp.\"tpch/lineitem.parquet\"");
    assertEquals(DATE, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToTime() throws Exception {
    JobDataFragment data =
        testConvert(
            "to_time(l_commitdate, 'YYYY-MM-DD') as foo",
            new FieldConvertTextToDate("YYYY-MM-DD").setDesiredType(TIME),
            "l_commitdate",
            "cp.\"tpch/lineitem.parquet\"");
    assertEquals(TIME, data.getColumn("foo").getType());
  }

  @Test
  public void testBinaryToTime() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("convertBinaryToTime", "cp.\"tpch/lineitem.parquet\"");

    InitialPreviewResponse previewResponse2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformField(
                    "l_commitdate", "foo", true, new FieldSimpleConvertToType(BINARY).wrap()));

    InitialPreviewResponse previewResponse3 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                previewResponse2.getDataset(),
                new TransformField(
                    "foo",
                    "foo2",
                    true,
                    new FieldConvertTextToDate("YYYY-MM-DD").setDesiredType(TIME).wrap()));

    assertThat(previewResponse3.getDataset().getSql().toLowerCase())
        .contains("to_time(convert_to(l_commitdate ,'utf8'), 'yyyy-mm-dd') as foo2");
    JobDataFragment data = getHttpClient().getDatasetApi().getJobData(previewResponse3, 0, 5);

    assertTrue(data.toString(), data.getColumns().size() > 0);
    assertTrue(data.toString(), data.getReturnedRowCount() > 0);
    assertEquals(TIME, data.getColumn("foo2").getType());
  }

  @Test
  public void testCase() throws Exception {
    testConvert(
        "lower(s_name) as foo",
        new FieldConvertCase(LOWER_CASE),
        "s_name",
        "cp.\"tpch/supplier.parquet\"");
  }

  @Test
  public void testFloatToInteger() throws Exception {
    testConvert(
        "cast(ceiling(s_acctbal) as integer) as foo",
        new FieldConvertFloatToInteger(CEILING),
        "s_acctbal",
        "cp.\"tpch/supplier.parquet\"");
  }

  @Test
  public void testFloatToDecimal() throws Exception {
    FieldTransformationBase t = new FieldConvertFloatToDecimal(1);
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(
                "convert" + t.getClass().getSimpleName(), "cp.\"tpch/supplier.parquet\"");
    expectError(
        CLIENT_ERROR,
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(versionedResourcePath(dataset))
                    .queryParam("newVersion", newVersion()))
            .buildPost(entity(new TransformField("s_acctbal", "foo", true, t.wrap()), JSON)),
        ValidationErrorMessage.class);
  }

  @Test
  public void testListToJSON() throws Exception {
    JobDataFragment data =
        testConvert(
            "cast(convert_from(convert_to(s_name, 'json'), 'UTF8') as varchar) as foo",
            new FieldConvertToJSON(),
            "s_name",
            "cp.\"tpch/supplier.parquet\"");
    assertEquals(TEXT, data.getColumn("foo").getType());
  }

  @Ignore("DX-5829")
  @Test
  public void testJSONToMap() throws Exception {
    JobDataFragment data =
        testConvert(
            "convert_from(a, 'json') as foo",
            new FieldConvertFromJSON(),
            "a",
            "cp.\"json/json.json\"");
    assertEquals(STRUCT, data.getColumn("foo").getType());
  }

  @Test
  public void testListToText() throws Exception {
    JobDataFragment data =
        testConvert(
            "list_to_delimited_string(s_name, ',') as foo",
            new FieldConvertListToText(","),
            "s_name",
            "cp.\"tpch/supplier.parquet\"");
    assertEquals(TEXT, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToBinary() throws Exception {
    JobDataFragment data =
        testConvert(
            "convert_to(s_name ,'utf8') as foo",
            new FieldSimpleConvertToType(BINARY),
            "s_name",
            "cp.\"tpch/supplier.parquet\"");
    assertEquals(BINARY, data.getColumn("foo").getType());
  }

  @Test
  public void testFloatToText() throws Exception {
    JobDataFragment data =
        testConvert(
            "cast(s_acctbal as varchar(2048)) as foo",
            new FieldSimpleConvertToType(TEXT),
            "s_acctbal",
            "cp.\"tpch/supplier.parquet\"");
    assertEquals(TEXT, data.getColumn("foo").getType());
  }

  @Test
  public void testIntToFloat() throws Exception {
    JobDataFragment data =
        testConvert(
            "cast(b as double) as foo",
            new FieldSimpleConvertToType(FLOAT),
            "b",
            "cp.\"json/strings.json\"");
    assertEquals(FLOAT, data.getColumn("foo").getType());
  }

  @Test
  public void testDateToTimestamp() throws Exception {
    JobDataFragment data =
        testConvert(
            "cast(l_commitdate as timestamp) as foo",
            new FieldSimpleConvertToType(DATETIME),
            "l_commitdate",
            "cp.\"tpch/lineitem.parquet\"");
    assertEquals(DATETIME, data.getColumn("foo").getType());
  }

  @Test
  public void testTimestampToTime() throws Exception {
    JobDataFragment data =
        testConvert(
            "cast(cast(l_commitdate as timestamp) as time) as foo",
            new FieldSimpleConvertToType(TIME),
            "l_commitdate",
            "cp.\"tpch/lineitem.parquet\"");
    assertEquals(TIME, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToInt() throws Exception {
    JobDataFragment data =
        testConvert(
            "convert_to_integer(a, 1, 1, 0) as foo",
            new FieldConvertToTypeIfPossible(INTEGER, REPLACE_WITH_NULL),
            "a",
            "cp.\"json/numbers.json\"");
    assertEquals(INTEGER, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToFloat() throws Exception {
    JobDataFragment data =
        testConvert(
            "convert_to_float(c, 1, 1, 0) as foo",
            new FieldConvertToTypeIfPossible(FLOAT, REPLACE_WITH_NULL),
            "c",
            "cp.\"json/numbers.json\"");
    assertEquals(FLOAT, data.getColumn("foo").getType());
  }

  @Test
  public void testTextToIntRemoveInvalid() throws Exception {
    JobDataFragment data =
        testConvert(
            "convert_to_integer(d, 1, 0, 0) as foo\nfrom cp.\"json/numbers.json\"\n where is_convertible_data(d, 1, 'integer')",
            new FieldConvertToTypeIfPossible(INTEGER, DELETE_RECORDS),
            "d",
            "cp.\"json/numbers.json\"");
    assertEquals(INTEGER, data.getColumn("foo").getType());
    validateData(data, "foo", "1", "2");
  }

  @Test
  public void testTextToFloatRemoveInvalid() throws Exception {
    JobDataFragment data =
        testConvert(
            "convert_to_float(d, 1, 0, 0) as foo\nfrom cp.\"json/numbers.json\"\n where is_convertible_data(d, 1, 'float')",
            new FieldConvertToTypeIfPossible(FLOAT, DELETE_RECORDS),
            "d",
            "cp.\"json/numbers.json\"");
    assertEquals(FLOAT, data.getColumn("foo").getType());
    validateData(data, "foo", "0.1", "1.0", "0.2", "2.0", "1.0E-113");
  }

  @Test
  public void testExtract2() throws Exception {
    testConvert(
        "case when length(substr(s_name, 2, length(s_name) - 4)) > 0 then substr(s_name, 2, length(s_name) - 4) else null end as foo",
        new FieldExtract(position(new Offset(1, FROM_THE_START), new Offset(3, FROM_THE_END))),
        "s_name",
        "cp.\"tpch/supplier.parquet\"");
  }

  @Test
  public void testExtractMapReco() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("extract_map", "cp.\"json/extract_map.json\"");
    doc("get extract cards recommendation");
    MapSelection selection = new MapSelection("a", asList("Tuesday"));
    Cards<ExtractMapRule> card =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/extract_map"))
                .buildPost(entity(selection, JSON)),
            new GenericType<Cards<ExtractMapRule>>() {});
    List<Card<ExtractMapRule>> cards = card.getCards();
    assertFalse(cards.toString(), cards.isEmpty());
    assertEquals(cards.toString(), 1, cards.size());
    assertEquals("extract from map Tuesday", cards.get(0).getDescription());

    Card<ExtractMapRule> cardPreview =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/extract_struct_preview"))
                .buildPost(
                    entity(
                        new PreviewReq<>(
                            new MapSelection("a", asList("Monday")),
                            new ExtractMapRule("Monday.close")),
                        JSON)),
            new GenericType<Card<ExtractMapRule>>() {});

    final String cardPreviewString = cardPreview.toString();
    assertEquals(cardPreviewString, "extract from map Monday.close", cardPreview.getDescription());
    assertEquals(cardPreviewString, 2, cardPreview.getMatchedCount());
    assertEquals(cardPreviewString, 1, cardPreview.getUnmatchedCount());
  }

  @Test
  public void testExtractMap() throws Exception {
    doc("extract map");
    JobDataFragment data =
        testConvert(
            "\"json/extract_map.json\".a.Tuesday as foo",
            new FieldExtractMap(new ExtractMapRule("Tuesday")),
            "a",
            "cp.\"json/extract_map.json\"");
    assertEquals(data.toString(), 3, data.getReturnedRowCount());
    for (int i = 0; i < data.getReturnedRowCount(); i++) {
      assertEquals("{\"close\":\"19:00\",\"open\":\"10:00\"}", data.extractString("foo", i));
    }
  }

  @Test
  public void testExtractMapNested() throws Exception {
    doc("extract map nested");
    JobDataFragment data =
        testConvert(
            "\"json/extract_map.json\".a.Tuesday.\"close\" as foo",
            new FieldExtractMap(new ExtractMapRule("Tuesday.close")),
            "a",
            "cp.\"json/extract_map.json\"");
    Column c = data.getColumn("foo");
    assertEquals(data.toString(), 3, data.getReturnedRowCount());
    for (int i = 0; i < data.getReturnedRowCount(); i++) {
      assertEquals("19:00", data.extractString(c.getName(), i));
    }
  }

  @Test
  public void testExtractList() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("extract_list", "cp.\"json/extract_list.json\"");

    doc("get replace cards recommendation");
    Selection selection = new Selection("a", "[ \"foo\", \"bar\", \"baz\" ]", 10, 10);
    ExtractListCards card =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/extract_list"))
                .buildPost(entity(selection, JSON)),
            ExtractListCards.class);
    List<ExtractListCard> cards = card.getCards();
    assertFalse(cards.toString(), cards.isEmpty());
    assertEquals(cards.toString(), 4, cards.size());
    List<String> actual = new ArrayList<>();
    for (ExtractListCard c : cards) {
      actual.add(c.getDescription());
    }
    assertEquals(
        asList(
            "Elements: 1 - 2",
            "Elements: 1 - 0 (from the end)",
            "Elements: 1 - 0 (both from the end)",
            "Elements: 1 (from the end) - 2"),
        actual);

    Card<ExtractListRule> cardPreview =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/extract_list_preview"))
                .buildPost(
                    entity(new PreviewReq<>(selection, new ExtractRuleSingle(1).wrap()), JSON)),
            new GenericType<Card<ExtractListRule>>() {});
    assertEquals("Element: 1", cardPreview.getDescription());

    testConvert(
        "a[1] as foo",
        new FieldExtractList(new ExtractListRule(single).setSingle(new ExtractRuleSingle(1))),
        "a",
        "cp.\"json/extract_list.json\"");

    testConvert(
        "sublist(a, 2, 3) as foo",
        new FieldExtractList(
            new ExtractListRule(multiple)
                .setMultiple(
                    new ExtractRuleMultiple(
                        new ListSelection(
                            new Offset(1, FROM_THE_START), new Offset(3, FROM_THE_START))))),
        "a",
        "cp.\"json/extract_list.json\"");
  }

  private void validateData(JobDataFragment data, String colName, String... values) {
    Column col = checkNotNull(data.getColumn(colName), data.getColumns());
    List<String> actual = new ArrayList<>();
    for (int i = 0; i < data.getReturnedRowCount(); i++) {
      actual.add(data.extractString(col.getName(), i));
    }
    List<String> expected = Arrays.asList(values);
    assertEquals(colName, expected, actual);
  }

  @Test
  public void testReplace() throws Exception {
    testConvert(
        "case when regexp_like(address, '^\\q10\\e.*?') then regexp_replace(address, '^\\q10\\e', 'bar') else address end as foo",
        new FieldReplacePattern(
                new ReplacePatternRule(STARTS_WITH).setIgnoreCase(false).setSelectionPattern("10"),
                SELECTION)
            .setReplacementValue("bar"),
        "address",
        "cp.\"json/replace_example.json\"");
  }

  @Test
  public void testReplaceValues() throws Exception {
    JobDataFragment data =
        testConvert(
            "case\n  when a = 'a' then 'bar'\n  when a = 'efa' then 'bar'\n  else a\nend as foo",
            new FieldReplaceValue(false)
                .setReplacedValuesList(asList("a", "efa"))
                .setReplacementValue("bar")
                .setReplacementType(TEXT),
            "a",
            "cp.\"json/strings.json\"");
    //  { "a": "abc", "b": 0}
    //  { "a": "Ab", "b": 1}
    //  { "a": "a", "b": 2}
    //  { "a": "defabc", "b": 3}
    //  { "a": "deab", "b": 4}
    //  { "a": "efa", "b": 5}
    //  { "b": 6}
    validateData(data, "foo", "abc", "Ab", "bar", "defabc", "deab", "bar", null);
  }

  @Test
  public void testReplaceCustom() throws Exception {
    JobDataFragment data =
        testConvert(
            "case\n  when a = 'a' or a = 'efa' then 'bar'\n  else a\nend as foo",
            new FieldReplaceCustom("a = 'a' or a = 'efa'")
                .setReplacementValue("bar")
                .setReplacementType(TEXT),
            "a",
            "cp.\"json/strings.json\"");
    validateData(data, "foo", "abc", "Ab", "bar", "defabc", "deab", "bar", null);
  }

  @Test
  public void testReplaceCustomInteger() throws Exception {
    JobDataFragment data =
        testConvert(
            "case\n  when b = 1 then 123\n  else b\nend as foo",
            new FieldReplaceCustom("b = 1").setReplacementValue("123").setReplacementType(INTEGER),
            "b",
            "cp.\"json/strings.json\"");
    validateData(data, "foo", "0", "123", "2", "3", "4", "5", "6");
  }

  @Test
  public void testReplaceIntValues() throws Exception {
    JobDataFragment data =
        testConvert(
            "case\n  when b = 1 then 125\n  else b\nend as foo",
            new FieldReplaceValue(false)
                .setReplacedValuesList(Arrays.asList("1"))
                .setReplacementValue("125")
                .setReplacementType(INTEGER),
            "b",
            "cp.\"json/strings.json\"");
    validateData(data, "foo", "0", "125", "2", "3", "4", "5", "6");
  }

  @Test
  public void testReplaceIntRange() throws Exception {
    JobDataFragment data =
        testConvert(
            "case\n  when 1 < b AND 4 > b then 125\n  else b\nend as foo",
            new FieldReplaceRange(false)
                .setLowerBound("1")
                .setUpperBound("4")
                .setReplacementValue("125")
                .setReplacementType(INTEGER),
            "b",
            "cp.\"json/strings.json\"");
    validateData(data, "foo", "0", "1", "125", "125", "4", "5", "6");
  }

  @Test
  public void testReplaceDateRange() throws Exception {
    try (AutoCloseable ignored =
        withSystemOption(ExecConstants.PARQUET_AUTO_CORRECT_DATES_VALIDATOR, true)) {
      JobDataFragment data =
          testConvert(
              "case\n  when TIMESTAMP '1992-02-19 00:00:00.000' < l_shipdate AND TIMESTAMP '1998-10-29 00:00:00.000' > l_shipdate then TIMESTAMP '2014-03-26 08:24:33.000'\n  else l_shipdate\nend as foo",
              new FieldReplaceRange(false)
                  .setLowerBound("1992-02-19 00:00:00.000")
                  .setUpperBound("1998-10-29 00:00:00.000")
                  .setReplacementValue("2014-03-26 08:24:33.000")
                  .setReplacementType(DATETIME),
              "l_shipdate",
              "cp.\"tpch/lineitem.parquet\"");

      Column col = checkNotNull(data.getColumn("foo"), data.getColumns());
      Set<String> actual = new TreeSet<>();
      for (int i = 0; i < data.getReturnedRowCount(); i++) {
        actual.add(data.extractString(col.getName(), i).substring(0, 4));
      }
      assertEquals(new TreeSet<>(Arrays.asList("1992", "1998", "2014")), actual);
    }
  }

  @Test
  public void testSplit() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("split", "cp.\"json/split.json\"");
    doc("get split cards recommendation");
    Selection selection =
        new Selection("a", "Shopping, Home Services, Internet Service Providers, Mobi", 8, 1);
    Cards<SplitRule> cards =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/split"))
                .buildPost(entity(selection, JSON)),
            new GenericType<Cards<SplitRule>>() {});
    List<Card<SplitRule>> cardsList = cards.getCards();
    assertFalse(cardsList.toString(), cardsList.isEmpty());
    assertEquals(cardsList.toString(), 1, cardsList.size());
    List<String> actual = new ArrayList<>();
    for (Card<SplitRule> c : cardsList) {
      actual.add(c.getDescription());
    }
    assertEquals(asList("Exactly matches \",\""), actual);

    Card<SplitRule> card =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/split_preview"))
                .buildPost(
                    entity(new PreviewReq<>(selection, new SplitRule(", ", exact, false)), JSON)),
            new GenericType<Card<SplitRule>>() {});
    assertEquals(card.toString(), "Exactly matches \", \"", card.getDescription());

    testConvert(
        "regexp_split(a, '\\q,\\e', 'first', -1) as foo",
        new FieldSplit(new SplitRule(",", exact, false), FIRST),
        "a",
        "cp.\"json/split.json\"");
    testConvert(
        "regexp_split(a, '\\q, \\e', 'first', -1) as foo",
        new FieldSplit(new SplitRule(", ", exact, false), FIRST),
        "a",
        "cp.\"json/split.json\"");
    testConvert(
        "regexp_split(a, ',.', 'all', 10) as foo",
        new FieldSplit(new SplitRule(",.", regex, false), ALL).setMaxFields(10),
        "a",
        "cp.\"json/split.json\"");
    testConvert(
        "regexp_split(a, ',.', 'index', 0) as foo",
        new FieldSplit(new SplitRule(",.", regex, false), INDEX).setIndex(0),
        "a",
        "cp.\"json/split.json\"");
  }

  @Test
  public void testUnnestList() throws Exception {
    JobDataFragment data =
        testConvert("flatten(b) as foo", new FieldUnnestList(), "b", "cp.\"json/nested.json\"");
    assertEquals(6, data.getReturnedRowCount());
  }

  @Test
  public void testUnnestGroupByList() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("unnestAndGroupBy", "cp.\"json/nested.json\"");

    InitialPreviewResponse response2 =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformField("b", "b", true, new FieldUnnestList().wrap()));

    InitialPreviewResponse response3 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                response2.getDataset(),
                new TransformGroupBy()
                    .setColumnsDimensionsList(asList(new Dimension("b")))
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("b"))));

    JobDataFragment data = getHttpClient().getDatasetApi().getJobData(response3, 0, 20);
    assertEquals(6, data.getReturnedRowCount());
  }

  @Test
  public void testPreviewTransform() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("datasetbuzz", "cp.\"tpch/supplier.parquet\"");
    TransformBase transform =
        new TransformConvertCase("s_name", ConvertCase.LOWER_CASE, "foo2", true);
    InitialPendingTransformResponse preview =
        getHttpClient().getDatasetApi().transformPeek(dataset, transform);
    assertThat(preview.getSql().toLowerCase()).contains("lower(s_name) as foo");
    Column foo2 = preview.getData().getColumn("foo2");
    Column s_name = preview.getData().getColumn("s_name");
    assertNotNull("cols: " + preview.getData().getColumns(), foo2);
    assertEquals(asList("foo2"), preview.getHighlightedColumns());
    assertNotNull("cols: " + preview.getData().getColumns(), s_name);
    assertEquals(asList("s_name"), preview.getDeletedColumns());
  }

  @Test
  public void testPreviewTransformSameName() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("same", "cp.\"tpch/supplier.parquet\"");
    TransformBase transform =
        new TransformConvertCase("s_name", ConvertCase.LOWER_CASE, "s_name", true);
    InitialPendingTransformResponse preview =
        getHttpClient().getDatasetApi().transformPeek(dataset, transform);
    assertThat(preview.getSql().toLowerCase())
        .contains("s_name, lower(s_name) as \"s_name (new)\"");
    Column s_name = preview.getData().getColumn("s_name");
    Column s_name_new = preview.getData().getColumn("s_name (new)");
    assertNotNull("cols: " + preview.getData().getColumns(), s_name);
    assertEquals(asList("s_name"), preview.getDeletedColumns());
    assertNotNull("cols: " + preview.getData().getColumns(), s_name_new);
    assertEquals(asList("s_name (new)"), preview.getHighlightedColumns());
  }

  private JobDataFragment testConvert(
      String expected, FieldTransformationBase t, String field, String parent) throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("convert" + t.getClass().getSimpleName(), parent);

    InitialPreviewResponse previewResponse =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformField(field, "foo", true, t.wrap()));

    assertThat(previewResponse.getDataset().getSql().toLowerCase())
        .contains(expected.toLowerCase());

    JobDataFragment data = getHttpClient().getDatasetApi().getJobData(previewResponse, 0, 2000);
    assertTrue(data.toString(), data.getColumns().size() > 0);
    assertTrue(data.toString(), data.getReturnedRowCount() > 0);

    return data;
  }

  @Test
  public void testSaveDataset() throws Exception {
    setSpace();

    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderbaz.datasetbuzz");
    doc("creating dataset");
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(datasetPath, "cp.\"tpch/supplier.parquet\"");

    doc("verifying ds");
    final DatasetUI dsGet = getHttpClient().getDatasetApi().getDataset(datasetPath);

    assertEquals(getName(dataset), getName(dsGet));
    assertEquals(dataset.getSql(), dsGet.getSql());

    doc("sort dataset by s_suppkey");
    // transform and save
    DatasetUI dsTransform =
        getHttpClient()
            .getDatasetApi()
            .transform(dsGet, new TransformSort("s_suppkey", ASC))
            .getDataset();

    assertThat(dsTransform.getSql().toLowerCase()).contains("order by s_suppkey asc");

    DatasetUI saved = getHttpClient().getDatasetApi().save(dsGet, dsGet.getVersion()).getDataset();
    getHttpClient().getDatasetApi().saveExpectConflict(dsGet, dsGet.getVersion());
    {
      // a failure to save should not impact the last saved version
      final DatasetUI dsGet1 = getHttpClient().getDatasetApi().getDataset(datasetPath);
      assertEquals(dsGet1.getVersion(), saved.getVersion());
    }

    doc("creating dataset space1.ds1");
    DatasetPath datasetPath2 = new DatasetPath("space1.ds1");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath2, "cp.\"tpch/supplier.parquet\"");
    final DatasetUI dsGet1 = getHttpClient().getDatasetApi().getDataset(datasetPath);
    assertEquals(dsGet1.getVersion(), saved.getVersion());
    DatasetUI dsPut1 = dsGet1;

    doc("sort multiple: s_name asc, s_suppkey desc");
    // transform another time to verify result
    DatasetUI dsTransform2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dsGet1,
                new TransformSorts()
                    .setColumnsList(asList(new Order("s_name", ASC), new Order("s_suppkey", DESC))))
            .getDataset();

    assertThat(dsTransform2.getSql().toLowerCase()).contains("order by s_name asc, s_suppkey desc");

    // copy from dataset
    doc("copying existing dataset");
    final DatasetUI dsCopied =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path("dataset/space1.ds1-copied/copyFrom/space1.ds1"))
                .buildPut(Entity.json(new VirtualDatasetUI())),
            DatasetUI.class);

    assertEquals(dsPut1.getSql(), dsCopied.getSql());

    doc("verifying copied dataset");
    final DatasetUI dsGetCopied =
        getHttpClient().getDatasetApi().getDataset(dsCopied.toDatasetPath());
    assertEquals(dsPut1.getSql(), dsGetCopied.getSql());
    assertEquals(dsPut1.getContext(), dsGetCopied.getContext());
    assertNotEquals(dsPut1.getFullPath(), dsGetCopied.getFullPath());

    doc("delete dataset");
    // Issue get again.
    final DatasetUI dsDeleted = getHttpClient().getDatasetApi().delete(dsGet1);
    assertEquals(dsGet1.getSql(), dsDeleted.getSql());
    assertEquals(dsGet1.getContext(), dsDeleted.getContext());
    assertEquals(dsGet1.getFullPath(), dsDeleted.getFullPath());

    expectStatus(
        Status.NOT_FOUND,
        getHttpClient().getDatasetApi().getDatasetInvocation(dsGet1.toDatasetPath()));
  }

  @Test
  public void testSaveDatasetWrongFolder() throws Exception {
    setSpace();

    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    DatasetPath datasetPath = new DatasetPath("spacefoo.folderbar.folderblahz.datasetbuzz");
    InitialPreviewResponse preview =
        getHttpClient().getDatasetApi().createDatasetFromParent("cp.\"tpch/supplier.parquet\"");

    expectStatus(
        Status.BAD_REQUEST,
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(versionedResourcePath(preview.getDataset()) + "/save")
                    .queryParam("as", datasetPath))
            .buildPost(entity("", JSON)));
  }

  @Test
  public void testVirtualDatasetWithNotNullFields() {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    final String pathName = "space1.v1";
    final DatasetPath numbersJsonPath = new DatasetPath(pathName);
    List<String> context = ImmutableList.of("cp");
    DatasetUI numbersJsonVD =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQLAndSave(
                numbersJsonPath,
                "select row_number() over (order by a) as rnk, a from cp.\"json/numbers.json\"",
                context);
    final SqlQuery query =
        getQueryFromSQL(
            String.format(
                "select t1.rnk, t1.a from %s t1 join %s t2 on t1.rnk = t2.rnk+1",
                pathName, pathName));
    submitJobAndWaitUntilCompletion(JobRequest.newBuilder().setSqlQuery(query).build());
  }

  @Test
  public void testVirtualDatasetWithTimestampDiff() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    final String pathName = "space1.v1";
    final DatasetPath datetimePath = new DatasetPath(pathName);
    List<String> context = ImmutableList.of("cp");
    DatasetUI dateTimeVD =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQLAndSave(
                datetimePath,
                "select timestampdiff(SECOND, datetime1, datetime2) as tsdiff from cp.\"json/datetime.json\"",
                context);
    final SqlQuery query = getQueryFromSQL(String.format("select * from %s", pathName));
    submitJobAndWaitUntilCompletion(JobRequest.newBuilder().setSqlQuery(query).build());
  }

  @Test
  public void testVirtualDatasetWithChar() {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    final String pathName = "space1.v1";
    final DatasetPath numbersJsonPath = new DatasetPath(pathName);
    List<String> context = ImmutableList.of("cp");
    DatasetUI numbersJsonVD =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQLAndSave(
                numbersJsonPath,
                "select CASE WHEN a > 2 THEN 'less than 2' ELSE 'greater than 2' END as twoInfo, a from cp.\"json/numbers.json\"",
                context);
    final SqlQuery query = getQueryFromSQL(String.format("select * from %s", pathName));
    submitJobAndWaitUntilCompletion(JobRequest.newBuilder().setSqlQuery(query).build());

    final SqlQuery query2 =
        getQueryFromSQL(String.format("select count(*) from %s where a > 2", pathName));
    submitJobAndWaitUntilCompletion(JobRequest.newBuilder().setSqlQuery(query2).build());
  }

  @Test
  public void testReapplyDataset() {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space2", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    DatasetPath d1Path = new DatasetPath("space1.ds1");
    DatasetPath d2Path = new DatasetPath("space1.ds2");
    DatasetUI d1 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQLAndSave(
                d1Path, "select s_name, s_phone from cp.\"tpch/supplier.parquet\"", asList("cp"));
    DatasetUI d2 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQLAndSave(
                d2Path, "select s_name, s_phone from cp.\"tpch/supplier.parquet\"", asList("cp"));

    doc("creating untitled from existing dataset");
    String parentDataset = d1Path.toNamespaceKey().toString();
    final DatasetUI d3 =
        getHttpClient().getDatasetApi().createDatasetFromParent(parentDataset).getDataset();

    doc("verifying untitled ds");
    DatasetVersionResourcePath datasetVersionPath1 = d3.toDatasetVersionPath();
    final DatasetUI d4 = getHttpClient().getDatasetApi().getVersionedDataset(datasetVersionPath1);

    doc("applying transform to untitled ds");
    final DatasetUI d5 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                d4,
                new TransformField(
                    "s_phone", "s_phone_json", false, new FieldConvertToJSON().wrap()))
            .getDataset();
    assertThat(d5.getSql().toLowerCase())
        .contains(
            "cast(convert_from(convert_to(s_phone, 'json'), 'utf8') as varchar) as s_phone_json");

    doc("verifying transformed ds");
    DatasetVersionResourcePath datasetVersionPath = d5.toDatasetVersionPath();
    final DatasetUI d6 = getHttpClient().getDatasetApi().getVersionedDataset(datasetVersionPath);
    assertEquals(d6.getFullPath().get(0), "tmp");

    doc("reapplying transform from untitled to ds1");
    DatasetVersionResourcePath versionResourcePath = d6.toDatasetVersionPath();
    InitialPreviewResponse reapplyResult =
        getHttpClient().getDatasetApi().reapply(versionResourcePath);
    final DatasetUI d7 = reapplyResult.getDataset();
    assertEquals(Arrays.asList("space1", "ds1"), d7.getFullPath());
    assertEquals(
        reapplyResult.getHistory().getItems().size(),
        1); // should be only data set creation. Transformation should be ignored
    HistoryItem firstHistoryItem = reapplyResult.getHistory().getItems().get(0);
    assertEquals(firstHistoryItem.getDataset(), d1Path);
    assertEquals(firstHistoryItem.getDatasetVersion(), d1.getDatasetVersion());

    doc("should fail to edit original sql for dataset that is created from sql");
    DatasetVersionResourcePath versionResourcePath1 = d2.toDatasetVersionPath();
    expectError(
        CLIENT_ERROR,
        getHttpClient().getDatasetApi().reapplyInvocation(versionResourcePath1),
        UserExceptionMapper.ErrorMessageWithContext.class);
  }

  @Test
  public void testReapplyAndSave() {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(
                Entity.json(
                    new com.dremio.dac.api.Space(null, "reapplyAndSave", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    DatasetPath d1Path = new DatasetPath("reapplyAndSave.ds1");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            d1Path, "select s_name, s_phone from cp.\"tpch/supplier.parquet\"", asList("cp"));
    final DatasetUI d2 =
        getHttpClient().getDatasetApi().createDatasetFromParent("reapplyAndSave.ds1").getDataset();

    DatasetVersionResourcePath newVersion = d2.toDatasetVersionPath();
    DatasetUIWithHistory reapply =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(newVersion.toString() + "/reapplyAndSave")
                        .queryParam("as", "reapplyAndSave.ds3"))
                .buildPost(null),
            DatasetUIWithHistory.class);
    assertThat(reapply.getDataset().getSql()).contains("supplier.parquet");
    List<HistoryItem> historyItems = reapply.getHistory().getItems();
    assertEquals("Number of history items incorrect", 1, historyItems.size());
    assertEquals(
        historyItems.get(0).getVersionedResourcePath().getVersion(),
        reapply.getDataset().getDatasetVersion());
    assertEquals(
        historyItems.get(0).getDataset(), new DatasetPath(reapply.getDataset().getFullPath()));
  }

  @Test
  public void testReapplyAndSaveWrongFolder() {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(
                Entity.json(
                    new com.dremio.dac.api.Space(null, "reapplyAndSave", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    DatasetPath d1Path = new DatasetPath("reapplyAndSave.ds1");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            d1Path, "select s_name, s_phone from cp.\"tpch/supplier.parquet\"", asList("cp"));
    final DatasetUI d2 =
        getHttpClient().getDatasetApi().createDatasetFromParent("reapplyAndSave.ds1").getDataset();

    DatasetVersionResourcePath newVersion = d2.toDatasetVersionPath();
    expectStatus(
        Status.BAD_REQUEST,
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(newVersion.toString() + "/reapplyAndSave")
                    .queryParam("as", "reapplyAndSaveNotFound.ds3"))
            .buildPost(null));
  }

  @Test
  public void testRenameDataset() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space2", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    doc("creating dataset");
    DatasetPath datasetPath = new DatasetPath("space1.ds1");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"tpch/supplier.parquet\"");

    doc("verifying ds");
    DatasetUI dsGet = getHttpClient().getDatasetApi().getDataset(datasetPath);

    doc("renaming dataset");
    // test rename dataset
    final DatasetUI dsPutRenamed = getHttpClient().getDatasetApi().rename(datasetPath, "ds1r");
    assertEquals("ds1r", getName(dsPutRenamed));
    assertEquals(dsGet.getSql(), dsPutRenamed.getSql());
    assertEquals(dsGet.getDatasetVersion(), dsPutRenamed.getDatasetVersion());

    DatasetPath renamedDatasetPath = dsPutRenamed.toDatasetPath();

    doc("verifying renamed ds");
    dsGet = getHttpClient().getDatasetApi().getDataset(renamedDatasetPath);

    doc("getting old ds before rename - should fail");
    expectError(
        CLIENT_ERROR,
        getHttpClient().getDatasetApi().getDatasetInvocation(datasetPath),
        NotFoundErrorMessage.class);

    doc("move dataset");
    final DatasetPath newDest = new DatasetPath("space2.ds1m");
    final DatasetUI dsPutMoved = getHttpClient().getDatasetApi().move(renamedDatasetPath, newDest);

    assertEquals("ds1m", getName(dsPutMoved));
    assertEquals(dsGet.getSql(), dsPutMoved.getSql());
    assertEquals(dsGet.getDatasetVersion(), dsPutMoved.getDatasetVersion());

    doc("verifying moved ds");
    dsGet = getHttpClient().getDatasetApi().getDataset(newDest);

    doc("getting old ds before move - should fail");
    expectError(
        CLIENT_ERROR,
        getHttpClient().getDatasetApi().getDatasetInvocation(renamedDatasetPath),
        NotFoundErrorMessage.class);
  }

  @Test
  public void testGroupBy() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformGroupBy()
                    .setColumnsDimensionsList(asList(new Dimension("a")))
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("b"))))
            .getDataset();

    assertThat(dataset2.getSql().toLowerCase()).contains("group by a");
    assertThat(dataset2.getSql().toLowerCase()).contains("a, count(b) as count_b");
  }

  @Test
  public void testGroupByWithSortBy() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI datasetSort =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformSorts()
                    .setColumnsList(
                        asList(
                            new Order("a", OrderDirection.ASC),
                            new Order("c", OrderDirection.DESC))))
            .getDataset();
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                datasetSort,
                new TransformGroupBy()
                    .setColumnsDimensionsList(asList(new Dimension("a")))
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("b"))))
            .getDataset();

    assertThat(dataset2.getSql().toLowerCase()).contains("group by a");
    assertThat(dataset2.getSql().toLowerCase()).contains("a, count(b) as count_b");
    assertThat(dataset2.getSql().toLowerCase()).doesNotContain("order by c");
    assertThat(dataset2.getSql().toLowerCase()).contains("order by a asc");
  }

  @Test
  public void testGroupByWithSortByNoGroupBy() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI datasetSort =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformSorts()
                    .setColumnsList(
                        asList(
                            new Order("a", OrderDirection.ASC),
                            new Order("c", OrderDirection.DESC))))
            .getDataset();
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                datasetSort,
                new TransformGroupBy()
                    .setColumnsDimensionsList(null)
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("b"))))
            .getDataset();

    assertThat(dataset2.getSql().toLowerCase()).doesNotContain("group by a");
    assertThat(dataset2.getSql().toLowerCase()).doesNotContain("a, count(b) as count_b");
    assertThat(dataset2.getSql().toLowerCase()).contains("count(b) as count_b");
    assertThat(dataset2.getSql().toLowerCase()).doesNotContain("order by c desc");
    assertThat(dataset2.getSql().toLowerCase()).doesNotContain("order by a asc");
  }

  @Test
  public void testGroupByDimension() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformGroupBy().setColumnsDimensionsList(asList(new Dimension("a"))))
            .getDataset();

    assertThat(dataset2.getSql().toLowerCase()).contains("group by a");
  }

  @Test
  public void testGroupByMeasure() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformGroupBy()
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("b"))))
            .getDataset();
    assertThat(dataset2.getSql().toLowerCase()).contains("count(b) as count_b");
  }

  @Test
  public void testGroupByTwice() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformGroupBy()
                    .setColumnsDimensionsList(asList(new Dimension("a")))
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("b"))))
            .getDataset();

    DatasetUI dataset3 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset2,
                new TransformGroupBy()
                    .setColumnsDimensionsList(asList(new Dimension("Count_b")))
                    .setColumnsMeasuresList(asList(new Measure(Count).setColumn("a"))))
            .getDataset();

    assertThat(dataset3.getSql().toLowerCase()).contains("group by a");
    assertThat(dataset3.getSql().toLowerCase()).contains("a, count(b) as count_b");
    assertThat(dataset3.getSql().toLowerCase()).contains("group by count_b");
    assertThat(dataset3.getSql().toLowerCase()).contains("count_b, count(a) as count_a");
  }

  @Test
  public void testGroupByCountStar() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/group_by.json\"");
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset,
                new TransformGroupBy()
                    .setColumnsDimensionsList(asList(new Dimension("a")))
                    .setColumnsMeasuresList(asList(new Measure(Count_Star))))
            .getDataset();

    assertThat(dataset2.getSql().toLowerCase()).contains("group by a");
    assertThat(dataset2.getSql().toLowerCase()).contains("a, count(*) as count_star");
  }

  @Test
  public void testDatasets() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space2", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space3", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    expectError(
        CLIENT_ERROR,
        getHttpClient().getDatasetApi().getDatasetInvocation(new DatasetPath("space.myspace")),
        NotFoundErrorMessage.class);

    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            new DatasetPath("space1.ds2"),
            "select s.s_name from cp.\"tpch/supplier.parquet\" s",
            asList("cp"));
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            new DatasetPath("space2.ds1"),
            "select s.s_name from cp.\"tpch/supplier.parquet\" s",
            asList("cp"));
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            new DatasetPath("space2.ds2"),
            "select s.s_name from cp.\"tpch/supplier.parquet\" s",
            asList("cp"));
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            new DatasetPath("space3.ds3"),
            "select s.s_name from cp.\"tpch/supplier.parquet\" s",
            asList("cp"));
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQLAndSave(
            new DatasetPath("space2.ds3"),
            "select s.s_name from cp.\"tpch/supplier.parquet\" s",
            asList("cp"));

    final Space space1 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/space/space1")).buildGet(), Space.class);
    assertEquals(1, space1.getContents().getDatasets().size());

    final Space space2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/space/space2")).buildGet(), Space.class);
    assertEquals(3, space2.getContents().getDatasets().size());

    final Space space3 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/space/space3")).buildGet(), Space.class);
    assertEquals(1, space3.getContents().getDatasets().size());
  }

  @Test
  public void testCreateDatasets() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(
                Entity.json(
                    new com.dremio.dac.api.Space(null, "spaceCreateDataset", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    DatasetPath datasetPath = new DatasetPath("spaceCreateDataset.ds1");
    DatasetUI ds1 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQLAndSave(
                datasetPath, "select s.s_name from cp.\"tpch/supplier.parquet\" s", asList("cp"));
    DatasetConfig dataset = l(NamespaceService.class).getDataset(datasetPath.toNamespaceKey());
    assertEquals(ds1.getVersion(), dataset.getTag());

    getHttpClient().getDatasetApi().getDataset(ds1.toDatasetPath());

    String parentDataset = ds1.toDatasetPath().toPathString();
    DatasetUI ds2 =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(
                new DatasetPath("spaceCreateDataset.ds3"), parentDataset);
    getHttpClient().getDatasetApi().getDataset(ds2.toDatasetPath());
  }

  @Test
  public void canReapplyIsCorrect() {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(
                Entity.json(
                    new com.dremio.dac.api.Space(null, "canReapplyDataset", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    InitialPreviewResponse createFromPhysical =
        getHttpClient().getDatasetApi().createDatasetFromParent("cp.\"tpch/supplier.parquet\"");
    List<String> displayFullPath = createFromPhysical.getDataset().getDisplayFullPath();
    // datasets directly derived from other datasets should have their display path set to their
    // parent
    assertEquals("cp", displayFullPath.get(0));
    assertEquals("tpch/supplier.parquet", displayFullPath.get(1));
    assertEquals(
        "Dataset derived directly from a physical dataset shouldn't be reappliable.",
        false,
        createFromPhysical.getDataset().canReapply());

    InitialRunResponse runResponse =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(
                            new DatasetVersionResourcePath(
                                        new DatasetPath(
                                            createFromPhysical.getDataset().getFullPath()),
                                        createFromPhysical.getDataset().getDatasetVersion())
                                    .toString()
                                + "/run"))
                .buildGet(),
            InitialRunResponse.class);

    displayFullPath = runResponse.getDataset().getDisplayFullPath();
    assertEquals("cp", displayFullPath.get(0));
    assertEquals("tpch/supplier.parquet", displayFullPath.get(1));

    DatasetUI savedFromPhysical =
        getHttpClient()
            .getDatasetApi()
            .saveAs(createFromPhysical.getDataset(), new DatasetPath("canReapplyDataset.myDataset"))
            .getDataset();
    assertEquals(
        "Dataset that is saved should not be reappliable.", false, savedFromPhysical.canReapply());

    InitialPreviewResponse transformedPhysical =
        getHttpClient()
            .getDatasetApi()
            .transform(savedFromPhysical, new TransformSort("s_name", OrderDirection.ASC));
    assertEquals(
        "A dataset transformed from a !reappliable dataset should also not be reappliable.",
        false,
        transformedPhysical.getDataset().canReapply());

    InitialPreviewResponse createFromVirtual =
        getHttpClient().getDatasetApi().createDatasetFromParent("canReapplyDataset.myDataset");
    assertEquals(
        "Expected a new dataset based off a virtual dataset should be able to be reapplied.",
        true,
        createFromVirtual.getDataset().canReapply());

    InitialPreviewResponse transformedVirtual1 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                createFromVirtual.getDataset(), new TransformSort("s_name", OrderDirection.ASC));
    assertEquals(
        "Unsaved transformation derived from virtual dataset should be reappliable.",
        true,
        transformedVirtual1.getDataset().canReapply());

    DatasetUI savedFromVirtual =
        getHttpClient()
            .getDatasetApi()
            .saveAs(
                transformedVirtual1.getDataset(), new DatasetPath("canReapplyDataset.myDataset2"))
            .getDataset();
    assertEquals(
        "Saved transformation derived from virtual dataset should not be reappliable.",
        false,
        savedFromVirtual.canReapply());

    InitialPreviewResponse transformedVirtual2 =
        getHttpClient()
            .getDatasetApi()
            .transform(savedFromVirtual, new TransformSort("s_name", OrderDirection.ASC));
    assertEquals(
        "Unsaved transformation derived from virtual dataset that has name should not be reappliable (since it is named).",
        false,
        transformedVirtual2.getDataset().canReapply());
  }

  @Test
  public void testQueryExternal() throws Exception {

    // simple query
    JobDataFragment data =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("sql"))
                .buildPost(
                    entity(
                        new CreateFromSQL(
                            "select * from cp.\"tpch/supplier.parquet\"", asList("cp")),
                        JSON)),
            JobDataFragment.class);
    assertTrue(data.toString(), data.getColumns().size() > 0);
    assertTrue(data.toString(), data.getReturnedRowCount() > 0);

    // ds query
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("querytest", "cp.\"tpch/supplier.parquet\"");
    String sql = "select * from " + dataset.toDatasetPath().toPathString();
    JobDataFragment data2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("sql"))
                .buildPost(entity(new CreateFromSQL(sql, asList("cp")), JSON)),
            JobDataFragment.class);
    assertTrue(data2.toString(), data2.getColumns().size() > 0);
    assertTrue(data2.toString(), data2.getReturnedRowCount() > 0);
  }

  @Test
  public void testRecommendedJoins() throws Exception {
    String a = "cp.\"json/join/a.json\"";
    String b = "cp.\"json/join/b.json\"";
    String c = "cp.\"json/join/c.json\"";
    DatasetUI dataset =
        getHttpClient().getDatasetApi().createDatasetFromParentAndSave("join_reco", a);
    JoinRecommendations recommendations;
    DatasetUI ds = getHttpClient().getDatasetApi().getDataset(dataset.toDatasetPath());

    doc("Get data for join_reco so that there's a job for it");
    final InitialPreviewResponse previewResponse = getHttpClient().getDatasetApi().getPreview(ds);
    waitForJobComplete(previewResponse.getJobId().getId());

    doc("Get dataset join recommendations join_reco");

    recommendations =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(ds) + "/join_recs"))
                .buildGet(),
            JoinRecommendations.class);
    assertEquals(0, recommendations.getRecommendations().size());

    DatasetUI djointest =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave(
                new DatasetPath(getRoot(dataset) + ".djointest"),
                dataset.toDatasetPath().toString());
    final InitialPreviewResponse previewResponseA =
        getHttpClient().getDatasetApi().getPreview(djointest);
    waitForJobComplete(previewResponseA.getJobId().getId());

    DatasetUI sibling = getHttpClient().getDatasetApi().getDataset(djointest.toDatasetPath());

    // JOIN ... ON ... joins
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("/sql"))
            .buildPost(
                json(
                    new CreateFromSQL(
                        "SELECT * FROM " + a + " A INNER JOIN " + b + " B ON A.a = B.b", null))),
        JobDataFragment.class);

    // WHERE based joins
    expectSuccess(
        getBuilder(getHttpClient().getAPIv2().path("/sql"))
            .buildPost(
                json(
                    new CreateFromSQL(
                        "SELECT * FROM " + a + " A, " + c + " C WHERE A.a = C.c", null))),
        JobDataFragment.class);

    recommendations =
        expectSuccess(
            getBuilder(
                    getHttpClient().getAPIv2().path(versionedResourcePath(sibling) + "/join_recs"))
                .buildGet(),
            JoinRecommendations.class);

    // TODO DX-101113: Join Recommendations are disabled, expect none
    assertEquals(0, recommendations.getRecommendations().size());
    // JoinRecommendation r0 = recommendations.getRecommendations().get(0);
    // JoinRecommendation r1 = recommendations.getRecommendations().get(1);
    // assertEquals(asList("cp", "json/join/c.json"), r0.getRightTableFullPathList());
    // assertEquals(asList("cp", "json/join/b.json"), r1.getRightTableFullPathList());
  }

  @Test
  public void testConvertDateToNumber() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("dateToNumber", "cp.\"json/datetime.json\"");

    TransformBase t1 =
        new TransformField()
            .setSourceColumnName("datetime1")
            .setNewColumnName("a1")
            .setDropSourceColumn(true)
            .setFieldTransformation(
                new FieldConvertDateToNumber(NumberToDateFormat.JULIAN, DATETIME, INTEGER).wrap());

    DatasetUI datasetDate = getHttpClient().getDatasetApi().transform(dataset, t1).getDataset();
    assertThat(datasetDate.getSql().toLowerCase())
        .contains(
            "cast(ceil(unix_timestamp(datetime1, 'yyyy-mm-dd hh24:mi:ss.fff') / 86400 + 2440587.5) as integer)");

    DatasetUI datasetExcel =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("datasetExcel", "cp.\"json/datetime.json\"");
    TransformBase t2 =
        new TransformField()
            .setSourceColumnName("date1")
            .setNewColumnName("a1")
            .setDropSourceColumn(true)
            .setFieldTransformation(
                new FieldConvertDateToNumber(NumberToDateFormat.EXCEL, DATE, INTEGER).wrap());

    DatasetUI datasetDate2 =
        getHttpClient().getDatasetApi().transform(datasetExcel, t2).getDataset();
    assertThat(datasetDate2.getSql().toLowerCase())
        .contains("cast(ceil(unix_timestamp(date1, 'yyyy-mm-dd') / 86400 + 25569) as integer)");
  }

  @Test
  public void testJoin() throws Exception {

    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("tbl1", "cp.\"json/numbers.json\"");

    TransformJoin join = new TransformJoin();
    join.setJoinType(JoinType.Inner);
    join.setJoinConditionsList(Arrays.asList(new JoinCondition("b", "b")));
    join.setRightTableFullPathList(dataset.getFullPath());
    InitialPendingTransformResponse resp =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/transformPeek")
                        .queryParam("newVersion", "123456"))
                .buildPost(entity(join, JSON)),
            InitialPendingTransformResponse.class);
  }

  @Ignore("Fails with Missing function implementation: [to_time(FLOAT8-OPTIONAL)]")
  @Test
  public void testConvertNumberToDate() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("groupBy", "cp.\"json/numbers.json\"");
    TransformBase t1 =
        new TransformField()
            .setSourceColumnName("a")
            .setNewColumnName("a1")
            .setDropSourceColumn(true)
            .setFieldTransformation(
                new FieldConvertNumberToDate()
                    .setDesiredType(DATE)
                    .setFormat(NumberToDateFormat.EXCEL)
                    .wrap());

    DatasetUI datasetDate = getHttpClient().getDatasetApi().transform(dataset, t1).getDataset();

    assertThat(datasetDate.getSql().toLowerCase()).contains("to_date");
    assertThat(datasetDate.getSql().toLowerCase()).contains(" - 25569) * 86400");

    TransformBase t2 =
        new TransformField()
            .setSourceColumnName("a")
            .setNewColumnName("a1")
            .setDropSourceColumn(true)
            .setFieldTransformation(
                new FieldConvertNumberToDate()
                    .setDesiredType(TIME)
                    .setFormat(NumberToDateFormat.JULIAN)
                    .wrap());

    DatasetUI datasetTime = getHttpClient().getDatasetApi().transform(dataset, t2).getDataset();

    assertThat(datasetTime.getSql().toLowerCase()).contains("to_time");
    assertThat(datasetTime.getSql().toLowerCase()).contains(" - 2440587.5) * 86400");

    TransformBase t3 =
        new TransformField()
            .setSourceColumnName("a")
            .setNewColumnName("a1")
            .setDropSourceColumn(true)
            .setFieldTransformation(
                new FieldConvertNumberToDate()
                    .setDesiredType(DATETIME)
                    .setFormat(NumberToDateFormat.EPOCH)
                    .wrap());

    DatasetUI datasetTimestamp =
        getHttpClient().getDatasetApi().transform(dataset, t3).getDataset();
    assertThat(datasetTimestamp.getSql().toLowerCase()).contains("to_timestamp");
  }

  @Test
  public void testConvertCalculatedField() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("calculatedField", "cp.\"tpch/supplier.parquet\"");
    DatasetUI dataset2 =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformAddCalculatedField("baz", "s_address", "1", true))
            .getDataset();

    assertTrue(
        Pattern.compile(
                "select s_suppkey, s_name, 1 as baz, s_nationkey, s_phone, s_acctbal, s_comment\\s*\\n\\s*from")
            .matcher(dataset2.getSql().toLowerCase())
            .find());
    // To address a variety of failures based on performing several transforms in a row, we added
    // nesting in the case of all calculated fields. We do not currently parse the expression from
    // the
    // user to find out which columns are all referenced, so they may require nesting. This
    // previously excluded
    // string now appears in the subquery, but the include above has been updated to check for all
    // expected columns
    // in the outer select list, which does exclude s_address.
    // assertNotContains("s_address", dataset2.getSql().toLowerCase());

    DatasetUI dataset3 =
        getHttpClient()
            .getDatasetApi()
            .transform(
                dataset2,
                new TransformField()
                    .setFieldTransformation(new FieldSimpleConvertToType(TEXT).wrap())
                    .setSourceColumnName("baz")
                    .setNewColumnName("baz2")
                    .setDropSourceColumn(true))
            .getDataset();

    assertThat(dataset3.getSql().toLowerCase()).contains("as varchar");

    DatasetUI dataset4 =
        getHttpClient()
            .getDatasetApi()
            .transform(dataset, new TransformAddCalculatedField("baz", "s_address", "2", false))
            .getDataset();

    assertThat(dataset4.getSql().toLowerCase()).contains(" 2 as baz");
    assertThat(dataset4.getSql().toLowerCase()).contains("s_address, 2 as baz");
  }

  @Test
  public void testNodeActivity() {
    @SuppressWarnings("unchecked")
    List<Map<?, ?>> nodesRaw =
        (List<Map<?, ?>>)
            expectSuccess(
                getBuilder(getHttpClient().getAPIv2().path("/system/nodes")).buildGet(),
                List.class);
    // expectSuccess deserializes to List<LinkedHashMap> so we need to convert it explicitly
    List<NodeInfo> nodes = JSONUtil.mapper().convertValue(nodesRaw, new TypeReference<>() {});
    if (isMultinode()) {
      assertEquals(3, nodes.size());
      final NodeInfo master = nodes.get(0);
      assertTrue(master.getIsMaster() && master.getIsCoordinator() && !master.getIsExecutor());
      final NodeInfo coord = nodes.get(1);
      assertTrue(!coord.getIsMaster() && coord.getIsCoordinator() && !coord.getIsExecutor());
      final NodeInfo exec = nodes.get(2);
      assertTrue(!exec.getIsMaster() && !exec.getIsCoordinator() && exec.getIsExecutor());
    } else {
      assertEquals("green", nodes.get(0).getStatus());
      assertEquals(1, nodes.size());
      final NodeInfo masterExec = nodes.get(0);
      assertTrue(
          masterExec.getIsMaster() && masterExec.getIsCoordinator() && masterExec.getIsExecutor());
    }
    assertTrue(
        nodes.stream().allMatch(node -> node.getVersion().equals(DremioVersionInfo.getVersion())));
  }

  @Test
  public void testMemoryActivity() {
    SystemResource.ResourceInfo resourceInfo =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path("/system/cluster-resource-info")).buildGet(),
            SystemResource.ResourceInfo.class);
    assertNotNull(resourceInfo);
  }

  @Test
  public void testNewQueryContext() {
    String sql = "select * from \"tpch/supplier.parquet\"";
    List<String> context = asList("cp");

    InitialPreviewResponse datasetResponse =
        getHttpClient().getDatasetApi().createDatasetFromSQL(sql, context);

    assertEquals("UNTITLED", getName(datasetResponse.getDataset()));

    JobDataFragment data = getHttpClient().getDatasetApi().getJobData(datasetResponse, 0, 200);
    String dataString = JSONUtil.toString(data);
    assertEquals(dataString, 7, data.getColumns().size());
    assertEquals(dataString, "s_suppkey", data.getColumns().get(0).getName());
    assertEquals(dataString, DataType.INTEGER, data.getColumns().get(0).getType());
    assertEquals(dataString, 0, data.getColumns().get(0).getIndex());
    assertEquals(dataString, 100, data.getReturnedRowCount());
    assertNotNull(dataString, data.extractString(data.getColumns().get(0).getName(), 0));
  }

  @Test
  public void testNewQuerySemantics() {
    String sql = "select * from \"tpch/supplier.parquet\"";
    List<String> context = asList("cp");

    InitialPreviewResponse datasetResponse =
        getHttpClient().getDatasetApi().createDatasetFromSQL(sql, context);

    // any transform will do
    InitialPreviewResponse result =
        getHttpClient()
            .getDatasetApi()
            .transform(datasetResponse.getDataset(), new TransformDrop("s_phone"));

    // should not get wrapped
    String newSql = result.getDataset().getSql();
    // no subquery => no parenthesis
    assertFalse(newSql, newSql.contains("(") || newSql.contains(")"));
  }

  @Test
  public void testEditDatasetContext() throws Exception {
    List<String> context = asList("cp");
    // create dataset
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("testds", "cp.\"tpch/supplier.parquet\"");

    // make some transform
    TransformBase tb =
        new TransformUpdateSQL("select * from \"tpch/supplier.parquet\"")
            .setSqlContextList(context);

    InitialPreviewResponse dataset2Response =
        getHttpClient().getDatasetApi().transform(dataset, tb);

    JobDataFragment data = getHttpClient().getDatasetApi().getJobData(dataset2Response, 0, 200);

    String dataString = JSONUtil.toString(data);
    assertEquals(dataString, 7, data.getColumns().size());
    assertEquals(dataString, "s_suppkey", data.getColumns().get(0).getName());
    assertEquals(dataString, DataType.INTEGER, data.getColumns().get(0).getType());
    assertEquals(dataString, 0, data.getColumns().get(0).getIndex());
    assertEquals(dataString, 100, data.getReturnedRowCount());
    assertNotNull(dataString, data.extractString(data.getColumns().get(0).getName(), 0));
  }

  @Test
  public void testEditSQL() throws Exception {
    List<String> context = asList("cp");
    // create dataset
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("testds", "cp.\"tpch/supplier.parquet\"");

    // make some transform
    TransformBase tb =
        new TransformUpdateSQL("select count(*) from \"tpch/supplier.parquet\"")
            .setSqlContextList(context);

    InitialPreviewResponse dataset2Response =
        getHttpClient().getDatasetApi().transform(dataset, tb);

    assertEquals(
        "select count(*) from \"tpch/supplier.parquet\"", dataset2Response.getDataset().getSql());
    JobDataFragment data = getHttpClient().getDatasetApi().getJobData(dataset2Response, 0, 200);

    String dataString = JSONUtil.toString(data);
    assertEquals(dataString, 1, data.getColumns().size());
    assertEquals(dataString, "EXPR$0", data.getColumns().get(0).getName());
    assertEquals(dataString, DataType.INTEGER, data.getColumns().get(0).getType());
    assertEquals(dataString, 0, data.getColumns().get(0).getIndex());
    assertEquals(dataString, 1, data.getReturnedRowCount());
    assertNotNull(dataString, data.extractString(data.getColumns().get(0).getName(), 0));
  }

  @Test
  public void testGetKeeponlyExcludeCards() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("testds", "cp.\"tpch/supplier.parquet\"");

    Selection selection = new Selection("s_phone", "27-918-335-1736", 3, 3);

    doc("get keeponly cards recommendation");
    ReplaceCards card1 =
        expectSuccess(
            getBuilder(
                    getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/keeponly"))
                .buildPost(entity(selection, JSON)),
            ReplaceCards.class);
    List<Card<ReplacePatternRule>> cards = card1.getCards();
    assertFalse(cards.toString(), cards.isEmpty());
    assertEquals(cards.toString(), 2, cards.size());
    List<String> actual = new ArrayList<>();
    for (Card<ReplacePatternRule> c : cards) {
      actual.add(c.getDescription());
    }
    assertEquals(asList("Matches regex \\d+", "Contains 918"), actual);

    doc("get exclude cards recommendation");
    ReplaceCards card2 =
        expectSuccess(
            getBuilder(getHttpClient().getAPIv2().path(versionedResourcePath(dataset) + "/exclude"))
                .buildPost(entity(selection, JSON)),
            ReplaceCards.class);
    cards = card2.getCards();
    assertFalse(cards.toString(), cards.isEmpty());
    assertEquals(cards.toString(), 2, cards.size());
    actual.clear();
    for (Card<ReplacePatternRule> c : cards) {
      actual.add(c.getDescription());
    }
    assertEquals(asList("Matches regex \\d+", "Contains 918"), actual);
  }

  @Test
  public void testCardGenOnEmptyTable() throws Exception {
    List<String> context = Lists.newArrayList("cp");
    InitialPreviewResponse previewDataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromSQL("select * from sys.version where commit_id = ''", context);

    Selection sel = new Selection("commit_id", "unused in test", 0, 3);

    expectSuccess(
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(versionedResourcePath(previewDataset.getDataset()) + "/keeponly"))
            .buildPost(entity(sel, JSON)),
        new GenericType<ReplaceCards>() {});
  }

  @Test // DX-3964
  public void testReplaceValuesCard() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("testds", "cp.\"json/numbers.json\"");

    Selection sel = new Selection("c", "0.1", 0, 3);

    helperTestReplaceValuesCard(dataset, "replace", sel);
    helperTestReplaceValuesCard(dataset, "keeponly", sel);
    helperTestReplaceValuesCard(dataset, "exclude", sel);

    sel = new Selection("c", null, 0, 0);
    helperTestReplaceValuesCard(dataset, "replace", sel);
    helperTestReplaceValuesCard(dataset, "keeponly", sel);
    helperTestReplaceValuesCard(dataset, "exclude", sel);
  }

  private void helperTestReplaceValuesCard(DatasetUI datasetUI, String function, Selection sel) {
    doc(String.format("get %s card preview", function));
    ReplaceCards cards =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(datasetUI) + "/" + function))
                .buildPost(entity(sel, JSON)),
            new GenericType<ReplaceCards>() {});
    assertEquals(1, cards.getValues().getMatchedValues());
    assertEquals(6, cards.getValues().getUnmatchedValues());
    assertEquals(7, cards.getValues().getAvailableValuesCount());
  }

  @Test
  public void testGetKeeponlyExcludeCard() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("testds", "cp.\"tpch/supplier.parquet\"");

    PreviewReq<ReplacePatternRule, Selection> req =
        new PreviewReq<>(
            new Selection("s_phone", "27-918-335-1736", 3, 3),
            new ReplacePatternRule(CONTAINS).setIgnoreCase(false).setSelectionPattern("918"));

    doc("get keeponly card preview");
    Card<ReplacePatternRule> card1 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/keeponly_preview"))
                .buildPost(entity(req, JSON)),
            new GenericType<Card<ReplacePatternRule>>() {});
    assertEquals(card1.getMatchedCount(), 1);
    assertEquals(card1.getDescription(), "Contains 918");

    doc("get exclude card preview");
    Card<ReplacePatternRule> card2 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/exclude_preview"))
                .buildPost(entity(req, JSON)),
            new GenericType<Card<ReplacePatternRule>>() {});
    assertEquals(card2.getMatchedCount(), 1);
    assertEquals(card2.getDescription(), "Contains 918");
  }

  @Test
  public void testGetKeeponlyExcludeValuesCard() throws Exception {
    DatasetUI dataset =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParentAndSave("testds", "cp.\"tpch/supplier.parquet\"");

    ReplaceValuesPreviewReq req =
        new ReplaceValuesPreviewReq(
            new Selection("s_phone", "27-918-335-1736", 3, 3),
            Arrays.asList("27-918-335-1736", "20-403-398-8662", "13-715-945-6730"),
            false);

    doc("get keeponly card value preview");
    ReplaceValuesCard card1 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/keeponly_values_preview"))
                .buildPost(entity(req, JSON)),
            ReplaceValuesCard.class);
    assertEquals(card1.getMatchedValues(), 3);
    assertEquals(card1.getAvailableValuesCount(), 100);

    doc("get exclude card value preview");
    ReplaceValuesCard card2 =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path(versionedResourcePath(dataset) + "/exclude_values_preview"))
                .buildPost(entity(req, JSON)),
            ReplaceValuesCard.class);
    assertEquals(card2.getMatchedValues(), 3);
    assertEquals(card2.getAvailableValuesCount(), 100);
  }

  @Test
  public void testCellTruncation() throws Exception {

    // For testing purposes set the value to 30
    String defaultMaxCellLength = System.getProperty(JobData.MAX_CELL_SIZE_KEY);
    System.setProperty(JobData.MAX_CELL_SIZE_KEY, "30");
    try {

      DatasetUI dataset =
          getHttpClient()
              .getDatasetApi()
              .createDatasetFromParentAndSave("cellTrunc", "cp.\"json/cell_truncation.json\"");

      final InitialPreviewResponse previewResponse =
          getHttpClient().getDatasetApi().getPreview(dataset);
      waitForJobComplete(previewResponse.getJobId().getId());
      final DataPOJO data =
          (DataPOJO)
              getHttpClient()
                  .getDatasetApi()
                  .getJobData(previewResponse, 0, INITIAL_RESULTSET_SIZE);

      List<CellPOJO> row0Cells = data.getRows().get(0).getRow();
      fetchAndVerifyFullCellValue(
          row0Cells.get(1).getUrl(),
          "very very very very very very very very very very long long string value 1");

      fetchAndVerifyFullCellValue(
          row0Cells.get(2).getUrl(),
          ImmutableMap.of(
              "innerCol1", 200,
              "innerCol2", 234.432,
              "innerCol3", "inner string col",
              "innerCol4", "2016-04-06",
              "innerCol5", "another string"));

      fetchAndVerifyFullCellValue(
          row0Cells.get(3).getUrl(),
          ImmutableList.of(
              "item 1", "item 2", "item 3", "item 4", "item 1", "item 2", "item 3", "item 4",
              "item 1"));

      List<CellPOJO> row1Cells = data.getRows().get(1).getRow();
      fetchAndVerifyFullCellValue(
          row1Cells.get(1).getUrl(),
          "very very very very very very very very very very long long string value 2");

      fetchAndVerifyFullCellValue(
          row1Cells.get(2).getUrl(),
          ImmutableMap.of(
              "innerCol1", 400,
              "innerCol2", 434.432,
              "innerCol3", "inner string col",
              "innerCol4", "2016-04-06",
              "innerCol5", "another string"));

      fetchAndVerifyFullCellValue(
          row1Cells.get(3).getUrl(),
          ImmutableList.of(
              "item 5", "item 6", "item 7", "item 8", "item 1", "item 2", "item 3", "item 4",
              "item 1"));

      List<CellPOJO> row2Cells = data.getRows().get(2).getRow();
      fetchAndVerifyFullCellValue(
          row2Cells.get(1).getUrl(),
          "very very very very very very very very very very long long string value 3");

      fetchAndVerifyFullCellValue(
          row2Cells.get(2).getUrl(),
          ImmutableMap.of(
              "innerCol1", 600,
              "innerCol2", 634.432,
              "innerCol3", "inner string col",
              "innerCol4", "2016-04-06",
              "innerCol5", "another string"));

      fetchAndVerifyFullCellValue(
          row2Cells.get(3).getUrl(),
          ImmutableList.of(
              "item 9", "item 10", "item 11", "item 12", "item 1", "item 2", "item 3", "item 4",
              "item 1"));

      fetchAndVerifyFullCellValue(
          row2Cells.get(4).getUrl(), "long long long long long long long long long string value");

      // Request additional data for preview and verify that cell fetch urls are still valid
      DataPOJO addtlData =
          (DataPOJO) getHttpClient().getDatasetApi().getJobData(previewResponse, 2, 1);

      List<CellPOJO> addlRow = addtlData.getRows().get(0).getRow();
      fetchAndVerifyFullCellValue(
          addlRow.get(1).getUrl(),
          "very very very very very very very very very very long long string value 3");

      fetchAndVerifyFullCellValue(
          addlRow.get(2).getUrl(),
          ImmutableMap.of(
              "innerCol1", 600,
              "innerCol2", 634.432,
              "innerCol3", "inner string col",
              "innerCol4", "2016-04-06",
              "innerCol5", "another string"));

      fetchAndVerifyFullCellValue(
          addlRow.get(3).getUrl(),
          ImmutableList.of(
              "item 9", "item 10", "item 11", "item 12", "item 1", "item 2", "item 3", "item 4",
              "item 1"));

      fetchAndVerifyFullCellValue(
          addlRow.get(4).getUrl(), "long long long long long long long long long string value");
    } finally {
      if (defaultMaxCellLength != null) {
        System.setProperty(JobData.MAX_CELL_SIZE_KEY, defaultMaxCellLength);
      }
    }
  }

  private void fetchAndVerifyFullCellValue(String url, Object expectedValue) {
    Object result =
        expectSuccess(getBuilder(getHttpClient().getAPIv2().path(url)).buildGet(), Object.class);
    assertEquals(expectedValue, result);
  }

  @Test
  public void previewTableInSubschemaExposedAsTopLevelSchema() throws Exception {
    // Namespace is cleaned up before the test. So make sure to create a home directory for the test
    // user.
    final NamespaceKey homeKey =
        new HomePath(HomeName.getUserHomePath(DEFAULT_USERNAME)).toNamespaceKey();
    final NamespaceService ns = l(NamespaceService.class);
    if (!ns.exists(homeKey, Type.HOME)) {
      final HomeConfig homeConfig = new HomeConfig().setOwner(DEFAULT_USERNAME);
      ns.addOrUpdateHome(homeKey, homeConfig);
    }

    final NASConf nas = new NASConf();
    nas.path = new File("src/test/resources").getAbsolutePath();
    SourceUI source = new SourceUI();
    source.setName("testNAS");
    source.setConfig(nas);
    source.setMetadataPolicy(
        UIMetadataPolicy.of(CatalogService.DEFAULT_METADATA_POLICY_WITH_AUTO_PROMOTE));

    final SourceService sourceService = getSourceService();
    sourceService.registerSourceWithRuntime(source, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);

    InitialDataPreviewResponse resp =
        getHttpClient()
            .getDatasetApi()
            .getPreview(new DatasetPath(asList("testNAS", "datasets", "users.json")));

    // Now try to query the table as "testNAS.datasets"."users.json"
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromSQL("SELECT * FROM \"testNAS.datasets\".\"users.json\"", asList("cp"));
  }

  @Test
  public void testReapplyForCopiedRenamedMovedDataset() throws Exception {
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space1", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});
    expectSuccess(
        getBuilder(getHttpClient().getCatalogApi())
            .buildPost(Entity.json(new com.dremio.dac.api.Space(null, "space2", null, null, null))),
        new GenericType<com.dremio.dac.api.Space>() {});

    // create dataset
    DatasetPath datasetPath = new DatasetPath("space1.ds1");
    getHttpClient()
        .getDatasetApi()
        .createDatasetFromParentAndSave(datasetPath, "cp.\"tpch/supplier.parquet\"");

    // copy existing dataset
    final DatasetUI dsCopied =
        expectSuccess(
            getBuilder(
                    getHttpClient()
                        .getAPIv2()
                        .path("dataset/space1.ds1-copied/copyFrom/space1.ds1"))
                .buildPut(Entity.json(new VirtualDatasetUI())),
            DatasetUI.class);
    final DatasetUI dsGetCopied =
        getHttpClient().getDatasetApi().getDataset(dsCopied.toDatasetPath());

    // rename copied dataset
    final DatasetUI dsPutRenamed =
        getHttpClient().getDatasetApi().rename(dsGetCopied.toDatasetPath(), "ds1-copied-renamed");

    // move copied and renamed dataset
    final DatasetPath newDest = new DatasetPath("space2.ds1-copied-moved");
    final DatasetUI dsPutMoved =
        getHttpClient().getDatasetApi().move(dsPutRenamed.toDatasetPath(), newDest);

    // should success to edit original sql for a dataset that is copied, moved, and renamed
    final DatasetUI testDS =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParent("space2.ds1-copied-moved")
            .getDataset();
    DatasetVersionResourcePath versionResourcePath = testDS.toDatasetVersionPath();
    expectSuccess(getHttpClient().getDatasetApi().reapplyInvocation(versionResourcePath));
  }

  private void testBIEndpoint(String endpoint, Callable<Void> biToolSetupCallback)
      throws Exception {
    setSpace();
    // create dataset so we get a namespace entry for the physical dataset.
    DatasetUI datasetUI =
        getHttpClient()
            .getDatasetApi()
            .createDatasetFromParent("cp.\"tpch/supplier.parquet\"")
            .getDataset();
    final DatasetUI ui =
        getHttpClient()
            .getDatasetApi()
            .saveAs(datasetUI, new DatasetPath("spacefoo.boo"))
            .getDataset();

    if (biToolSetupCallback != null) {
      biToolSetupCallback.call();
    }

    expectSuccess(
        getBuilder(
                getHttpClient()
                    .getAPIv2()
                    .path(endpoint)
                    .path(String.join("/", ui.getDisplayFullPath())))
            .header("Accept", "*/*")
            .header("host", "localhost")
            .buildGet());
  }

  private static String getName(DatasetUI datasetUI) {
    return datasetUI.getFullPath().get(datasetUI.getFullPath().size() - 1);
  }

  private static String getRoot(DatasetUI datasetUI) {
    return datasetUI.getFullPath().get(0);
  }
}
