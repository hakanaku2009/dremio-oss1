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
package com.dremio;

import static com.dremio.TestBuilder.listOf;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.FUNCTION;
import static com.dremio.sabot.Fixtures.ts;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.dremio.common.exceptions.UserRemoteException;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.util.FileUtils;
import com.dremio.common.util.TestTools;
import com.dremio.config.DremioConfig;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.CatalogServiceImpl;
import com.dremio.exec.catalog.SourceUpdateType;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType;
import com.dremio.exec.store.CatalogService;
import com.dremio.sabot.op.join.hash.HashJoinOperator;
import com.dremio.sabot.rpc.user.QueryDataBatch;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.test.TemporarySystemProperties;
import com.dremio.test.UserExceptionAssert;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestExampleQueries extends PlanTestBase {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(TestExampleQueries.class);

  private static final String TEST_RES_PATH = TestTools.getWorkingPath() + "/src/test/resources";

  @Rule public TemporarySystemProperties properties = new TemporarySystemProperties();

  @Before
  public void setupOptions() throws Exception {
    testNoResult(
        "ALTER SESSION SET \"%s\" = true", ExecConstants.ENABLE_VERBOSE_ERRORS.getOptionName());
    properties.set(DremioConfig.LEGACY_STORE_VIEWS_ENABLED, "true");
  }

  @After
  public void resetOptions() throws Exception {
    testNoResult("ALTER SESSION RESET ALL");
  }

  @Test
  public void testDifferentOperatorsWithMatchingSplits() throws Exception {
    test("use dfs_test");
    final String vwCreate =
        "create or replace view \"dfs_test\".voter_csv_v as "
            + "select case when columns[0]='' then cast(null as int) else cast(columns[0] as int) end as voter_id, "
            + "case when columns[1]='' then cast(null as varchar(30)) else cast(columns[1] as varchar(30)) end as name, "
            + "case when columns[2]='' then cast(null as integer) else cast(columns[2] as integer) end as age, "
            + "case when columns[3]='' then cast(null as varchar(20)) else cast(columns[3] as varchar(20)) end as registration, "
            + "case when columns[4]='' then cast(null as double precision) else cast(columns[4] as double precision) end as contributions, "
            + "case when columns[5]='' then cast(null as integer) else cast(columns[5] as integer) end as voterzone, "
            + "case when columns[6]='' then cast(null as timestamp) else cast(columns[6] as timestamp) end as create_time, "
            + "cast(columns[7] as boolean) isVote from \"cp\".\"json/voters.json\"\nt";

    test(vwCreate);
    testBuilder()
        .sqlQuery(
            "SELECT distinct(isVote) FROM dfs_test.voter_csv_v where (registration <> 'independent') ")
        .unOrdered()
        .baselineColumns("isVote")
        .baselineValues(false)
        .baselineValues(true)
        .go();
    // the following query has some splits with exactly same expression as previous splits but the
    // number of splits vary. See DX-45671.
    testBuilder()
        .sqlQuery(
            "SELECT count(*) as cnt FROM dfs_test.voter_csv_v where registration = 'independent'")
        .unOrdered()
        .baselineColumns("cnt")
        .baselineValues(8L)
        .go();
    test("drop view dfs_test.voter_csv_v");
  }

  @Test
  public void testQueryWithConstant() throws Exception {
    final String sql =
        "SELECT count(*) as c1 FROM cp.\"complex/complex_fields.parquet\" as \"x\" where (case "
            + "when x.c_array_array[0][0] > 3 "
            + "then x.c_array_array[0][1] "
            + "else x.c_array_array[0][0] end) = 3;";
    testBuilder().sqlQuery(sql).unOrdered().baselineColumns("c1").baselineValues(14L).go();
  }

  @Test
  public void testLeastFunction() throws Exception {
    final String sql = "select least('2023-05','2023-03')";
    testBuilder()
        .sqlQuery(sql)
        .unOrdered()
        .baselineColumns("EXPR$0")
        .baselineValues("2023-03")
        .go();
  }

  /* Create a temporary folder which has sub-folders/files as below
  2011/Jan/1.csv
  2012/Jan/1.csv
  2013/Jan/1.csv
  */
  private TemporaryFolder CreateFolderStructure() throws Exception {
    final TemporaryFolder folder = new TemporaryFolder();
    folder.create();
    for (String folderName : new String[] {"2011", "2012", "2013"}) {
      File f = folder.newFolder(folderName, "Jan");
      File handle = new File(f, "1.csv");
      PrintWriter out = new PrintWriter(handle);
      out.println("Hello world");
      out.close();
    }
    return folder;
  }

  @Test
  public void testFolderExplorer() throws Exception {
    final TemporaryFolder folder = CreateFolderStructure();
    final String path = folder.getRoot().toPath().toString();
    final String sql =
        "select dir0, dir1 from dfs.\""
            + path
            + "\" "
            + "where dir0 = MAXDIR('dfs','"
            + path
            + "')";

    testBuilder()
        .sqlQuery(sql)
        .unOrdered()
        .baselineColumns("dir0", "dir1")
        .baselineValues("2013", "Jan")
        .go();

    folder.delete();
  }

  @Test
  public void testMaxDirEvaluationInPlanner() throws Exception {
    final TemporaryFolder folder = CreateFolderStructure();
    final String path = folder.getRoot().toPath().toString();
    final String sql =
        "SELECT dir0 FROM"
            + "(SELECT '2013' AS dir0 from (values ((0), (1))))"
            + "WHERE dir0 = MAXDIR('dfs','"
            + path
            + "')";

    testBuilder().sqlQuery(sql).unOrdered().baselineColumns("dir0").baselineValues("2013").go();

    folder.delete();
  }

  @Test
  public void testMaxDirEvaluationAtRuntime() throws Exception {
    final TemporaryFolder folder = CreateFolderStructure();
    final String path = folder.getRoot().toPath().toString();
    final String sql =
        "SELECT dir0 FROM"
            + "(SELECT '2013' AS dir0 from (values ((0), (1))))"
            + "WHERE dir0 = MAXDIR('com.dremio.plugins.azure', '"
            + path
            + "')";

    errorMsgWithTypeTestHelper(
        sql,
        ErrorType.UNSUPPORTED_OPERATION,
        "The partition explorer interface can only be used in functions that can be evaluated at planning time");

    folder.delete();
  }

  @Test
  public void testMaxDirWithNullArgument() throws Exception {
    final String sql =
        "SELECT dir0 FROM"
            + "(SELECT '2013' AS dir0 from (values ((0), (1))))"
            + "WHERE dir0 = MAXDIR(NULL, NULL)";

    errorMsgWithTypeTestHelper(
        sql,
        ErrorType.UNSUPPORTED_OPERATION,
        "The partition explorer interface can only be used in functions that can be evaluated at planning time");
  }

  @Test
  public void testUnionWithDecimalScale() throws Exception {
    final String sql =
        "SELECT *\n" + "FROM\n" + "((select 100.0 as c1)\n" + "UNION\n" + "(select 20.55 as c2));";
    testBuilder()
        .sqlQuery(sql)
        .unOrdered()
        .baselineColumns("c1")
        .baselineValues(new BigDecimal("20.55"))
        .baselineValues(new BigDecimal("100.00"))
        .go();
  }

  @Test
  public void testDivideByZeroUsingIEEE754Semantics() throws Exception {
    final String query =
        ""
            + "SELECT\n"
            + "  CAST (1.0 as double)/0.0 AS inf,\n"
            + "  CAST (-1.0 as double)/0.0 AS ninf,\n"
            + "  CAST (0.0 as double)/0.0 AS nan\n";
    try (AutoCloseable option = withOption(PlannerSettings.IEEE_754_DIVIDE_SEMANTICS, true)) {
      testBuilder()
          .optionSettingQueriesForTestQuery(
              "alter system set \"planner.ieee_754_divide_semantics\" = true")
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("inf", "ninf", "nan")
          .baselineValues(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)
          .go();
    }
  }

  @Test
  public void testQueryWithOnlyOffset() throws Exception {
    final String sql =
        "SELECT columns[0] AS Key, columns[1] AS Country, columns[2] as Capital FROM cp.\"csv/nationsWithCapitals.csv\" OFFSET 4";

    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("Key", "Country", "Capital")
        .baselineValues("5", "France", "Paris")
        .baselineValues("6", "Italy", "Rome")
        .go();
  }

  @Test
  public void testQueryWithOnlyLimit() throws Exception {
    final String sql =
        "SELECT columns[0] AS Key, columns[1] AS Country, columns[2] as Capital FROM cp.\"csv/nationsWithCapitals.csv\" LIMIT 2";

    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("Key", "Country", "Capital")
        .baselineValues("1", "USA", "Washington DC")
        .baselineValues("2", "Singapore", "Singapore")
        .go();
  }

  @Test
  public void testQueryWithOnlyLimitAndOffset() throws Exception {
    final String sql =
        "SELECT columns[0] AS Key, columns[1] AS Country, columns[2] as Capital FROM cp.\"csv/nationsWithCapitals.csv\" LIMIT 3 OFFSET 2";

    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("Key", "Country", "Capital")
        .baselineValues("3", "UK", "London")
        .baselineValues("4", "Germany", "Berlin")
        .baselineValues("5", "France", "Paris")
        .go();
  }

  @Test
  public void testQueryWithOnlyOrderByAndOffset() throws Exception {
    final String sql =
        "SELECT columns[0] AS Key, columns[1] AS Country, columns[2] as Capital FROM cp.\"csv/nationsWithCapitals.csv\" ORDER BY Country OFFSET 2";

    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("Key", "Country", "Capital")
        .baselineValues("6", "Italy", "Rome")
        .baselineValues("2", "Singapore", "Singapore")
        .baselineValues("3", "UK", "London")
        .baselineValues("1", "USA", "Washington DC")
        .go();
  }

  @Test
  public void testCovarPop() throws Exception {
    final String sql =
        "SELECT covar_pop(val, val) as col from cp.\"parquet/dremio_int_max.parquet\"";

    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("col")
        .baselineValues(8.3848836698679782E17)
        .go();
  }

  @Test
  public void testConcat() throws Exception {
    final String sql = "SELECT CONCAT('a', 'bc', 'd') as concol";

    testBuilder().sqlQuery(sql).ordered().baselineColumns("concol").baselineValues("abcd").go();
  }

  @Test
  public void testCovarSamp() throws Exception {
    final String sql =
        "SELECT covar_samp(val, val) as col from cp.\"parquet/dremio_int_max.parquet\"";

    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("col")
        .baselineValues(9.2233720368547763E17)
        .go();
  }

  @Test
  public void testInvalidUtf() throws Exception {
    final String subQuery =
        "select columns[0] as col0, columns[1] as col1 from cp.\"csv/invalidUtf.csv\" limit 500";
    final String sql =
        "SELECT convert_from(col0, 'UTF8', '') as c1 FROM ("
            + subQuery
            + ") where is_utf8(col0) is false";
    testBuilder()
        .sqlQuery(sql)
        .unOrdered()
        .baselineColumns("c1")
        .baselineValues("slcken")
        .baselineValues("undeveopable")
        .baselineValues("donswing")
        .go();
    testBuilder()
        .sqlQuery(sql)
        .unOrdered()
        .baselineColumns("c1")
        .baselineValues("slcken")
        .baselineValues("undeveopable")
        .baselineValues("donswing")
        .go();
  }

  @Test
  public void testPrimaryCacheInCodeCompilerFlow() throws Exception {

    final String subQuery =
        "select columns[0] as col0, columns[1] as col1 from cp.\"csv/invalidUtf.csv\" limit 500";
    final String sql1 =
        "SELECT convert_from(col0, 'UTF8', '') as c1 FROM ("
            + subQuery
            + ") where is_utf8(col0) is false";
    testBuilder()
        .sqlQuery(sql1)
        .unOrdered()
        .baselineColumns("c1")
        .baselineValues("slcken")
        .baselineValues("undeveopable")
        .baselineValues("donswing")
        .go();
    String sql2 = "select cast(null as varbinary(10)) as a";
    testBuilder().sqlQuery(sql2).unOrdered().baselineColumns("a").baselineValues(null).go();
    String sql3 =
        "WITH t1 \n"
            + "     AS (SELECT 0    AS \"A\", \n"
            + "                'No' AS \"B\" \n"
            + "         FROM   (VALUES ROW(1)) \n"
            + "         UNION ALL \n"
            + "         SELECT 1     AS \"A\", \n"
            + "                'Yes' AS \"B\" \n"
            + "         FROM   (VALUES ROW(1))) \n"
            + "SELECT * \n"
            + "FROM   t1 \n"
            + "WHERE  \"B\" = 'No'";
    testBuilder().sqlQuery(sql3).ordered().baselineColumns("A", "B").baselineValues(0, "No").go();

    testBuilder().sqlQuery(sql2).unOrdered().baselineColumns("a").baselineValues(null).go();
    testBuilder().sqlQuery(sql3).ordered().baselineColumns("A", "B").baselineValues(0, "No").go();
    testBuilder()
        .sqlQuery(sql1)
        .unOrdered()
        .baselineColumns("c1")
        .baselineValues("slcken")
        .baselineValues("undeveopable")
        .baselineValues("donswing")
        .go();
  }

  @Test
  public void testCacheFlowWithAQueryWithSplitsInGandivaAndJava() throws Exception {
    try {
      test(String.format("alter session set %s = true", ExecConstants.PARQUET_AUTO_CORRECT_DATES));
      final String sqlQuery =
          "SELECT o_orderkey,  extractYear(castDate(o_orderdate)) as y1,  "
              + "extractYear(TO_DATE(o_orderdate, 'yyyy-mm-dd')) as y2 "
              + "FROM "
              + "cp.\"tpch/orders.parquet\" "
              + "ORDER BY o_orderkey limit 1";
      testBuilder()
          .sqlQuery(sqlQuery)
          .unOrdered()
          .baselineColumns("o_orderkey", "y1", "y2")
          .baselineValues(1, 1996L, 1996L)
          .go();

      testBuilder()
          .sqlQuery(
              "SELECT o_orderkey,  extractYear(castDate(o_orderdate)) as y1,  "
                  + "extractYear(TO_DATE(o_orderdate, 'yyyy-mm-dd')) as y2 "
                  + "FROM "
                  + "cp.\"tpch/orders.parquet\" "
                  + "ORDER BY o_orderkey limit 1")
          .unOrdered()
          .baselineColumns("o_orderkey", "y1", "y2")
          .baselineValues(1, 1996L, 1996L)
          .go();

      test(
          String.format(
              "ALTER SESSION SET \"%s\" = false", ExecConstants.SPLIT_CACHING_ENABLED_KEY));
      testBuilder()
          .sqlQuery(
              "SELECT o_orderkey,  extractYear(castDate(o_orderdate)) as y1,  "
                  + "extractYear(TO_DATE(o_orderdate, 'yyyy-mm-dd')) as y2 "
                  + "FROM "
                  + "cp.\"tpch/orders.parquet\" "
                  + "ORDER BY o_orderkey limit 1")
          .unOrdered()
          .baselineColumns("o_orderkey", "y1", "y2")
          .baselineValues(1, 1996L, 1996L)
          .go();
    } finally {
      test(String.format("alter session set %s = false", ExecConstants.PARQUET_AUTO_CORRECT_DATES));
      test(
          String.format(
              "ALTER SESSION SET \"%s\" = true", ExecConstants.SPLIT_CACHING_ENABLED_KEY));
    }
  }

  @Test
  public void testNullInInClause() throws Exception {
    test("select * from cp.\"tpch/lineitem.parquet\" where l_orderkey in (-1, null)");
  }

  @Test
  public void nullBinaryLiteral() throws Exception {
    String sql = "select cast(null as varbinary(10)) as a";
    testBuilder().sqlQuery(sql).unOrdered().baselineColumns("a").baselineValues(null).go();
  }

  @Test
  public void stringLiteralFilter() throws Exception {
    String sql =
        "WITH t1 \n"
            + "     AS (SELECT 0    AS \"A\", \n"
            + "                'No' AS \"B\" \n"
            + "         FROM   (VALUES ROW(1)) \n"
            + "         UNION ALL \n"
            + "         SELECT 1     AS \"A\", \n"
            + "                'Yes' AS \"B\" \n"
            + "         FROM   (VALUES ROW(1))) \n"
            + "SELECT * \n"
            + "FROM   t1 \n"
            + "WHERE  \"B\" = 'No'";
    testBuilder().sqlQuery(sql).ordered().baselineColumns("A", "B").baselineValues(0, "No").go();
    testBuilder().sqlQuery(sql).ordered().baselineColumns("A", "B").baselineValues(0, "No").go();
  }

  // See DX-17817
  @Test
  public void testCorrectConstantHandlingInNLJE() throws Exception {
    try (AutoCloseable c = withOption(PlannerSettings.NLJOIN_FOR_SCALAR, false)) {
      testBuilder()
          .sqlQuery(
              "SELECT l_orderkey "
                  + "FROM "
                  + "cp.\"tpch/lineitem.parquet\" lineitem  left join  cp.\"tpch/orders.parquet\" on  (TO_DATE(o_orderdate, 'yyyy-mm-dd') > (TO_DATE(L_SHIPDATE, 'yyyy-mm-dd'))) "
                  + "limit 5")
          .unOrdered()
          .baselineColumns("l_orderkey")
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .go();
    }
  }

  // See DX-17818
  @Test
  public void leftJoinInequality() throws Exception {
    try (AutoCloseable c = withOption(PlannerSettings.NLJOIN_FOR_SCALAR, false);
        AutoCloseable c2 = withOption(PlannerSettings.ENABLE_JOIN_OPTIMIZATION, false);
        AutoCloseable c3 = withOption(HashJoinOperator.NUM_PARTITIONS, 1)) {
      String q =
          "SELECT l_orderkey "
              + "FROM "
              + "cp.\"tpch/orders.parquet\" left join cp.\"tpch/lineitem.parquet\"  on (L_orderkey = O_orderkey) and TO_DATE(o_orderdate, 'yyyy-mm-dd') between o_orderdate and l_shipdate "
              + "LIMIT 5";
      testBuilder()
          .sqlQuery(q)
          .unOrdered()
          .baselineColumns("l_orderkey")
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .go();
      // check swaps the declared right to a left.
      testPlanOneExpectedPattern(q, Pattern.quote("joinType=[left]"));
    }
  }

  // See DX-17818
  @Test
  public void rightJoinInequality() throws Exception {
    try (AutoCloseable c = withOption(PlannerSettings.NLJOIN_FOR_SCALAR, false);
        AutoCloseable c2 = withOption(PlannerSettings.ENABLE_JOIN_OPTIMIZATION, false);
        AutoCloseable c3 = withOption(HashJoinOperator.NUM_PARTITIONS, 1)) {

      String q =
          "SELECT l_orderkey "
              + "FROM "
              + "cp.\"tpch/orders.parquet\" right join cp.\"tpch/lineitem.parquet\"  on (L_orderkey = O_orderkey) and TO_DATE(o_orderdate, 'yyyy-mm-dd') between o_orderdate and l_shipdate "
              + "LIMIT 5";
      testBuilder()
          .sqlQuery(q)
          .unOrdered()
          .baselineColumns("l_orderkey")
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .baselineValues(1)
          .go();

      // check swaps the declared right to a left.
      testPlanOneExpectedPattern(q, Pattern.quote("joinType=[left]"));
    }
  }

  @Test
  public void stringLiteralComparison() throws Exception {
    String sql =
        ""
            + "SELECT\n"
            + "  a = b as example1,\n"
            + "  'foo' = 'foo ' as example2,\n"
            + "  a = 'foo ' as example3,\n"
            + "  c = 'foo ' as example4,\n"
            + "  b = CAST('foo' AS CHAR(3)) as example5\n"
            + "FROM (\n"
            + "  VALUES('foo', 'foo ', CAST('foo' AS CHAR(3)))) AS t(a, b, c)";
    testBuilder()
        .sqlQuery(sql)
        .ordered()
        .baselineColumns("example1", "example2", "example3", "example4", "example5")
        .baselineValues(false, false, false, false, false)
        .go();
  }

  @Test
  public void testUnionString() throws Exception {
    String sql =
        ""
            + "WITH t1 AS (\n"
            + "SELECT 0 AS \"First Loan Flag\", 'No' AS \"First Loan\"\n"
            + "FROM (VALUES ROW(1))\n"
            + "UNION ALL\n"
            + "SELECT 1 AS \"First Loan Flag\", 'Yes' AS \"First Loan\"\n"
            + "FROM (VALUES ROW(1)))\n"
            + "SELECT count(*) cnt\n"
            + "FROM   t1 \n"
            + "WHERE \"First Loan\" = 'Yes'";

    testBuilder().sqlQuery(sql).ordered().baselineColumns("cnt").baselineValues(1L).go();
  }

  @Test
  public void stringLiteralInClause() throws Exception {
    String sql =
        ""
            + "WITH t1 AS \n"
            + "( \n"
            + "       SELECT \n"
            + "              CASE n_regionkey \n"
            + "                     WHEN 0 THEN 'AFRICA' \n"
            + "                     WHEN 1 THEN 'AMERICA' \n"
            + "                     WHEN 2 THEN 'ASIA' \n"
            + "                     ELSE 'OTHER' \n"
            + "              END AS region \n"
            + "       FROM   cp.\"tpch/nation.parquet\") \n"
            + "SELECT count(*) cnt\n"
            + "FROM   t1 \n"
            + "WHERE  region IN ('ASIA')";

    testBuilder().sqlQuery(sql).ordered().baselineColumns("cnt").baselineValues(5L).go();
  }

  @Test
  public void threeWayJoinWithImplicitCastInCondition() throws Exception {
    // just testing that this successfully plans
    test(
        "select *\n"
            + "from\n"
            + "   INFORMATION_SCHEMA.CATALOGS,\n"
            + "   INFORMATION_SCHEMA.SCHEMATA,\n"
            + "   INFORMATION_SCHEMA.\"TABLES\"\n"
            + "where\n"
            + "   CATALOGS.CATALOG_NAME = SCHEMATA.CATALOG_NAME \n"
            + "   and CATALOGS.CATALOG_NAME = \"TABLES\".TABLE_CATALOG \n"
            + "   and SCHEMATA.SCHEMA_NAME = \"TABLES\".TABLE_SCHEMA  \n"
            + "   and SCHEMATA.CATALOG_NAME = \"TABLES\".TABLE_CATALOG");
  }

  @Test
  public void useMulti() throws Exception {
    test("use \"cp.tpch\"");
  }

  @Test
  public void manyInList() throws Exception {
    test(
        "select * from cp.\"tpch/lineitem.parquet\" where l_returnflag in ('a','b','c','d','e','f','g','h','i','j') or l_returnflag != 'x' limit 10");
  }

  // DX-22772
  @Test
  public void testGroupbyWithManyVarcharColumns() throws Exception {
    test(
        "select max(l_quantity) from cp.\"tpch/lineitem"
            + ".parquet\" group by l_returnflag, "
            + "concat(l_returnflag, '00'), "
            + "concat(l_returnflag, '01'), "
            + "concat(l_returnflag, '02'), "
            + "concat(l_returnflag, '03'), "
            + "concat(l_returnflag, '04'), "
            + "concat(l_returnflag, '05'), "
            + "concat(l_returnflag, '06'), "
            + "concat(l_returnflag, '07'), "
            + "concat(l_returnflag, '08'), "
            + "concat(l_returnflag, '09'), "
            + "concat(l_returnflag, '00'), "
            + "concat(l_returnflag, '11'), "
            + "concat(l_returnflag, '12'), "
            + "concat(l_returnflag, '13'), "
            + "concat(l_returnflag, '14'), "
            + "concat(l_returnflag, '15'), "
            + "concat(l_returnflag, '16'), "
            + "concat(l_returnflag, '17'), "
            + "concat(l_returnflag, '18'), "
            + "concat(l_returnflag, '19'), "
            + "concat(l_returnflag, '20'), "
            + "concat(l_returnflag, '21'), "
            + "concat(l_returnflag, '22'), "
            + "concat(l_returnflag, '23'), "
            + "concat(l_returnflag, '24'), "
            + "concat(l_returnflag, '25'), "
            + "concat(l_returnflag, '26')"
            + "");
  }

  @Test
  public void testGroupbyWithManyFixedColumns() throws Exception {
    test(
        "select max(l_quantity) from cp.\"tpch/lineitem"
            + ".parquet\" group by l_discount, "
            + "l_discount + 1, "
            + "l_discount + 2, "
            + "l_discount + 3, "
            + "l_discount + 4, "
            + "l_discount + 5, "
            + "l_discount + 6, "
            + "l_discount + 7, "
            + "l_discount + 8, "
            + "l_discount + 9, "
            + "l_discount + 10, "
            + "l_discount + 11, "
            + "l_discount + 12, "
            + "l_discount + 13, "
            + "l_discount + 14, "
            + "l_discount + 15, "
            + "l_discount + 16, "
            + "l_discount + 17, "
            + "l_discount + 18, "
            + "l_discount + 19, "
            + "l_discount + 20, "
            + "l_discount + 21, "
            + "l_discount + 22, "
            + "l_discount + 23, "
            + "l_discount + 24, "
            + "l_discount + 25, "
            + "l_discount + 26"
            + "");
  }

  @Test
  public void unknownListTypeCtas() throws Exception {
    test("create table dfs_test.unknownList as select * from cp.\"/json/unknownListType.json\"");
    test("select * from dfs_test.unknownList");
  }

  @Test
  public void emptyMapCtas() throws Exception {
    test("create table dfs_test.emptyMap as select * from cp.\"/json/emptyMap.json\"");
    test("select * from dfs_test.emptyMap");
  }

  @Test
  public void subQueryNotInWhereNotNull() throws Exception {
    String query =
        "SELECT l_returnflag, l_linestatus, sum(l_extendedprice)\n"
            + "FROM cp.\"tpch/lineitem.parquet\" as lineitem\n"
            + "where l_quantity not in\n"
            + "(select l_linenumber from cp.\"tpch/lineitem.parquet\" as lineitem where (l_linenumber is not null) group by l_linenumber)\n"
            + "group by l_returnflag, l_linestatus";

    test(query);

    // DX-35078
    try (AutoCloseable option = withOption(PlannerSettings.ENHANCED_FILTER_JOIN_PUSHDOWN, true)) {
      test(query);
    }
  }

  @Test
  public void testMinVarCharWithGroupBy() throws Exception {
    String sql =
        "select l_linenumber, min(l_shipmode) as min_ship_mode from cp.\"tpch/lineitem.parquet\" group by l_linenumber";
    testBuilder()
        .sqlQuery(sql)
        .unOrdered()
        .baselineColumns("l_linenumber", "min_ship_mode")
        .baselineValues(1, "AIR")
        .baselineValues(2, "AIR")
        .baselineValues(3, "AIR")
        .baselineValues(4, "AIR")
        .baselineValues(5, "AIR")
        .baselineValues(6, "AIR")
        .baselineValues(7, "AIR")
        .go();
  }

  @Test
  public void testInt8Parquet() throws Exception {
    String query = "select * from cp.\"/parquet/intTypes/int_8.parquet\"";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("index", "value")
        .baselineValues(1, 0)
        .baselineValues(2, -1)
        .baselineValues(3, 1)
        .baselineValues(4, -128)
        .baselineValues(5, 127)
        .go();
  }

  @Test
  public void testInt16Parquet() throws Exception {
    String query = "select * from cp.\"/parquet/intTypes/int_16.parquet\"";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("index", "value")
        .baselineValues(1, 0)
        .baselineValues(2, -1)
        .baselineValues(3, 1)
        .baselineValues(4, -32768)
        .baselineValues(5, 32767)
        .go();
  }

  /**
   * See DX-14959, when uint16/int16 et. al data types in a parquet file are considered as partition
   * columns ,the datasource can't be added.
   *
   * @throws Exception
   */
  @Test
  public void testParquetWithInt16ParitionedColumn() throws Exception {
    String query = "select * from cp.\"/parquet/intTypes/DX_14959.parquet\" LIMIT 10";
    test(query);
  }

  @Test
  public void testSelectWithOptionsDataset() throws Exception {
    test(
        "select * from table(cp.\"line.tbl\"(type => 'text', fieldDelimiter => '|', autoGenerateColumnNames => true))");
    test("select * from cp.\"line.tbl\"");
  }

  @Test
  public void testEmptyListSchemaLearning() throws Exception {
    String dfs_tmp = getDfsTestTmpSchemaLocation();
    File directory = new File(dfs_tmp, "emptyList");
    directory.mkdir();
    PrintStream file = new PrintStream(new File(directory.getPath(), "file.json"));
    file.println("{\"a\":1,\"b\":[]}");
    file.close();
    test("select * from dfs_test.emptyList");
    Thread.sleep(
        1200); // sleep so we make sure the filesystem uses a different modification time for the
    // second file.
    // getCatalogService().refreshSource(new NamespaceKey("dfs_test"),
    // CatalogService.REFRESH_EVERYTHING_NOW);
    PrintStream file2 = new PrintStream(new File(directory.getPath(), "file2.json"));
    file2.println("{\"a\":1,\"b\":[\"b\"]}");
    file2.close();
    // TODO(AH) force refresh schema
    ((CatalogServiceImpl) getCatalogService())
        .refreshSource(
            new NamespaceKey("dfs_test"),
            CatalogService.REFRESH_EVERYTHING_NOW,
            SourceUpdateType.FULL);
    try {
      test("select * from dfs_test.emptyList");
    } catch (Exception e) {
      logger.debug("Schema learned", e);
      // learn schema
    }
    final List<QueryDataBatch> results = testSqlWithResults("select * from dfs_test.emptyList");
    try {
      Assert.assertEquals(
          MinorType.VARCHAR,
          results
              .get(0)
              .getHeader()
              .getDef()
              .getField(1)
              .getChild(2)
              .getMajorType()
              .getMinorType());
    } finally {
      for (QueryDataBatch batch : results) {
        batch.close();
      }
    }
  }

  @Test
  public void testParquetAlternatingNullRuns() throws Exception {
    test(
        String.format(
            "create table dfs_test.f as select * from dfs.\"%s/json/tableWithNullStrings\"",
            TEST_RES_PATH));
    String query = "select a, b from dfs_test.f where b like '%hell%'";
    TestBuilder builder = testBuilder().sqlQuery(query).unOrdered().baselineColumns("a", "b");
    for (int i = 0; i < 10; i++) {
      builder.baselineValues(null, "hello");
    }
    for (int i = 0; i < 9; i++) {
      builder.baselineValues("hello", "hello");
    }
    for (int i = 0; i < 10; i++) {
      builder.baselineValues(null, "hello");
    }
    for (int i = 0; i < 9; i++) {
      builder.baselineValues("hello", "hello");
    }
    builder.go();
  }

  @Test
  public void testLongDirectoryNames() throws Exception {
    String dfs_tmp = getDfsTestTmpSchemaLocation();
    File directory = new File(dfs_tmp, "nestedTable");
    directory.mkdir();
    File directory2 = new File(dfs_tmp, "nestedTable/veryLongDirectoryName/");
    directory2.mkdir();
    File data = new File(dfs_tmp, "nestedTable/veryLongDirectoryName/file.csv");
    PrintStream printStream = new PrintStream(data);
    for (int i = 0; i < 5000; i++) {
      printStream.println("a");
    }
    printStream.close();
    test("select * from dfs_test.nestedTable");
  }

  @Test
  public void testBooleanIntegerEquals() throws Exception {
    test("SELECT * FROM (VALUES(1)) WHERE 1 <> true");
    test("SELECT * FROM (VALUES(1)) WHERE true = 0");
    test("SELECT * FROM (VALUES(1)) WHERE false = 1");
  }

  @Test
  public void testBangEqual() throws Exception {
    test("SELECT * FROM (VALUES(1)) WHERE 1 != 0");
  }

  @Test
  public void testReduceExpressionsWithVarBinary() throws Exception {
    test("select c_int from cp.\"parquet/varBinaryTest\" where c_decimal28 is null");
  }

  @Test
  public void testValuesNullIf() throws Exception {
    String query =
        "SELECT id FROM (VALUES(''),('asdfkjhasdjkhgavdjhkgdvkjhg'),('aaaaa'),(''),('zzzzzzz'),('a'),('z'),('non-null-value')) tbl(id) WHERE NULLIF(id,'') IS NULL";
    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("id")
        .baselineValues("")
        .baselineValues("")
        .go();
  }

  @Test
  public void testReduceConstants() throws Exception {
    test(
        "select extract(second from now())=extract(second from current_timestamp) from INFORMATION_SCHEMA.CATALOGS limit 1");
  }

  @Test
  public void currentTimestampTypes() throws Exception {
    testBuilder()
        .sqlQuery(
            "SELECT typeof(CURRENT_TIMESTAMP) c1, typeof(CURRENT_TIMESTAMP(3)) c2 FROM (VALUES(1))")
        .unOrdered()
        .baselineColumns("c1", "c2")
        .baselineValues("TIMESTAMP", "TIMESTAMP")
        .go();
  }

  @Test
  public void dateTimeTypes() throws Exception {
    testPlanMatchingPatterns(
        "SELECT CURRENT_TIME ct, CURRENT_TIME(0) ct0,"
            + " CURRENT_TIMESTAMP cts, CURRENT_TIMESTAMP(1) cts1 FROM (VALUES(1))",
        new String[] {
          "TIME\\(3\\) ct, TIME\\(3\\) ct0, TIMESTAMP\\(3\\) cts, TIMESTAMP\\(3\\) cts1"
        });
  }

  @Test
  public void testValues2() throws Exception {
    String query =
        "SELECT id FROM (VALUES(''),(''),('non-null-value')) tbl(id) WHERE NULLIF(id,'') IS NOT NULL";
    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("id")
        .baselineValues("non-null-value")
        .go();
  }

  @Test
  public void testQ14Regression() throws Exception {
    String query =
        ""
            + "select x, y, z\n"
            + "from (\n"
            + "  select customer_region_id, fname, avg(total_children)\n"
            + "  from cp.\"customer.json\"\n"
            + "  group by customer_region_id, fname) as sq(x, y, z)\n"
            + "where coalesce(x, 100) = 10";
    testPlanMatchingPatterns(
        query,
        new String[] {
          "Project\\(x=\\[CAST\\(10:BIGINT\\):BIGINT\\], y=\\[\\$0\\], z=\\[",
          "HashAgg\\(group=\\[\\{0\\}\\], agg\\#0=\\[.?SUM.?\\(\\$2\\)\\], agg\\#1=\\[COUNT\\(\\$2\\)\\]\\)",
          "Filter\\(condition=\\[=\\(\\$1, 10\\)\\]\\)"
        },
        null);
  }

  @Test
  public void testLocate() throws Exception {
    try {
      test("use dfs_test");
      test("create view locateview as (select * from cp.\"customer.json\" where customer_id < 5);");

      testBuilder()
          .sqlQuery("select locate('Spence', lname, 1) as A from locateview")
          .ordered()
          .baselineColumns("A")
          .baselineValues(0)
          .baselineValues(0)
          .baselineValues(0)
          .baselineValues(1)
          .build()
          .run();

    } finally {
      test("drop view locateview;");
    }
  }

  @Test // see DRILL-2328
  public void testConcatOnNull() throws Exception {
    try {
      test("use dfs_test");
      test("create view concatNull as (select * from cp.\"customer.json\" where customer_id < 5);");

      // Test Left Null
      testBuilder()
          .sqlQuery(
              "select (mi || lname) as CONCATOperator, mi, lname, concat(mi, lname) as CONCAT from concatNull")
          .ordered()
          .baselineColumns("CONCATOperator", "mi", "lname", "CONCAT")
          .baselineValues("A.Nowmer", "A.", "Nowmer", "A.Nowmer")
          .baselineValues("I.Whelply", "I.", "Whelply", "I.Whelply")
          .baselineValues(null, null, "Derry", "Derry")
          .baselineValues("J.Spence", "J.", "Spence", "J.Spence")
          .build()
          .run();

      // Test Right Null
      testBuilder()
          .sqlQuery(
              "select (lname || mi) as CONCATOperator, lname, mi, concat(lname, mi) as CONCAT from concatNull")
          .ordered()
          .baselineColumns("CONCATOperator", "lname", "mi", "CONCAT")
          .baselineValues("NowmerA.", "Nowmer", "A.", "NowmerA.")
          .baselineValues("WhelplyI.", "Whelply", "I.", "WhelplyI.")
          .baselineValues(null, "Derry", null, "Derry")
          .baselineValues("SpenceJ.", "Spence", "J.", "SpenceJ.")
          .build()
          .run();

      // Test Two Sides
      testBuilder()
          .sqlQuery(
              "select (mi || mi) as CONCATOperator, mi, mi, concat(mi, mi) as CONCAT from concatNull")
          .ordered()
          .baselineColumns("CONCATOperator", "mi", "mi0", "CONCAT")
          .baselineValues("A.A.", "A.", "A.", "A.A.")
          .baselineValues("I.I.", "I.", "I.", "I.I.")
          .baselineValues(null, null, null, "")
          .baselineValues("J.J.", "J.", "J.", "J.J.")
          .build()
          .run();

      testBuilder()
          .sqlQuery(
              "select (cast(null as varchar(10)) || lname) as CONCATOperator, "
                  + "cast(null as varchar(10)) as NullColumn, lname, concat(cast(null as varchar(10)), lname) as CONCAT from concatNull")
          .ordered()
          .baselineColumns("CONCATOperator", "NullColumn", "lname", "CONCAT")
          .baselineValues(null, null, "Nowmer", "Nowmer")
          .baselineValues(null, null, "Whelply", "Whelply")
          .baselineValues(null, null, "Derry", "Derry")
          .baselineValues(null, null, "Spence", "Spence")
          .build()
          .run();
    } finally {
      test("drop view concatNull;");
    }
  }

  @Test // see DRILL-2054
  public void testConcatOperator() throws Exception {
    testBuilder()
        .sqlQuery(
            "select cast(n_nationkey as varchar) || '+' || n_name || '=' as CONCAT, n_nationkey, '+' as PLUS, n_name from cp.\"tpch/nation.parquet\"")
        .ordered()
        .csvBaselineFile("testframework/testExampleQueries/testConcatOperator.tsv")
        .baselineTypes(MinorType.VARCHAR, MinorType.INT, MinorType.VARCHAR, MinorType.VARCHAR)
        .baselineColumns("CONCAT", "n_nationkey", "PLUS", "n_name")
        .build()
        .run();

    testBuilder()
        .sqlQuery(
            "select (cast(n_nationkey as varchar) || n_name) as CONCAT from cp.\"tpch/nation.parquet\"")
        .ordered()
        .csvBaselineFile(
            "testframework/testExampleQueries/testConcatOperatorInputTypeCombination.tsv")
        .baselineTypes(MinorType.VARCHAR)
        .baselineColumns("CONCAT")
        .build()
        .run();

    testBuilder()
        .sqlQuery(
            "select (cast(n_nationkey as varchar) || cast(n_name as varchar(30))) as CONCAT from cp.\"tpch/nation.parquet\"")
        .ordered()
        .csvBaselineFile(
            "testframework/testExampleQueries/testConcatOperatorInputTypeCombination.tsv")
        .baselineTypes(MinorType.VARCHAR)
        .baselineColumns("CONCAT")
        .build()
        .run();

    testBuilder()
        .sqlQuery(
            "select (cast(n_nationkey as varchar(30)) || n_name) as CONCAT from cp.\"tpch/nation.parquet\"")
        .ordered()
        .csvBaselineFile(
            "testframework/testExampleQueries/testConcatOperatorInputTypeCombination.tsv")
        .baselineTypes(MinorType.VARCHAR)
        .baselineColumns("CONCAT")
        .build()
        .run();
  }

  @Test
  public void testTextInClasspathStorage() throws Exception {
    test("select * from cp.\"/store/text/classpath_storage_csv_test.csv\"");
  }

  @Test
  public void testParquetComplex() throws Exception {
    test("select recipe from cp.\"parquet/complex.parquet\"");
    test("select * from cp.\"parquet/complex.parquet\"");
    test(
        "select recipe, c.inventor.name as name, c.inventor.age as age from cp.\"parquet/complex.parquet\" c");
  }

  @Test // see DRILL-553
  public void testQueryWithNullValues() throws Exception {
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
    test("select count(*) from cp.\"customer.json\" limit 1");
  }

  @Test
  @Ignore()
  public void testJoinMerge() throws Exception {
    try (AutoCloseable option = withOption(PlannerSettings.HASHJOIN, false)) {
      test(
          "select count(*) \n"
              + "  from (select l.l_orderkey as x, c.c_custkey as y \n"
              + "  from cp.\"tpch/lineitem.parquet\" l \n"
              + "    left outer join cp.\"tpch/customer.parquet\" c \n"
              + "      on l.l_orderkey = c.c_custkey) as foo\n"
              + "  where x < 10000\n"
              + "");
    }
  }

  @Test
  public void testJoinExpOn() throws Exception {
    test(
        "select a.n_nationkey from cp.\"tpch/nation.parquet\" a join cp.\"tpch/region.parquet\" b on a.n_regionkey + 1 = b.r_regionkey and a.n_regionkey + 1 = b.r_regionkey;");
  }

  @Test
  public void testJoinExpWhere() throws Exception {
    test(
        "select a.n_nationkey from cp.\"tpch/nation.parquet\" a , cp.\"tpch/region.parquet\" b where a.n_regionkey + 1 = b.r_regionkey and a.n_regionkey + 1 = b.r_regionkey;");
  }

  @Test
  public void testPushExpInJoinConditionInnerJoin() throws Exception {
    test(
        "select a.n_nationkey from cp.\"tpch/nation.parquet\" a join cp.\"tpch/region.parquet\" b "
            + ""
            + " on a.n_regionkey + 100  = b.r_regionkey + 200"
            + // expressions in both sides of equal join filter
            "   and (substr(a.n_name,1,3)= 'L1' or substr(a.n_name,2,2) = 'L2') "
            + // left filter
            "   and (substr(b.r_name,1,3)= 'R1' or substr(b.r_name,2,2) = 'R2') "
            + // right filter
            "   and (substr(a.n_name,2,3)= 'L3' or substr(b.r_name,3,2) = 'R3');"); // non-equal
    // join filter
  }

  @Test
  public void testPushExpInJoinConditionWhere() throws Exception {
    test(
        "select a.n_nationkey from cp.\"tpch/nation.parquet\" a , cp.\"tpch/region.parquet\" b "
            + ""
            + " where a.n_regionkey + 100  = b.r_regionkey + 200"
            + // expressions in both sides of equal join filter
            "   and (substr(a.n_name,1,3)= 'L1' or substr(a.n_name,2,2) = 'L2') "
            + // left filter
            "   and (substr(b.r_name,1,3)= 'R1' or substr(b.r_name,2,2) = 'R2') "
            + // right filter
            "   and (substr(a.n_name,2,3)= 'L3' or substr(b.r_name,3,2) = 'R3');"); // non-equal
    // join filter
  }

  @Test
  public void testPushExpInJoinConditionLeftJoin() throws Exception {
    test(
        "select a.n_nationkey, b.r_regionkey from cp.\"tpch/nation.parquet\" a left join cp.\"tpch/region.parquet\" b "
            + ""
            + " on a.n_regionkey +100 = b.r_regionkey +200 "
            + // expressions in both sides of equal join filter
            //    "   and (substr(a.n_name,1,3)= 'L1' or substr(a.n_name,2,2) = 'L2') " +  // left
            // filter
            "   and (substr(b.r_name,1,3)= 'R1' or substr(b.r_name,2,2) = 'R2') "); // right filter
    //    "   and (substr(a.n_name,2,3)= 'L3' or substr(b.r_name,3,2) = 'R3');");  // non-equal join
    // filter
  }

  @Test
  public void testPushExpInJoinConditionRightJoin() throws Exception {
    test(
        "select a.n_nationkey, b.r_regionkey from cp.\"tpch/nation.parquet\" a right join cp.\"tpch/region.parquet\" b "
            + ""
            + " on a.n_regionkey +100 = b.r_regionkey +200 "
            + // expressions in both sides of equal join filter
            "   and (substr(a.n_name,1,3)= 'L1' or substr(a.n_name,2,2) = 'L2') "); // left filter
    //   "   and (substr(b.r_name,1,3)= 'R1' or substr(b.r_name,2,2) = 'R2') " +  // right filter
    //   "   and (substr(a.n_name,2,3)= 'L3' or substr(b.r_name,3,2) = 'R3');");  // non-equal join
    // filter
  }

  @Test
  public void testCaseReturnValueVarChar() throws Exception {
    test(
        "select case when employee_id < 1000 then 'ABC' else 'DEF' end from cp.\"employee.json\" limit 5");
  }

  @Test
  public void testCaseReturnValueBigInt() throws Exception {
    test(
        "select case when employee_id < 1000 then 1000 else 2000 end from cp.\"employee.json\" limit 5");
  }

  @Test
  public void testHashPartitionSV2() throws Exception {
    test(
        "select count(n_nationkey) from cp.\"tpch/nation.parquet\" where n_nationkey > 8 group by n_regionkey");
  }

  @Test
  public void testHashPartitionSV4() throws Exception {
    test(
        "select count(n_nationkey) as cnt from cp.\"tpch/nation.parquet\" group by n_regionkey order by cnt");
  }

  @Test
  public void testSelectWithLimit() throws Exception {
    test("select employee_id,  first_name, last_name from cp.\"employee.json\" limit 5 ");
  }

  @Test
  public void testSelectWithLimit2() throws Exception {
    test("select l_comment, l_orderkey from cp.\"tpch/lineitem.parquet\" limit 10000 ");
  }

  @Test
  public void testSVRV4() throws Exception {
    test("select employee_id,  first_name from cp.\"employee.json\" order by employee_id ");
  }

  @Test
  public void testSVRV4MultBatch() throws Exception {
    test("select l_orderkey from cp.\"tpch/lineitem.parquet\" order by l_orderkey limit 10000 ");
  }

  @Test
  public void testSVRV4Join() throws Exception {
    test(
        "select count(*) from cp.\"tpch/lineitem.parquet\" l, cp.\"tpch/partsupp.parquet\" ps \n"
            + " where l.l_partkey = ps.ps_partkey and l.l_suppkey = ps.ps_suppkey ;");
  }

  @Test
  public void testText() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/store/text/data/regions.csv").toURI().getPath().toString();
    String query = String.format("select * from dfs.\"%s\"", root);
    test(query);
  }

  @Test
  public void testFilterOnArrayTypes() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/store/text/data/regions.csv").toURI().getPath().toString();
    String query =
        String.format(
            "select columns[0] from dfs.\"%s\" "
                + " where cast(columns[0] as int) > 1 and cast(columns[1] as varchar(20))='ASIA'",
            root);
    test(query);
  }

  @Test
  @Ignore("DRILL-3774")
  public void testTextPartitions() throws Exception {
    String root = FileUtils.getResourceAsFile("/store/text/data/").toURI().getPath().toString();
    String query = String.format("select * from dfs.\"%s\"", root);
    test(query);
  }

  @Test
  @Ignore("DRILL-3004")
  public void testJoin() throws Exception {
    try (AutoCloseable ac = withOption(PlannerSettings.HASHJOIN, false)) {
      test(
          "SELECT\n"
              + "  nations.N_NAME,\n"
              + "  regions.R_NAME\n"
              + "FROM\n"
              + "  cp.\"tpch/nation.parquet\" nations\n"
              + "JOIN\n"
              + "  cp.\"tpch/region.parquet\" regions\n"
              + "  on nations.N_REGIONKEY = regions.R_REGIONKEY where 1 = 0");
    }
  }

  @Test
  public void testFullOuterJoinTrueCondition() throws Exception {
    try (AutoCloseable ac = withOption(PlannerSettings.ENABLE_REDUCE_JOIN, true)) {
      test(
          "with n as (SELECT * FROM cp.\"tpch/nation.parquet\" nations WHERE nations.N_REGIONKEY = 1),\n"
              + "r as (SELECT * FROM cp.\"tpch/region.parquet\" regions WHERE regions.R_REGIONKEY = 1)\n"
              + "SELECT count(*) FROM n FULL OUTER JOIN r\n"
              + "on n.N_REGIONKEY = r.R_REGIONKEY");
    }
  }

  @Test
  public void testWhere() throws Exception {
    test("select * from cp.\"employee.json\" ");
  }

  @Test
  public void testGroupBy() throws Exception {
    test(
        "select marital_status, COUNT(1) as cnt from cp.\"employee.json\" group by marital_status");
  }

  @Test
  public void testExplainPhysical() throws Exception {
    test(
        "explain plan for select marital_status, COUNT(1) as cnt from cp.\"employee.json\" group by marital_status");
  }

  @Test
  public void testExplainLogical() throws Exception {
    test(
        "explain plan without implementation for select marital_status, COUNT(1) as cnt from cp.\"employee.json\" group by marital_status");
  }

  @Test
  public void testGroupScanRowCountExp1() throws Exception {
    test(
        "EXPLAIN plan for select count(n_nationkey) as mycnt, count(*) + 2 * count(*) as mycnt2 from cp.\"tpch/nation.parquet\" ");
  }

  @Test
  public void testGroupScanRowCount1() throws Exception {
    test(
        "select count(n_nationkey) as mycnt, count(*) + 2 * count(*) as mycnt2 from cp.\"tpch/nation.parquet\" ");
  }

  @Test
  public void testColunValueCnt() throws Exception {
    test("select count( 1 + 2) from cp.\"tpch/nation.parquet\" ");
  }

  @Test
  public void testGroupScanRowCountExp2() throws Exception {
    test(
        "EXPLAIN plan for select count(*) as mycnt, count(*) + 2 * count(*) as mycnt2 from cp.\"tpch/nation.parquet\" ");
  }

  @Test
  public void testGroupScanRowCount2() throws Exception {
    test(
        "select count(*) as mycnt, count(*) + 2 * count(*) as mycnt2 from cp.\"tpch/nation.parquet\" where 1 < 2");
  }

  @Test
  @Ignore("no longer supported because of sampling")
  // cast non-exist column from json file. Should return null value.
  public void testDrill428() throws Exception {
    test("select cast(NON_EXIST_COL as varchar(10)) from cp.\"employee.json\" limit 2; ");
  }

  @Test // Bugs DRILL-727, DRILL-940
  public void testOrderByDiffColumn() throws Exception {
    test("select r_name from cp.\"tpch/region.parquet\" order by r_regionkey");
    test("select r_name from cp.\"tpch/region.parquet\" order by r_name, r_regionkey");
    test("select cast(r_name as varchar(20)) from cp.\"tpch/region.parquet\" order by r_name");
  }

  @Test // tests with LIMIT 0
  @Ignore("DRILL-1866")
  public void testLimit0_1() throws Exception {
    test("select n_nationkey, n_name from cp.\"tpch/nation.parquet\" limit 0");
    test("select n_nationkey, n_name from cp.\"tpch/nation.parquet\" limit 0 offset 5");
    test("select n_nationkey, n_name from cp.\"tpch/nation.parquet\" order by n_nationkey limit 0");
    test("select * from cp.\"tpch/nation.parquet\" limit 0");
    test(
        "select n.n_nationkey from cp.\"tpch/nation.parquet\" n, cp.\"tpch/region.parquet\" r where n.n_regionkey = r.r_regionkey limit 0");
    test(
        "select n_regionkey, count(*) from cp.\"tpch/nation.parquet\" group by n_regionkey limit 0");
  }

  @Test
  public void testTextJoin() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/store/text/data/nations.csv").toURI().getPath().toString();
    String root1 =
        FileUtils.getResourceAsFile("/store/text/data/regions.csv").toURI().getPath().toString();
    String query =
        String.format(
            "select t1.columns[1] from dfs.\"%s\" t1,  dfs.\"%s\" t2 where t1.columns[0] = t2.columns[0]",
            root, root1);
    test(query);
  }

  @Test // DRILL-811
  public void testDRILL_811View() throws Exception {
    test("use dfs_test");
    test("create view nation_view_testexamplequeries as select * from cp.\"tpch/nation.parquet\";");

    test(
        "select n.n_nationkey, n.n_name, n.n_regionkey from nation_view_testexamplequeries n where n.n_nationkey > 8 order by n.n_regionkey");

    test(
        "select n.n_regionkey, count(*) as cnt from nation_view_testexamplequeries n where n.n_nationkey > 8 group by n.n_regionkey order by n.n_regionkey");

    test("drop view nation_view_testexamplequeries ");
  }

  @Test // DRILL-811
  public void testDRILL_811ViewJoin() throws Exception {
    test("use dfs_test");
    test("create view nation_view_testexamplequeries as select * from cp.\"tpch/nation.parquet\";");
    test("create view region_view_testexamplequeries as select * from cp.\"tpch/region.parquet\";");

    test(
        "select n.n_nationkey, n.n_regionkey, r.r_name from region_view_testexamplequeries r , nation_view_testexamplequeries n where r.r_regionkey = n.n_regionkey ");

    test(
        "select n.n_regionkey, count(*) as cnt from region_view_testexamplequeries r , nation_view_testexamplequeries n where r.r_regionkey = n.n_regionkey and n.n_nationkey > 8 group by n.n_regionkey order by n.n_regionkey");

    test(
        "select n.n_regionkey, count(*) as cnt from region_view_testexamplequeries r join nation_view_testexamplequeries n on r.r_regionkey = n.n_regionkey and n.n_nationkey > 8 group by n.n_regionkey order by n.n_regionkey");

    test("drop view region_view_testexamplequeries ");
    test("drop view nation_view_testexamplequeries ");
  }

  @Test // DRILL-811
  public void testDRILL_811Json() throws Exception {
    test("use dfs_test");
    test("create view region_view_testexamplequeries as select * from cp.\"region.json\";");
    test(
        "select sales_city, sales_region from region_view_testexamplequeries where region_id > 50 order by sales_country; ");
    test("drop view region_view_testexamplequeries ");
  }

  @Test
  public void testCase() throws Exception {
    test(
        "select case when n_nationkey > 0 and n_nationkey < 2 then concat(n_name, '_abc') when n_nationkey >=2 and n_nationkey < 4 then '_EFG' else concat(n_name,'_XYZ') end from cp.\"tpch/nation.parquet\" ;");
  }

  @Test // tests join condition that has different input types
  public void testJoinCondWithDifferentTypes() throws Exception {
    test(
        "select t1.department_description from cp.\"department.json\" t1, cp.\"employee.json\" t2 where (cast(t1.department_id as double)) = t2.department_id");
    test(
        "select t1.full_name from cp.\"employee.json\" t1, cp.\"department.json\" t2 where cast(t1.department_id as double) = t2.department_id and cast(t1.position_id as bigint) = t2.department_id");

    // See DRILL-3995. Re-enable this once fixed.
    //    test("select t1.full_name from cp.\"employee.json\" t1, cp.\"department.json\" t2 where
    // t1.department_id = t2.department_id and t1.position_id = t2.department_id");
  }

  @Test
  public void testTopNWithSV2() throws Exception {
    int actualRecordCount =
        testSql(
            "select N_NATIONKEY from cp.\"tpch/nation.parquet\" where N_NATIONKEY < 10 order by N_NATIONKEY limit 5");
    int expectedRecordCount = 5;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);
  }

  @Test
  public void testTextQueries() throws Exception {
    test("select cast('285572516' as int) from cp.\"tpch/nation.parquet\" limit 1");
  }

  @Test // DRILL-1544
  public void testLikeEscape() throws Exception {
    int actualRecordCount =
        testSql(
            "select id, name from cp.\"jsoninput/specialchar.json\" where name like '%#_%' ESCAPE '#'");
    int expectedRecordCount = 1;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);
  }

  @Test
  public void testSimilarEscape() throws Exception {
    int actualRecordCount =
        testSql(
            "select id, name from cp.\"jsoninput/specialchar.json\" where name similar to '(N|S)%#_%' ESCAPE '#'");
    int expectedRecordCount = 1;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);
  }

  @Test
  public void testImplicitDownwardCast() throws Exception {
    int actualRecordCount =
        testSql(
            "select o_totalprice from cp.\"tpch/orders.parquet\" where o_orderkey=60000 and o_totalprice=299402");
    int expectedRecordCount = 0;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);
  }

  @Test // DRILL-1470
  public void testCastToVarcharWithLength() throws Exception {
    // cast from varchar with unknown length to a fixed length.
    int actualRecordCount =
        testSql(
            "select first_name from cp.\"employee.json\" where cast(first_name as varchar(2)) = 'Sh'");
    int expectedRecordCount = 27;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    // cast from varchar with unknown length to varchar(5), then to varchar(10), then to varchar(2).
    // Should produce the same result as the first query.
    actualRecordCount =
        testSql(
            "select first_name from cp.\"employee.json\" where cast(cast(cast(first_name as varchar(5)) as varchar(10)) as varchar(2)) = 'Sh'");
    expectedRecordCount = 27;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    // this long nested cast expression should be essentially equal to substr(), meaning the query
    // should return every row in the table.
    actualRecordCount =
        testSql(
            "select first_name from cp.\"employee.json\" where cast(cast(cast(first_name as varchar(5)) as varchar(10)) as varchar(2)) = substr(first_name, 1, 2)");
    expectedRecordCount = 1155;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    // cast is applied to a column from parquet file.
    actualRecordCount =
        testSql(
            "select n_name from cp.\"tpch/nation.parquet\" where cast(n_name as varchar(2)) = 'UN'");
    expectedRecordCount = 2;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);
  }

  @Test // DRILL-1488
  public void testIdentifierMaxLength() throws Exception {
    // use long column alias name (approx 160 chars)
    test(
        "select employee_id as  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa from cp.\"employee.json\" limit 1");

    // use long table alias name  (approx 160 chars)
    test(
        "select employee_id from cp.\"employee.json\" as aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa limit 1");
  }

  @Test // DRILL-1846  (this tests issue with SimpleMergeExchange)
  public void testOrderByDiffColumnsInSubqAndOuter() throws Exception {
    String query =
        "select n.n_nationkey from  (select n_nationkey, n_regionkey from cp.\"tpch/nation.parquet\" order by n_regionkey) n  order by n.n_nationkey";
    // set slice_target = 1 to force exchanges
    try (AutoCloseable ac = withOption(ExecConstants.SLICE_TARGET_OPTION, 1)) {
      test(query);
    }
  }

  @Test // DRILL-1846  (this tests issue with UnionExchange)
  @Ignore("DRILL-1866")
  public void testLimitInSubqAndOrderByOuter() throws Exception {
    String query =
        "select t2.n_nationkey from (select n_nationkey, n_regionkey from cp.\"tpch/nation.parquet\" t1 group by n_nationkey, n_regionkey limit 10) t2 order by t2.n_nationkey";
    // set slice_target = 1 to force exchanges
    try (AutoCloseable ac = withOption(ExecConstants.SLICE_TARGET_OPTION, 1)) {
      test(query);
    }
  }

  @Test // DRILL-1788
  public void testCaseInsensitiveJoin() throws Exception {
    test(
        "select n3.n_name from (select n2.n_name from cp.\"tpch/nation.parquet\" n1, cp.\"tpch/nation.parquet\" n2 where n1.N_name = n2.n_name) n3 "
            + " join cp.\"tpch/nation.parquet\" n4 on n3.n_name = n4.n_name");
  }

  @Test // DRILL-1561
  public void test2PhaseAggAfterOrderBy() throws Exception {
    String query =
        "select count(*) from (select o_custkey from cp.\"tpch/orders.parquet\" order by o_custkey)";
    // set slice_target = 1 to force exchanges and 2-phase aggregation
    try (AutoCloseable ac = withOption(ExecConstants.SLICE_TARGET_OPTION, 1)) {
      test(query);
    }
  }

  @Test // DRILL-1867
  public void testCaseInsensitiveSubQuery() throws Exception {
    int actualRecordCount = 0, expectedRecordCount = 0;

    // source is JSON
    actualRecordCount =
        testSql(
            "select EMPID from ( select employee_id as empid from cp.\"employee.json\" limit 2)");
    expectedRecordCount = 2;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    actualRecordCount =
        testSql(
            "select EMPLOYEE_ID from ( select employee_id from cp.\"employee.json\" where Employee_id is not null limit 2)");
    expectedRecordCount = 2;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    actualRecordCount =
        testSql(
            "select x.EMPLOYEE_ID from ( select employee_id from cp.\"employee.json\" limit 2) X");
    expectedRecordCount = 2;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    // source is PARQUET
    actualRecordCount =
        testSql(
            "select NID from ( select n_nationkey as nid from cp.\"tpch/nation.parquet\") where NID = 3");
    expectedRecordCount = 1;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    actualRecordCount =
        testSql(
            "select x.N_nationkey from ( select n_nationkey from cp.\"tpch/nation.parquet\") X where N_NATIONKEY = 3");
    expectedRecordCount = 1;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);

    // source is CSV
    String root =
        FileUtils.getResourceAsFile("/store/text/data/regions.csv").toURI().getPath().toString();
    String query =
        String.format(
            "select rid, x.name from (select columns[0] as RID, columns[1] as NAME from dfs.\"%s\") X where X.rid = 2",
            root);
    actualRecordCount = testSql(query);
    expectedRecordCount = 1;
    assertEquals(
        String.format(
            "Received unexpected number of rows in output: expected=%d, received=%s",
            expectedRecordCount, actualRecordCount),
        expectedRecordCount,
        actualRecordCount);
  }

  @Test
  public void testMultipleCountDistinctWithGroupBy() throws Exception {
    String query =
        "select n_regionkey, count(distinct n_nationkey), count(distinct n_name) from cp.\"tpch/nation.parquet\" group by n_regionkey;";

    try (AutoCloseable ac1 = withOption(PlannerSettings.HASHAGG, false);
        AutoCloseable ac2 = withOption(PlannerSettings.STREAMAGG, true); ) {
      test(query);
      try (AutoCloseable ac3 = withOption(ExecConstants.SLICE_TARGET_OPTION, 1)) {
        test(query);
      }
    }

    try (AutoCloseable ac1 = withOption(PlannerSettings.HASHAGG, true);
        AutoCloseable ac2 = withOption(PlannerSettings.STREAMAGG, false); ) {
      test(query);
      try (AutoCloseable ac3 = withOption(ExecConstants.SLICE_TARGET_OPTION, 1)) {
        test(query);
      }
    }
  }

  @Test // DRILL-2019
  public void testFilterInSubqueryAndOutside() throws Exception {
    String query1 =
        "select r_regionkey from (select r_regionkey from cp.\"tpch/region.parquet\" o where r_regionkey < 2) where r_regionkey > 2";
    String query2 =
        "select r_regionkey from (select r_regionkey from cp.\"tpch/region.parquet\" o where r_regionkey < 4) where r_regionkey > 1";
    int actualRecordCount = 0;
    int expectedRecordCount = 0;

    actualRecordCount = testSql(query1);
    assertEquals(expectedRecordCount, actualRecordCount);

    expectedRecordCount = 2;
    actualRecordCount = testSql(query2);
    assertEquals(expectedRecordCount, actualRecordCount);
  }

  @Test // DRILL-1973
  public void testLimit0SubqueryWithFilter() throws Exception {
    String query1 =
        "select * from (select sum(1) as x from  cp.\"tpch/region.parquet\" limit 0) WHERE x < 10";
    String query2 =
        "select * from (select sum(1) as x from  cp.\"tpch/region.parquet\" limit 0) WHERE (0 = 1)";
    int actualRecordCount = 0;
    int expectedRecordCount = 0;

    actualRecordCount = testSql(query1);
    assertEquals(expectedRecordCount, actualRecordCount);

    actualRecordCount = testSql(query2);
    assertEquals(expectedRecordCount, actualRecordCount);
  }

  @Test // DRILL-2063
  public void testAggExpressionWithGroupBy() throws Exception {
    String query =
        "select l_suppkey, sum(l_extendedprice)/sum(l_quantity) as avg_price \n"
            + " from cp.\"tpch/lineitem.parquet\" where l_orderkey in \n"
            + " (select o_orderkey from cp.\"tpch/orders.parquet\" where o_custkey = 2) \n"
            + " and l_suppkey = 4 group by l_suppkey";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("l_suppkey", "avg_price")
        .baselineValues(4, 1374.47)
        .build()
        .run();
  }

  @Test // DRILL-1888
  public void testAggExpressionWithGroupByHaving() throws Exception {
    String query =
        "select l_suppkey, sum(l_extendedprice)/sum(l_quantity) as avg_price \n"
            + " from cp.\"tpch/lineitem.parquet\" where l_orderkey in \n"
            + " (select o_orderkey from cp.\"tpch/orders.parquet\" where o_custkey = 2) \n"
            + " group by l_suppkey having sum(l_extendedprice)/sum(l_quantity) > 1850.0";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("l_suppkey", "avg_price")
        .baselineValues(98, 1854.95)
        .build()
        .run();
  }

  @Test
  public void testExchangeRemoveForJoinPlan() throws Exception {
    String sql =
        String.format(
            "select t2.n_nationkey from dfs.\"%s/tpchmulti/region\" t1 join dfs.\"%s/tpchmulti/nation\" t2 on t2.n_regionkey = t1.r_regionkey",
            TEST_RES_PATH, TEST_RES_PATH);

    testBuilder()
        .unOrdered()
        .optionSettingQueriesForTestQuery(
            "alter session set \"planner.slice_target\" = 10; alter session set \"planner.join.row_count_estimate_factor\" = 0.1") // Enforce exchange will be inserted.
        .sqlQuery(sql)
        .optionSettingQueriesForBaseline(
            "alter session set \"planner.slice_target\" = 100000; alter session set \"planner.join.row_count_estimate_factor\" = 1.0") // Use default option setting.
        .sqlBaselineQuery(sql)
        .build()
        .run();
  }

  @Test // DRILL-2163
  public void testNestedTypesPastJoinReportsValidResult() throws Exception {
    final String query =
        "select t1.uid, t1.events, t1.events[0].evnt_id as event_id, t2.transactions, "
            + "t2.transactions[0] as trans, t1.odd, t2.even from cp.\"project/complex/a.json\" t1, "
            + "cp.\"project/complex/b.json\" t2 where t1.uid = t2.uid";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .jsonBaselineFile("project/complex/drill-2163-result.json")
        .build()
        .run();
  }

  @Test
  public void testSimilar() throws Exception {
    String query =
        "select n_nationkey "
            + "from cp.\"tpch/nation.parquet\" "
            + "where n_name similar to 'CHINA' "
            + "order by n_regionkey";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .optionSettingQueriesForTestQuery("alter session set \"planner.slice_target\" = 1")
        .baselineColumns("n_nationkey")
        .baselineValues(18)
        .go();

    test("alter session set \"planner.slice_target\" = " + ExecConstants.SLICE_TARGET_DEFAULT);
  }

  @Test // DRILL-2311
  @Ignore("Move to TestParquetWriter. Have to ensure same file name does not exist on filesystem.")
  public void testCreateTableSameColumnNames() throws Exception {
    String creatTable =
        "CREATE TABLE CaseInsensitiveColumnNames as "
            + "select cast(r_regionkey as BIGINT) BIGINT_col, cast(r_regionkey as DECIMAL) bigint_col \n"
            + "FROM cp.\"tpch/region.parquet\";\n";

    test("USE dfs_test");
    test(creatTable);

    testBuilder()
        .sqlQuery("select * from \"CaseInsensitiveColumnNames\"")
        .unOrdered()
        .baselineColumns("BIGINT_col", "bigint_col0\n")
        .baselineValues((long) 0, new BigDecimal(0))
        .baselineValues((long) 1, new BigDecimal(1))
        .baselineValues((long) 2, new BigDecimal(2))
        .baselineValues((long) 3, new BigDecimal(3))
        .baselineValues((long) 4, new BigDecimal(4))
        .build()
        .run();
  }

  @Test // DRILL-1943, DRILL-1911
  public void testColumnNamesDifferInCaseOnly() throws Exception {
    testBuilder()
        .sqlQuery("select r_regionkey a, r_regionkey A FROM cp.\"tpch/region.parquet\"")
        .unOrdered()
        .baselineColumns("a", "A0")
        .baselineValues(0, 0)
        .baselineValues(1, 1)
        .baselineValues(2, 2)
        .baselineValues(3, 3)
        .baselineValues(4, 4)
        .build()
        .run();

    testBuilder()
        .sqlQuery("select employee_id, Employee_id from cp.\"employee.json\" limit 2")
        .unOrdered()
        .baselineColumns("employee_id", "Employee_id0")
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 2, (long) 2)
        .build()
        .run();
  }

  @Test
  public void testBadQuerySyntax() throws Exception {
    try {
      testBuilder()
          .sqlQuery("select employee_id from cp.\"employee.json\" where employee_id = (90, 100)")
          .unOrdered()
          .baselineColumns("employee_id")
          .baselineValues(100)
          .build()
          .run();
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Cannot apply '=' to arguments of type '<BIGINT> = <RECORDTYPE(INTEGER EXPR$0, INTEGER EXPR$1)"));
    }
  }

  @Test // DX-15425: GreenPlum query
  public void testQueryWithoutFrom() throws Exception {
    try {
      testBuilder()
          .sqlQuery("select 1 union (select distinct CAST(null AS INTEGER) union select '10')")
          .unOrdered()
          .baselineColumns("EXPR$0")
          .baselineValues(1)
          .baselineValues(10)
          .baselineValues(null)
          .build()
          .run();
    } catch (Exception e) {
      assertTrue(
          e.getMessage().contains("Conversion to relational algebra failed to preserve datatypes"));
    }
  }

  @Test // DX-15425: GreenPlum query
  public void testQuery1WithoutFrom() throws Exception {
    testBuilder()
        .sqlQuery("select 1 union (select distinct CAST(null AS INTEGER) union select 10)")
        .unOrdered()
        .baselineColumns("EXPR$0")
        .baselineValues(1)
        .baselineValues(10)
        .baselineValues(null)
        .build()
        .run();
  }

  @Test // DX-15425: GreenPlum query
  public void testQuery2WithoutFrom() throws Exception {
    testBuilder()
        .sqlQuery(
            "select 1 union (select distinct '10' from (select 1, 3.0 union select distinct 2, CAST(null AS INTEGER)) as foo)")
        .unOrdered()
        .baselineColumns("EXPR$0")
        .baselineValues("10")
        .baselineValues("1")
        .build()
        .run();
  }

  @Test // DRILL-2094
  public void testOrderbyArrayElement() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/store/json/orderByArrayElement.json")
            .toURI()
            .getPath()
            .toString();

    String query =
        String.format(
            "select t.id, t.list[0] as SortingElem " + "from dfs.\"%s\" t " + "order by t.list[0]",
            root);

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("id", "SortingElem")
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 5, (long) 2)
        .baselineValues((long) 4, (long) 3)
        .baselineValues((long) 2, (long) 5)
        .baselineValues((long) 3, (long) 6)
        .build()
        .run();
  }

  @Test // DRILL-2479
  public void testCorrelatedExistsWithInSubq() throws Exception {
    String query =
        "select count(*) as cnt from cp.\"tpch/lineitem.parquet\" l where exists "
            + " (select ps.ps_suppkey from cp.\"tpch/partsupp.parquet\" ps where ps.ps_suppkey = l.l_suppkey and ps.ps_partkey "
            + " in (select p.p_partkey from cp.\"tpch/part.parquet\" p where p.p_type like '%NICKEL'))";

    testBuilder().sqlQuery(query).unOrdered().baselineColumns("cnt").baselineValues(60175L).go();
  }

  @Test // DRILL-2094
  public void testOrderbyArrayElementInSubquery() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/store/json/orderByArrayElement.json")
            .toURI()
            .getPath()
            .toString();

    String query =
        String.format(
            "select s.id from \n" + "(select id \n" + "from dfs.\"%s\" \n" + "order by list[0]) s",
            root);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("id")
        .baselineValues((long) 1)
        .baselineValues((long) 5)
        .baselineValues((long) 4)
        .baselineValues((long) 2)
        .baselineValues((long) 3)
        .build()
        .run();
  }

  @Test // DRILL-1978
  public void testCTASOrderByCoumnNotInSelectClause() throws Exception {
    System.out.println(getDfsTestTmpSchemaLocation());
    String root =
        FileUtils.getResourceAsFile("/store/text/data/regions.csv").toURI().getPath().toString();
    String queryCTAS1 =
        "CREATE TABLE TestExampleQueries_testCTASOrderByCoumnNotInSelectClause1 as "
            + "select r_name from cp.\"tpch/region.parquet\" order by r_regionkey;";

    String queryCTAS2 =
        "CREATE TABLE TestExampleQueries_testCTASOrderByCoumnNotInSelectClause2 as "
            + "select r_name, r_regionkey as rkey, cast( 1 as double) from cp.\"tpch/region.parquet\" order by 1;";

    String queryCTAS3 =
        String.format(
            "CREATE TABLE TestExampleQueries_testCTASOrderByCoumnNotInSelectClause3 as "
                + "SELECT columns[1] as col FROM dfs.\"%s\" ORDER BY cast(columns[0] as double)",
            root);

    String queryCTAS4 =
        String.format(
            "CREATE TABLE TestExampleQueries_testCTASOrderByCoumnNotInSelectClause4 as "
                + "SELECT columns[0] as col0, columns[1] as col1 FROM dfs.\"%s\" ORDER BY cast(columns[0] as double)",
            root);

    String query1 = "select * from TestExampleQueries_testCTASOrderByCoumnNotInSelectClause1";
    String query2 = "select * from TestExampleQueries_testCTASOrderByCoumnNotInSelectClause2";
    String query3 = "select * from TestExampleQueries_testCTASOrderByCoumnNotInSelectClause3";
    String query4 = "select * from TestExampleQueries_testCTASOrderByCoumnNotInSelectClause4";

    test("use dfs_test");
    test(queryCTAS1);
    test(queryCTAS2);
    test(queryCTAS3);
    test(queryCTAS4);

    testBuilder()
        .sqlQuery(query1)
        .ordered()
        .baselineColumns("r_name")
        .baselineValues("AFRICA")
        .baselineValues("AMERICA")
        .baselineValues("ASIA")
        .baselineValues("EUROPE")
        .baselineValues("MIDDLE EAST")
        .build()
        .run();

    testBuilder()
        .sqlQuery(query2)
        .ordered()
        .baselineColumns("EXPR$2", "r_name", "rkey")
        .baselineValues((Double) 1.0, "AFRICA", 0)
        .baselineValues((Double) 1.0, "AMERICA", 1)
        .baselineValues((Double) 1.0, "ASIA", 2)
        .baselineValues((Double) 1.0, "EUROPE", 3)
        .baselineValues((Double) 1.0, "MIDDLE EAST", 4)
        .build()
        .run();

    testBuilder()
        .sqlQuery(query3)
        .ordered()
        .baselineColumns("col")
        .baselineValues("AFRICA")
        .baselineValues("AMERICA")
        .baselineValues("ASIA")
        .baselineValues("EUROPE")
        .baselineValues("MIDDLE EAST")
        .build()
        .run();

    testBuilder()
        .sqlQuery(query4)
        .ordered()
        .baselineColumns("col0", "col1")
        .baselineValues("0", "AFRICA")
        .baselineValues("1", "AMERICA")
        .baselineValues("2", "ASIA")
        .baselineValues("3", "EUROPE")
        .baselineValues("4", "MIDDLE EAST")
        .build()
        .run();
  }

  @Test // DRILL-2221
  public void createJsonWithEmptyList() throws Exception {
    final String file =
        FileUtils.getResourceAsFile("/store/json/record_with_empty_list.json")
            .toURI()
            .getPath()
            .toString();
    final String tableName = "jsonWithEmptyList";
    test("USE dfs_test");
    test("ALTER SESSION SET \"store.format\"='json'");
    test(String.format("CREATE TABLE %s AS SELECT * FROM dfs.\"%s\"", tableName, file));
    test(String.format("SELECT COUNT(*) FROM %s", tableName));
    test("ALTER SESSION SET \"store.format\"='parquet'");
  }

  @Test // DRILL-2914
  public void testGroupByStarSchemaless() throws Exception {
    String query =
        "SELECT n.n_nationkey AS col \n"
            + "FROM (SELECT * FROM cp.\"tpch/nation.parquet\") AS n \n"
            + "GROUP BY n.n_nationkey \n"
            + "ORDER BY n.n_nationkey";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .csvBaselineFile("testframework/testExampleQueries/testGroupByStarSchemaless.tsv")
        .baselineTypes(MinorType.INT)
        .baselineColumns("col")
        .build()
        .run();
  }

  @Test // DRILL-1927
  public void testGroupByCaseInSubquery1() throws Exception {
    String query1 =
        "select (case when t.r_regionkey in (3) then 0 else 1 end) as col \n"
            + "from cp.\"tpch/region.parquet\" t \n"
            + "group by (case when t.r_regionkey in (3) then 0 else 1 end)";

    testBuilder()
        .sqlQuery(query1)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(0)
        .baselineValues(1)
        .build()
        .run();
  }

  @Test // DRILL-1927
  public void testGroupByCaseInSubquery2() throws Exception {
    String query2 =
        "select sum(case when t.r_regionkey in (3) then 0 else 1 end) as col \n"
            + "from cp.\"tpch/region.parquet\" t";

    testBuilder()
        .sqlQuery(query2)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues((long) 4)
        .build()
        .run();
  }

  @Test // DRILL-1927
  public void testGroupByCaseInSubquery3() throws Exception {
    String query3 =
        "select (case when (r_regionkey IN (0, 2, 3, 4)) then 0 else r_regionkey end) as col1, min(r_regionkey) as col2 \n"
            + "from cp.\"tpch/region.parquet\" \n"
            + "group by (case when (r_regionkey IN (0, 2, 3, 4)) then 0 else r_regionkey end)";

    testBuilder()
        .sqlQuery(query3)
        .unOrdered()
        .baselineColumns("col1", "col2")
        .baselineValues(0, 0)
        .baselineValues(1, 1)
        .build()
        .run();
  }

  @Test // DRILL-2966
  public void testHavingAggFunction() throws Exception {
    String query1 =
        "select n_nationkey as col \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "group by n_nationkey \n"
            + "having sum(case when n_regionkey in (1, 2) then 1 else 0 end) + \n"
            + "sum(case when n_regionkey in (2, 3) then 1 else 0 end) > 1";

    String query2 =
        "select n_nationkey as col \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "group by n_nationkey \n"
            + "having n_nationkey in \n"
            + "(select r_regionkey \n"
            + "from cp.\"tpch/region.parquet\" \n"
            + "group by r_regionkey \n"
            + "having sum(r_regionkey) > 0)";

    String query3 =
        "select n_nationkey as col \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "group by n_nationkey \n"
            + "having max(n_regionkey) > ((select min(r_regionkey) from cp.\"tpch/region.parquet\") + 3)";

    //    testBuilder()
    //        .sqlQuery(query1)
    //        .unOrdered()
    //        .csvBaselineFile("testframework/testExampleQueries/testHavingAggFunction/q1.tsv")
    //        .baselineTypes(MinorType.INT)
    //        .baselineColumns("col")
    //        .build()
    //        .run();

    testBuilder()
        .sqlQuery(query2)
        .unOrdered()
        .csvBaselineFile("testframework/testExampleQueries/testHavingAggFunction/q2.tsv")
        .baselineTypes(MinorType.INT)
        .baselineColumns("col")
        .build()
        .run();
    /*

    testBuilder()
        .sqlQuery(query3)
        .unOrdered()
        .csvBaselineFile("testframework/testExampleQueries/testHavingAggFunction/q3.tsv")
        .baselineTypes(MinorType.INT)
        .baselineColumns("col")
        .build()
        .run();
        */
  }

  @Test // DRILL-3018
  public void testNestLoopJoinScalarSubQ() throws Exception {
    testBuilder()
        .sqlQuery(
            "select n_nationkey from cp.\"tpch/nation.parquet\" where n_nationkey >= (select min(c_nationkey) from cp.\"tpch/customer.parquet\")")
        .unOrdered()
        .sqlBaselineQuery("select n_nationkey from cp.\"tpch/nation.parquet\"")
        .build()
        .run();
  }

  @Test // DRILL-2953
  public void testGbAndObDifferentExp() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/store/text/data/nations.csv").toURI().getPath().toString();
    String query =
        String.format(
            "select cast(columns[0] as int) as nation_key "
                + " from dfs.\"%s\" "
                + " group by columns[0] "
                + " order by cast(columns[0] as int)",
            root);

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .csvBaselineFile("testframework/testExampleQueries/testGroupByStarSchemaless.tsv")
        .baselineTypes(MinorType.INT)
        .baselineColumns("nation_key")
        .build()
        .run();

    String query2 =
        String.format(
            "select cast(columns[0] as int) as nation_key "
                + " from dfs.\"%s\" "
                + " group by cast(columns[0] as int) "
                + " order by cast(columns[0] as int)",
            root);

    testBuilder()
        .sqlQuery(query2)
        .ordered()
        .csvBaselineFile("testframework/testExampleQueries/testGroupByStarSchemaless.tsv")
        .baselineTypes(MinorType.INT)
        .baselineColumns("nation_key")
        .build()
        .run();
  }

  @Test // DRILL_3004
  public void testDRILL_3004() throws Exception {
    final String query =
        "SELECT\n"
            + "  nations.N_NAME,\n"
            + "  regions.R_NAME\n"
            + "FROM\n"
            + "  cp.\"tpch/nation.parquet\" nations\n"
            + "JOIN\n"
            + "  cp.\"tpch/region.parquet\" regions\n"
            + "on nations.N_REGIONKEY = regions.R_REGIONKEY "
            + "where 1 = 0";

    testBuilder()
        .sqlQuery(query)
        .expectsEmptyResultSet()
        .optionSettingQueriesForTestQuery(
            "ALTER SESSION SET \"planner.enable_hashjoin\" = false; "
                + "ALTER SESSION SET \"planner.disable_exchanges\" = true; ALTER SESSION SET \"planner.enable_mergejoin\" = true")
        .build()
        .run();
  }

  @Test
  public void testRepeatedListProjectionPastJoin() throws Exception {
    final String query =
        "select * from cp.\"join/join-left-drill-3032.json\" f1 inner join cp.\"join/join-right-drill-3032.json\" f2 on f1.id = f2.id";
    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("id", "id0", "aaa")
        .baselineValues(1L, 1L, listOf(listOf(listOf("val1"), listOf("val2"))))
        .go();
  }

  @Test
  @Ignore
  public void testPartitionCTAS() throws Exception {
    test(
        "use dfs_test; "
            + "create table mytable1  partition by (r_regionkey, r_comment) as select r_regionkey, r_name, r_comment from cp.\"tpch/region.parquet\"");

    test(
        "use dfs_test; "
            + "create table mytable2  partition by (r_regionkey, r_comment) as select * from cp.\"tpch/region.parquet\" where r_name = 'abc' ");

    test(
        "use dfs_test; "
            + "create table mytable3  partition by (r_regionkey, n_nationkey) as "
            + "  select r.r_regionkey, r.r_name, n.n_nationkey, n.n_name from cp.\"tpch/nation.parquet\" n, cp.\"tpch/region.parquet\" r "
            + "  where n.n_regionkey = r.r_regionkey");

    test(
        "use dfs_test; "
            + "create table mytable4  partition by (r_regionkey, r_comment) as "
            + "  select  r.* from cp.\"tpch/nation.parquet\" n, cp.\"tpch/region.parquet\" r "
            + "  where n.n_regionkey = r.r_regionkey");
  }

  @Test // DRILL-3210
  public void testWindowFunAndStarCol() throws Exception {
    // SingleTableQuery : star + window function
    final String query =
        " select * , sum(n_nationkey) over (partition by n_regionkey) as sumwin "
            + " from cp.\"tpch/nation.parquet\"";
    final String baseQuery =
        " select n_nationkey, n_name, n_regionkey, n_comment, "
            + "   sum(n_nationkey) over (partition by n_regionkey) as sumwin "
            + " from cp.\"tpch/nation.parquet\"";
    testBuilder().sqlQuery(query).unOrdered().sqlBaselineQuery(baseQuery).build().run();

    // JoinQuery: star + window function
    final String joinQuery =
        " select *, sum(n.n_nationkey) over (partition by r.r_regionkey order by r.r_name) as sumwin"
            + " from cp.\"tpch/nation.parquet\" n, cp.\"tpch/region.parquet\" r "
            + " where n.n_regionkey = r.r_regionkey";
    final String joinBaseQuery =
        " select n.n_nationkey, n.n_name, n.n_regionkey, n.n_comment, r.r_regionkey, r.r_name, r.r_comment, "
            + "   sum(n.n_nationkey) over (partition by r.r_regionkey order by r.r_name) as sumwin "
            + " from cp.\"tpch/nation.parquet\" n, cp.\"tpch/region.parquet\" r "
            + " where n.n_regionkey = r.r_regionkey";

    testBuilder().sqlQuery(joinQuery).unOrdered().sqlBaselineQuery(joinBaseQuery).build().run();
  }

  @Test // see DRILL-3557
  public void testEmptyCSVinDirectory() throws Exception {
    final String root =
        FileUtils.getResourceAsFile("/store/text/directoryWithEmpyCSV")
            .toURI()
            .getPath()
            .toString();
    final String toFile =
        FileUtils.getResourceAsFile("/store/text/directoryWithEmpyCSV/empty.csv")
            .toURI()
            .getPath()
            .toString();

    String query1 = String.format("explain plan for select * from dfs.\"%s\"", root);
    String query2 = String.format("explain plan for select * from dfs.\"%s\"", toFile);
    assertThatThrownBy(() -> test(query1))
        .isInstanceOf(UserRemoteException.class)
        .hasMessageContaining("DATA_READ ERROR: Selected table has no columns.");
    assertThatThrownBy(() -> test(query2))
        .isInstanceOf(UserRemoteException.class)
        .hasMessageContaining("DATA_READ ERROR: Selected table has no columns.");
  }

  @Test
  public void testNegativeExtractOperator() throws Exception {
    String query =
        "select -EXTRACT(DAY FROM cast(birth_date as DATE)) as col \n"
            + "from cp.\"employee.json\" \n"
            + "order by col \n"
            + "limit 5";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("col")
        .baselineValues(-27L)
        .baselineValues(-27L)
        .baselineValues(-27L)
        .baselineValues(-26L)
        .baselineValues(-26L)
        .build()
        .run();
  }

  @Test // see DRILL-2313
  public void testDistinctOverAggFunctionWithGroupBy() throws Exception {
    String query1 =
        "select distinct count(distinct n_nationkey) as col from cp.\"tpch/nation.parquet\" group by n_regionkey order by 1";
    String query2 =
        "select distinct count(distinct n_nationkey) as col from cp.\"tpch/nation.parquet\" group by n_regionkey order by count(distinct n_nationkey)";
    String query3 =
        "select distinct sum(n_nationkey) as col from cp.\"tpch/nation.parquet\" group by n_regionkey order by 1";
    String query4 =
        "select distinct sum(n_nationkey) as col from cp.\"tpch/nation.parquet\" group by n_regionkey order by col";

    testBuilder()
        .sqlQuery(query1)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues((long) 5)
        .build()
        .run();

    testBuilder()
        .sqlQuery(query2)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues((long) 5)
        .build()
        .run();

    testBuilder()
        .sqlQuery(query3)
        .ordered()
        .baselineColumns("col")
        .baselineValues((long) 47)
        .baselineValues((long) 50)
        .baselineValues((long) 58)
        .baselineValues((long) 68)
        .baselineValues((long) 77)
        .build()
        .run();

    testBuilder()
        .sqlQuery(query4)
        .ordered()
        .baselineColumns("col")
        .baselineValues((long) 47)
        .baselineValues((long) 50)
        .baselineValues((long) 58)
        .baselineValues((long) 68)
        .baselineValues((long) 77)
        .build()
        .run();
  }

  @Test // DRILL-2190
  public void testDateImplicitCasting() throws Exception {
    String query =
        "SELECT birth_date \n"
            + "FROM cp.\"employee.json\" \n"
            + "WHERE birth_date BETWEEN '1920-01-01' AND cast('1931-01-01' AS DATE) \n"
            + "order by birth_date";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("birth_date")
        .baselineValues("1920-04-17")
        .baselineValues("1921-12-04")
        .baselineValues("1922-08-10")
        .baselineValues("1926-10-27")
        .baselineValues("1928-03-20")
        .baselineValues("1930-01-08")
        .build()
        .run();
  }

  @Test /* DX-9921, DX-9914 */
  public void testLargeConcat() throws Exception {
    final String largeString =
        "abcdefjssjdbsjbsbsbhbhbchbhbchbchbchdbhbchbshshchchchchdbchdbchdbchdchhchdcjncsjchbhsbhbshsbchsbhsbhsbxhsbxhsbhsbhbhbhbhbhbhbhbhbhbhcbshcbshcbshcbhcbscjbcdbchdbchdbchdbchdbhbhbhbchbdhcbdhbchdbchdbncjdncjndjncjdncjdcjdncjdcnkkdncndjcndjncjdncjdcdjnjndjnccjdn";
    String query =
        "SELECT concat(first_name, 'abcdefjssjdbsjbsbsbhbhbchbhbchbchbchdbhbchbshshchchchchdbchdbchdbchdchhchdcjncsjchbhsbhbshsbchsbhsbhsbxhsbxhsbhsbhbhbhbhbhbhbhbhbhbhcbshcbshcbshcbhcbscjbcdbchdbchdbchdbchdbhbhbhbchbdhcbdhbchdbchdbncjdncjndjncjdncjdcjdncjdcnkkdncndjcndjncjdncjdcdjnjndjnccjdn') FROM cp.\"employees.json\"";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("EXPR$0")
        .baselineValues("Steve" + largeString)
        .baselineValues("Mary" + largeString)
        .baselineValues("Leo" + largeString)
        .baselineValues("Nancy" + largeString)
        .baselineValues("Clara" + largeString)
        .baselineValues("Marcella" + largeString)
        .baselineValues("Charlotte" + largeString)
        .baselineValues("Benjamin" + largeString)
        .baselineValues("John" + largeString)
        .baselineValues("Lynn" + largeString)
        .baselineValues("Donald" + largeString)
        .baselineValues("William" + largeString)
        .baselineValues("Amy" + largeString)
        .baselineValues("Judy" + largeString)
        .baselineValues("Frederick" + largeString)
        .baselineValues("Phil" + largeString)
        .baselineValues("Lori" + largeString)
        .baselineValues("Anil" + largeString)
        .baselineValues("Bh" + largeString)
        .build()
        .run();
  }

  @Test // DX-11559
  public void testValuesPrelParallelization() throws Exception {
    // test preview query
    testNoResult("set planner.leaf_limit_enable = true");

    // allow parallelization
    testNoResult("set planner.width.max_per_node = 10");
    testNoResult("set planner.width.max_per_query = 10");
    testNoResult("set planner.slice_target = 1");

    final List<QueryDataBatch> results =
        testSqlWithResults("SELECT FLATTEN(MAPPIFY(CONVERT_FROM('{\"1\":3,\"2\":4}', 'JSON')))");
    try {
      int numRows =
          results.stream()
              .map(q -> q.getHeader().getRowCount())
              .reduce((first, second) -> first + second)
              .get();
      Assert.assertEquals(numRows, 2);
    } finally {
      for (QueryDataBatch batch : results) {
        batch.close();
      }
    }
  }

  @Test // DX-13843
  public void testGreenPlumQuery() throws Exception {
    testBuilder()
        .sqlQuery(
            "select (select count(*) from (values (1)) t0(inner_c)) from (values (2),(3)) t1(outer_c)")
        .unOrdered()
        .baselineColumns("EXPR$0")
        .baselineValues(1L)
        .baselineValues(1L)
        .build()
        .run();
  }

  /**
   * This test case tickled a scenario where we failed to support a join because of an issue where
   * we were failing to introduce the correct abstract converters. Changes to make it work were done
   * as an ordered enhancement to Prule.
   *
   * <p>See DX-17835 for further details
   */
  @Test
  public void ensureThatStackedConversionsWork() throws Exception {
    try (AutoCloseable c = withOption(PlannerSettings.NLJOIN_FOR_SCALAR, false)) {

      test(
          "create table dfs_test.zip as "
              + "select city, loc, pop, state, CAST(\"_id\" as INT) as \"id\"\n"
              + "FROM (\n"
              + "   select * from cp.\"/sample/samples-samples-dremio-com-zips-json.json\" limit 10\n"
              + ") nested_0");

      test(
          "create table dfs_test.lookup as "
              + "select\n"
              + "   case\n"
              + "       when A='id' then 0\n"
              + "       when A='A' then 0\n"
              + "       else CAST(A as INT)\n"
              + "   end as id,\n"
              + "   B, C, D\n"
              + "FROM (\n"
              + "   select columns[0] as A, columns[1] as B, columns[2] as C, columns[3] as D from cp.\"/sample/samples-samples-dremio-com-zip_lookup-csv.csv\" limit 10\n"
              + ") nested_0");

      // test preview query
      testNoResult("set planner.leaf_limit_enable = true");

      // allow parallelization
      testNoResult("set planner.width.max_per_node = 10");
      testNoResult("set planner.width.max_per_query = 10");
      testNoResult("set planner.slice_target = 1");

      test(
          "SELECT nested_2.city AS city, nested_2.pop AS pop, nested_2.state AS state, nested_2.Count_Star AS Count_Star, nested_2.Sum_pop AS Sum_pop, nested_2.newID AS newID, join_lookup.id AS id, join_lookup.B AS B, join_lookup.C AS C, join_lookup.D AS D\n"
              + "FROM (\n"
              + "SELECT newID, city, pop, state, COUNT(*) AS Count_Star, SUM(pop) AS Sum_pop\n"
              + "FROM (\n"
              + "SELECT city, loc, pop, state, \"id\" AS newID\n"
              + "FROM (\n"
              + "SELECT city, loc, pop, state, id\n"
              + "FROM dfs_test.zip\n"
              + ") nested_0\n"
              + ") nested_1\n"
              + "GROUP BY newID, city, pop, state\n"
              + ") nested_2\n"
              + "INNER JOIN dfs_test.lookup AS join_lookup ON nested_2.newID < join_lookup.id\n");
    }
  }

  @Test
  public void testLeftJoinNoAlias() throws Exception {
    test(
        "select * \n"
            + "  from cp.\"tpch/lineitem.parquet\" \n"
            + "    left outer join cp.\"tpch/customer.parquet\" c \n"
            + "      on \"tpch/lineitem.parquet\".l_orderkey = c.c_custkey\n");
  }

  @Test
  public void testLeftSubstring() throws Exception {
    testBuilder()
        .sqlQuery("select left('1234', 2) as l")
        .unOrdered()
        .baselineColumns("l")
        .baselineValues("12")
        .build()
        .run();
  }

  @Test // DX-27938
  public void testSpacedBooleanCast() throws Exception {
    String query =
        "select cast(CAST('    true   ' AS VARCHAR) as boolean) as \"true\", Cast(CAST('     false' AS VARCHAR) as boolean) as \"false\"";
    test(query);
  }

  @Test // DX-27938
  public void testSpacedBooleanCast2() {
    String query =
        "select cast(CAST('    tru  e   ' AS VARCHAR) as boolean) as \"true\", Cast(CAST('     fal s e' AS VARCHAR) as boolean) as \"false\"";
    UserExceptionAssert.assertThatThrownBy(() -> test(query))
        .hasErrorType(FUNCTION)
        .hasMessageContaining("FUNCTION ERROR: Invalid value for boolean: 'tru  e'");
  }

  @Test
  public void testBooleanIntegerEquality() throws Exception {
    final String query = "SELECT * FROM cp.\"boolTypes.parquet\" t where t.bVal = 1";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("id", "bVal")
        .baselineValues(1L, true)
        .baselineValues(2L, true)
        .build()
        .run();
  }

  @Test // DX-27940
  public void testShortTimeCasting() throws Exception {
    String query = "select CAST(\'00:00\' AS time) res1 FROM (values (\'00:00:00\'))";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("res1")
        .baselineValues(ts("1970-01-01T00:00:00.000"))
        .build()
        .run();
  }

  @Test // DX-34706
  public void testIsNotTrueInt() throws Exception {
    String query =
        "SELECT\n"
            + "  \"L\".\"PARTID\" AS \"C0\"\n"
            + "FROM\n"
            + "  (VALUES\n"
            + "  (1\n"
            + "  , 'A'\n"
            + "  , -1\n"
            + "  )\n"
            + ", (2, 'B', 1)\n"
            + ", (3, 'C', 1)\n"
            + ", (4, 'D', 1)\n"
            + ", (5, 'E', 3)\n"
            + ", (6, 'F', 4)\n"
            + ", (7, 'G', 6)) \"L\"(\"PARTID\", \"PARTNAME\", \"PARENTPART\")\n"
            + "WHERE\n"
            + "  NOT\n"
            + "  (\n"
            + "    \"L\".\"PARTID\" IN\n"
            + "    (\n"
            + "      SELECT\n"
            + "        \"PARTID\"\n"
            + "      FROM\n"
            + "        (\n"
            + "          SELECT\n"
            + "            \"PARTID\"\n"
            + "          , \"PARTNAME\"\n"
            + "          , \"PARENTPART\"\n"
            + "          FROM\n"
            + "            ( VALUES\n"
            + "            (1\n"
            + "            , 'A'\n"
            + "            , -1\n"
            + "            )\n"
            + "          , (3, 'C', 1)\n"
            + "          , (4, 'D', 1)\n"
            + "          , (6, 'F', 4)\n"
            + "          , (7, 'G', 6) ) \"D1\" (\"PARTID\", \"PARTNAME\", \"PARENTPART\")\n"
            + "        )\n"
            + "        \"PART\"(\"PARTID\", \"PARTNAME\", \"PARENTPART\")\n"
            + "    )\n"
            + "  )\n"
            + "ORDER BY\n"
            + "  \"C0\" ASC NULLS LAST";

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("C0")
        .baselineValues(2)
        .baselineValues(5)
        .build()
        .run();
  }

  @Test
  public void testCopier1() throws Exception {
    String query =
        "SELECT * FROM cp.\"json/30717-1.json\" where id = 'keyA' and (TIMESTAMPADD(SQL_TSI_DAY, 5, CAST(date_varchar as DATE)) >= CAST('2004-03-14' AS TIMESTAMP) AND TIMESTAMPADD(SQL_TSI_MONTH, 1, CAST(date_varchar as DATE)) <= CAST('2021-04-12' AS TIMESTAMP))";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .jsonBaselineFile("json/30717-1-result.json")
        .build()
        .run();
  }

  @Test
  public void testCopier2() throws Exception {
    String query =
        "SELECT * FROM cp.\"parquet/30717-2.parquet\" where varchar_col='Doe' or varchar_col is null";

    testBuilder()
        .unOrdered()
        .optionSettingQueriesForTestQuery(
            "alter system set \"exec.operator.copier.complex.vectorize\" = true")
        .sqlQuery(query)
        .optionSettingQueriesForBaseline(
            "alter system set \"exec.operator.copier.complex.vectorize\" = false")
        .sqlBaselineQuery(query)
        .build()
        .run();
  }

  @Test
  public void testCopier3() throws Exception {
    String query =
        "SELECT * FROM cp.\"parquet/30717-3.parquet\" t where varchar_col='Doe' or t.\"structOfStruct\".\"struct\".\"string\" = 'row3' or t.\"structOfStructOfStruct\".\"structOfStruct\".\"struct\".\"string\" = 'row2'";

    testBuilder()
        .unOrdered()
        .optionSettingQueriesForTestQuery(
            "alter system set \"exec.operator.copier.complex.vectorize\" = true")
        .sqlQuery(query)
        .optionSettingQueriesForBaseline(
            "alter system set \"exec.operator.copier.complex.vectorize\" = false")
        .sqlBaselineQuery(query)
        .build()
        .run();
  }

  @Test
  public void testCopier4() throws Exception {
    String query = "SELECT * FROM cp.\"json/40598.json\" t where col1 = 'row1' or col1 = 'row2'";

    testBuilder()
        .unOrdered()
        .optionSettingQueriesForTestQuery(
            "alter system set \"exec.operator.copier.complex.vectorize\" = true")
        .sqlQuery(query)
        .optionSettingQueriesForBaseline(
            "alter system set \"exec.operator.copier.complex.vectorize\" = false")
        .sqlBaselineQuery(query)
        .build()
        .run();
  }

  @Test
  public void testCopier5() throws Exception {
    String query =
        "SELECT * FROM cp.\"parquet/52864-1.parquet\" t where id0 = '2' or id0 = '4' or id0 = '6' or id0 = '7' or id0 = '8'";

    testBuilder()
        .unOrdered()
        .optionSettingQueriesForTestQuery(
            "alter system set \"dremio.data_types.map.enabled\" = true")
        .optionSettingQueriesForTestQuery(
            "alter system set \"exec.operator.copier.complex.vectorize\" = true")
        .sqlQuery(query)
        .optionSettingQueriesForBaseline(
            "alter system set \"exec.operator.copier.complex.vectorize\" = false")
        .sqlBaselineQuery(query)
        .build()
        .run();
  }

  @Test // DX-60099
  public void TestCoalesceOnSameColJava() throws Exception {
    String query =
        "select\n"
            + "  COALESCE(amount_dollars,0) as c1\n"
            + " ,COALESCE(score,0)*amount_dollars as c2\n"
            + " ,COALESCE(amount_dollars,0) as c3\n"
            + "FROM cp.\"parquet/coalesce_same_col.parquet\"";
    testBuilder()
        .unOrdered()
        .optionSettingQueriesForTestQuery(
            "alter system set \"exec.preferred.codegenerator\" = 'java'")
        .sqlQuery(query)
        .baselineColumns("c1", "c2", "c3")
        .baselineValues(new BigDecimal("0.0"), null, new BigDecimal("0.0"))
        .baselineValues(new BigDecimal("0.0"), null, new BigDecimal("0.0"))
        .baselineValues(new BigDecimal("7000.0"), 575.7124242453957, new BigDecimal("7000.0"))
        .baselineValues(new BigDecimal("4000.0"), 627.8740198036597, new BigDecimal("4000.0"))
        .baselineValues(new BigDecimal("0.0"), null, new BigDecimal("0.0"))
        .build()
        .run();
  }

  @Test
  public void TestColLike() throws Exception {
    String query =
        "select "
            + "b.pat, col_like(a.term, replace(b.pat, '*', '%')) as c1 "
            + "from cp.\"parquet/like_test.parquet\" as a "
            + "JOIN cp.\"parquet/like_test_2.parquet\" as b "
            + "ON a.id = b.id";

    testBuilder()
        .unOrdered()
        .sqlQuery(query)
        .baselineColumns("pat", "c1")
        .baselineValues("*hp*", true)
        .baselineValues("*hp*", true)
        .baselineValues("*zinni*", true)
        .baselineValues("*rugged shark*", true)
        .build()
        .run();
  }
}
