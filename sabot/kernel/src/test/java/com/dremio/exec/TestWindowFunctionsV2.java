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
package com.dremio.exec;

import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.UNSUPPORTED_OPERATION;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.VALIDATION;
import static org.junit.Assert.assertTrue;

import com.dremio.BaseTestQuery;
import com.dremio.PlanTestBase;
import com.dremio.TestBuilder;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.util.FileUtils;
import com.dremio.common.util.TestTools;
import com.dremio.config.DremioConfig;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.dremio.exec.work.foreman.UnsupportedFunctionException;
import com.dremio.test.TemporarySystemProperties;
import com.dremio.test.UserExceptionAssert;
import java.math.BigDecimal;
import java.util.stream.IntStream;
import org.joda.time.LocalDateTime;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class TestWindowFunctionsV2 extends BaseTestQuery {
  static final String WORKING_PATH = TestTools.getWorkingPath();
  static final String TEST_RES_PATH = WORKING_PATH + "/src/test/resources";

  @Rule public TemporarySystemProperties properties = new TemporarySystemProperties();

  private static void throwAsUnsupportedException(UserException ex) throws Exception {
    SqlUnsupportedException.errorClassNameToException(
        ex.getOrCreatePBError(false).getException().getExceptionClass());
    throw ex;
  }

  @Test
  public void testSinglePartition() throws Exception {
    final String query =
        "select sum(n_nationKey) over(partition by n_nationKey) as col1, count(*) over(partition by n_nationKey) as col2 \n"
            + "from cp.\"tpch/nation.parquet\"";

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{0\\} order by \\[\\].*\\[COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\), COUNT\\(\\)",
      "TableFunction.*columns=\\[`n_nationkey`\\].*"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\].*"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2")
        .baselineValues(0L, 1L)
        .baselineValues(1L, 1L)
        .baselineValues(2L, 1L)
        .baselineValues(3L, 1L)
        .baselineValues(4L, 1L)
        .baselineValues(5L, 1L)
        .baselineValues(6L, 1L)
        .baselineValues(7L, 1L)
        .baselineValues(8L, 1L)
        .baselineValues(9L, 1L)
        .baselineValues(10L, 1L)
        .baselineValues(11L, 1L)
        .baselineValues(12L, 1L)
        .baselineValues(13L, 1L)
        .baselineValues(14L, 1L)
        .baselineValues(15L, 1L)
        .baselineValues(16L, 1L)
        .baselineValues(17L, 1L)
        .baselineValues(18L, 1L)
        .baselineValues(19L, 1L)
        .baselineValues(20L, 1L)
        .baselineValues(21L, 1L)
        .baselineValues(22L, 1L)
        .baselineValues(23L, 1L)
        .baselineValues(24L, 1L)
        .build()
        .run();
  }

  @Test
  public void testSinglePartitionDefinedInWindowList() throws Exception {
    final String query =
        "select sum(n_nationKey) over w as col \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "window w as (partition by n_nationKey order by n_nationKey)";

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{0\\} order by \\[0\\].*COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "TableFunction.*columns=\\[`n_nationkey`\\].*"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\].*"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(0L)
        .baselineValues(1L)
        .baselineValues(2L)
        .baselineValues(3L)
        .baselineValues(4L)
        .baselineValues(5L)
        .baselineValues(6L)
        .baselineValues(7L)
        .baselineValues(8L)
        .baselineValues(9L)
        .baselineValues(10L)
        .baselineValues(11L)
        .baselineValues(12L)
        .baselineValues(13L)
        .baselineValues(14L)
        .baselineValues(15L)
        .baselineValues(16L)
        .baselineValues(17L)
        .baselineValues(18L)
        .baselineValues(19L)
        .baselineValues(20L)
        .baselineValues(21L)
        .baselineValues(22L)
        .baselineValues(23L)
        .baselineValues(24L)
        .build()
        .run();
  }

  @Test(expected = UnsupportedFunctionException.class)
  public void testWindowFunctionWithDistinct() throws Exception {
    try {
      final String query =
          "explain plan for select n_regionkey, count(distinct n_nationkey) over(partition by n_regionkey) \n"
              + "from cp.\"tpch/nation.parquet\"";

      test(query);
    } catch (UserException ex) {
      throwAsUnsupportedException(ex);
      throw ex;
    }
  }

  @Test(expected = UnsupportedFunctionException.class)
  public void testWindowWithAllowDisallow() throws Exception {
    try {
      final String query =
          "select sum(n_nationKey) over(partition by n_nationKey \n"
              + "rows between unbounded preceding and unbounded following disallow partial) \n"
              + "from cp.\"tpch/nation.parquet\" \n"
              + "order by n_nationKey";

      test(query);
    } catch (UserException ex) {
      throwAsUnsupportedException(ex);
      throw ex;
    }
  }

  @Test
  public void testWindowInWindow() {
    String query =
        "select rank() over(order by row_number() over(order by n_nationkey)) \n"
            + "from cp.\"tpch/nation.parquet\"";
    UserExceptionAssert.assertThatThrownBy(() -> test(query)).hasErrorType(VALIDATION);
  }

  @Test
  public void testMissingOverWithWindowClause() {
    String query =
        "select rank(), cume_dist() over w \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "window w as (partition by n_name order by n_nationkey)";

    UserExceptionAssert.assertThatThrownBy(() -> test(query)).hasErrorType(VALIDATION);
  }

  @Test
  public void testLeadMissingOver() {
    String query = "select lead(n_nationkey) from cp.\"tpch/nation.parquet\"";
    UserExceptionAssert.assertThatThrownBy(() -> test(query)).hasErrorType(VALIDATION);
  }

  @Test
  public void testMissingOverWithConstant() {
    String query = "select NTILE(1) from cp.\"tpch/nation.parquet\"";
    UserExceptionAssert.assertThatThrownBy(() -> test(query)).hasErrorType(VALIDATION);
  }

  @Test
  public void testWindowGroupBy() {
    String query =
        "explain plan for SELECT max(n_nationkey) OVER (), n_name as col2 \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "group by n_name";
    UserExceptionAssert.assertThatThrownBy(() -> test(query)).hasErrorType(VALIDATION);
  }

  @Test
  public void testWindowGroupByOnView() throws Exception {
    try {
      properties.set(DremioConfig.LEGACY_STORE_VIEWS_ENABLED, "true");
      String createView =
          "create view testWindowGroupByOnView(a, b) as \n"
              + "select n_nationkey, n_name from cp.\"tpch/nation.parquet\"";
      String query =
          "explain plan for SELECT max(a) OVER (), b as col2 \n"
              + "from testWindowGroupByOnView \n"
              + "group by b";

      test("use dfs_test");
      test(createView);
      UserExceptionAssert.assertThatThrownBy(() -> test(query)).hasErrorType(VALIDATION);
    } finally {
      test("drop view testWindowGroupByOnView");
    }
  }

  @Test
  public void testWindowFrameEquivalentToDefault() throws Exception {
    final String query1 =
        "select sum(n_nationKey) over(partition by n_nationKey order by n_nationKey) as col\n"
            + "from cp.\"tpch/nation.parquet\" t \n"
            + "order by n_nationKey";

    final String query2 =
        "select sum(n_nationKey) over(partition by n_nationKey order by n_nationKey \n"
            + "range between unbounded preceding and current row) as col \n"
            + "from cp.\"tpch/nation.parquet\" t \n"
            + "order by n_nationKey";

    final String query3 =
        "select sum(n_nationKey) over(partition by n_nationKey \n"
            + "rows BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) as col \n"
            + "from cp.\"tpch/nation.parquet\" t \n"
            + "order by n_nationKey";

    // Validate the plan
    final String[] expectedPlan1 = {
      "Window.*partition \\{0\\} order by \\[0\\].*COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "TableFunction.*columns=\\[`n_nationkey`\\].*"
    };
    final String[] excludedPatterns1 = {"Scan.*columns=\\[`\\*`\\].*"};
    PlanTestBase.testPlanMatchingPatterns(query1, expectedPlan1, excludedPatterns1);

    testBuilder()
        .sqlQuery(query1)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(0L)
        .baselineValues(1L)
        .baselineValues(2L)
        .baselineValues(3L)
        .baselineValues(4L)
        .baselineValues(5L)
        .baselineValues(6L)
        .baselineValues(7L)
        .baselineValues(8L)
        .baselineValues(9L)
        .baselineValues(10L)
        .baselineValues(11L)
        .baselineValues(12L)
        .baselineValues(13L)
        .baselineValues(14L)
        .baselineValues(15L)
        .baselineValues(16L)
        .baselineValues(17L)
        .baselineValues(18L)
        .baselineValues(19L)
        .baselineValues(20L)
        .baselineValues(21L)
        .baselineValues(22L)
        .baselineValues(23L)
        .baselineValues(24L)
        .build()
        .run();

    final String[] expectedPlan2 = {
      "Window.*partition \\{0\\} order by \\[0\\].*SUM\\(\\$0\\)",
      "Scan.*columns=\\[`n_nationKey`\\].*"
    };
    final String[] excludedPatterns2 = {"Scan.*columns=\\[`\\*`\\].*"};
    //    PlanTestBase.testPlanMatchingPatterns(query2, expectedPlan2, excludedPatterns2);

    testBuilder()
        .sqlQuery(query2)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(0L)
        .baselineValues(1L)
        .baselineValues(2L)
        .baselineValues(3L)
        .baselineValues(4L)
        .baselineValues(5L)
        .baselineValues(6L)
        .baselineValues(7L)
        .baselineValues(8L)
        .baselineValues(9L)
        .baselineValues(10L)
        .baselineValues(11L)
        .baselineValues(12L)
        .baselineValues(13L)
        .baselineValues(14L)
        .baselineValues(15L)
        .baselineValues(16L)
        .baselineValues(17L)
        .baselineValues(18L)
        .baselineValues(19L)
        .baselineValues(20L)
        .baselineValues(21L)
        .baselineValues(22L)
        .baselineValues(23L)
        .baselineValues(24L)
        .build()
        .run();

    final String[] expectedPlan3 = {
      "Window.*partition \\{0\\}.*SUM\\(\\$0\\)", "Scan.*columns=\\[`n_nationKey`\\].*"
    };
    final String[] excludedPatterns3 = {"Scan.*columns=\\[`\\*`\\].*"};
    //    PlanTestBase.testPlanMatchingPatterns(query3, expectedPlan3, excludedPatterns3);

    testBuilder()
        .sqlQuery(query3)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(0L)
        .baselineValues(1L)
        .baselineValues(2L)
        .baselineValues(3L)
        .baselineValues(4L)
        .baselineValues(5L)
        .baselineValues(6L)
        .baselineValues(7L)
        .baselineValues(8L)
        .baselineValues(9L)
        .baselineValues(10L)
        .baselineValues(11L)
        .baselineValues(12L)
        .baselineValues(13L)
        .baselineValues(14L)
        .baselineValues(15L)
        .baselineValues(16L)
        .baselineValues(17L)
        .baselineValues(18L)
        .baselineValues(19L)
        .baselineValues(20L)
        .baselineValues(21L)
        .baselineValues(22L)
        .baselineValues(23L)
        .baselineValues(24L)
        .build()
        .run();
  }

  @Test
  public void testWindowWithJoin() throws Exception {
    final String query =
        "select sum(t1.r_regionKey) over(partition by t1.r_regionKey) as col \n"
            + "from cp.\"tpch/region.parquet\" t1, cp.\"tpch/nation.parquet\" t2 \n"
            + "where t1.r_regionKey = t2.n_nationKey \n"
            + "group by t1.r_regionKey";

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{0\\}.*COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "TableFunction.*columns=\\[`n_nationkey`\\].*",
      "TableFunction.*columns=\\[`n_nationkey`\\].*"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\].*"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(0L)
        .baselineValues(1L)
        .baselineValues(2L)
        .baselineValues(3L)
        .baselineValues(4L)
        .build()
        .run();
  }

  @Test
  public void testCountEmptyPartitionByWithExchange() throws Exception {
    String query =
        String.format(
            "select count(*) over (order by o_orderpriority) as cnt from dfs.\"%s/multilevel/parquet\" where o_custkey < 100",
            TEST_RES_PATH);
    try {
      // Validate the plan
      final String[] expectedPlan = {
        "Window.*partition \\{\\} order by \\[0\\].*COUNT\\(\\)",
        "TableFunction.*columns=\\[`o_custkey`, `o_orderpriority`\\]"
      };
      final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
      PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

      testBuilder()
          .sqlQuery(query)
          .ordered()
          .baselineColumns("cnt")
          .optionSettingQueriesForTestQuery("alter session set \"planner.slice_target\" = 1")
          .baselineValues(1L)
          .baselineValues(4L)
          .baselineValues(4L)
          .baselineValues(4L)
          .build()
          .run();
    } finally {
      test("alter session set \"planner.slice_target\" = " + ExecConstants.SLICE_TARGET_DEFAULT);
    }
  }

  /* Verify the output of aggregate functions (which are reduced
   * eg: avg(x) = sum(x)/count(x)) return results of the correct
   * data type (double)
   */
  @Test
  public void testAvgVarianceWindowFunctions() throws Exception {
    final String avgQuery =
        "select avg(n_nationkey) over (partition by n_nationkey) col1 "
            + "from cp.\"tpch/nation.parquet\" "
            + "where n_nationkey = 1";

    // Validate the plan
    final String[] expectedPlan1 = {
      "Window.*partition \\{0\\} order by \\[\\].*COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "TableFunction.*columns=\\[`n_nationkey`\\]"
    };
    final String[] excludedPatterns1 = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(avgQuery, expectedPlan1, excludedPatterns1);

    testBuilder().sqlQuery(avgQuery).unOrdered().baselineColumns("col1").baselineValues(1.0d).go();

    final String varianceQuery =
        "select var_pop(n_nationkey) over (partition by n_nationkey) col1 "
            + "from cp.\"tpch/nation.parquet\" "
            + "where n_nationkey = 1";

    // Validate the plan
    final String[] expectedPlan2 = {
      "Window.*partition \\{0\\} order by \\[\\].*COUNT\\(\\$1\\), \\$SUM0\\(\\$1\\)",
      "TableFunction.*columns=\\[`n_nationkey`\\]"
    };
    final String[] excludedPatterns2 = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(varianceQuery, expectedPlan2, excludedPatterns2);

    testBuilder()
        .sqlQuery(varianceQuery)
        .unOrdered()
        .baselineColumns("col1")
        .baselineValues(0.0d)
        .go();
  }

  @Test
  public void testWindowFunctionWithKnownType() throws Exception {
    final String query =
        "select sum(cast(col_int as int)) over (partition by col_varchar) as col1 "
            + "from cp.\"jsoninput/large_int.json\" limit 1";

    // Validate the plan
    final String[] expectedPlan1 = {
      "Window.*partition \\{0\\} order by \\[\\].*COUNT\\(\\$1\\), \\$SUM0\\(\\$1\\)",
      "Scan.*columns=\\[`col_int`, `col_varchar`\\]"
    }; // There are only two columns col_int and col_varchar

    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan1, null);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1")
        .baselineValues(2147483649L)
        .go();

    final String avgQuery =
        "select avg(cast(col_int as double)) over (partition by col_varchar) as col1 "
            + "from cp.\"jsoninput/large_int.json\" limit 1";

    // Validate the plan
    final String[] expectedPlan2 = {
      "Window.*partition \\{0\\} order by \\[\\].*COUNT\\(\\$1\\), \\$SUM0\\(\\$1\\)",
      "Scan.*columns=\\[`col_varchar`, `col_int`\\]"
    };
    final String[] excludedPatterns2 = {"Scan.*columns=\\[`\\*`\\]"};
    // PlanTestBase.testPlanMatchingPatterns(avgQuery, expectedPlan2, excludedPatterns2);

    testBuilder()
        .sqlQuery(avgQuery)
        .unOrdered()
        .baselineColumns("col1")
        .baselineValues(1.0737418245E9d)
        .go();
  }

  @Test
  public void testCompoundIdentifierInWindowDefinition() throws Exception {
    String root =
        FileUtils.getResourceAsFile("/multilevel/csv/1994/Q1/orders_94_q1.csv").toURI().toString();
    String query =
        String.format(
            "SELECT count(*) OVER w as col1, count(*) OVER w as col2 \n"
                + "FROM dfs_test.\"%s\" \n"
                + "WINDOW w AS (PARTITION BY columns[1] ORDER BY columns[0] DESC)",
            root);

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{1\\} order by \\[0 DESC\\].*COUNT\\(\\)",
      "Scan.*columns=\\[`columns`\\[0\\], `columns`\\[1\\]\\]"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    // Validate the result
    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("col1", "col2")
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .baselineValues((long) 1, (long) 1)
        .build()
        .run();
  }

  @Test
  public void testRankWithGroupBy() throws Exception {
    final String query =
        "select dense_rank() over (order by l_suppkey) as rank1 "
            + " from cp.\"tpch/lineitem.parquet\" group by l_partkey, l_suppkey order by 1 desc limit 1";

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{\\} order by \\[0\\].*DENSE_RANK\\(\\)",
      "TableFunction.*columns=\\[`l_partkey`, `l_suppkey`\\]"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder().sqlQuery(query).unOrdered().baselineColumns("rank1").baselineValues(100L).go();
  }

  @Test
  @Ignore
  public void testWindowSumAggIsNotNull() throws Exception {
    String query =
        String.format(
            "select count(*) cnt from (select sum ( c1 ) over ( partition by c2 order by c1 asc nulls first ) w_sum from dfs.\"%s/window/table_with_nulls.parquet\" ) sub_query where w_sum is not null",
            TEST_RES_PATH);

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{1\\} order by \\[0 ASC-nulls-first\\].*COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "Scan.*columns=\\[`c1`, `c2`\\]"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("cnt")
        .baselineValues(26L)
        .build()
        .run();
  }

  @Test
  public void testWindowConstants() throws Exception {
    String query =
        "select rank() over w fn, sum(2) over w sumINTEGER, sum(employee_id) over w sumEmpId, sum(0.5) over w sumDecimal \n"
            + "from cp.\"employee.json\" \n"
            + "where position_id = 2 \n"
            + "window w as(partition by position_id order by employee_id)";

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{1\\} order by \\[0\\].*RANK\\(\\), SUM\\(\\$2\\), COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\), SUM\\(\\$3\\)",
      "Scan.*columns=\\[`employee_id`, `position_id`\\]"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .ordered()
        .baselineColumns("fn", "sumINTEGER", "sumEmpId", "sumDecimal")
        .baselineValues(1L, 2L, 2L, BigDecimal.valueOf(0.5))
        .baselineValues(2L, 4L, 6L, BigDecimal.valueOf(1.0))
        .baselineValues(3L, 6L, 11L, BigDecimal.valueOf(1.5))
        .baselineValues(4L, 8L, 31L, BigDecimal.valueOf(2.0))
        .baselineValues(5L, 10L, 52L, BigDecimal.valueOf(2.5))
        .baselineValues(6L, 12L, 74L, BigDecimal.valueOf(3.0))
        .build()
        .run();
  }

  @Test
  public void testMultiplePartitions1() throws Exception {
    String root = FileUtils.getResourceAsFile("/store/text/data/t.json").toURI().toString();
    String query =
        String.format(
            "select count(*) over(partition by b1 order by c1) as count1, \n"
                + "sum(a1)  over(partition by b1 order by c1) as sum1, \n"
                + "count(*) over(partition by a1 order by c1) as count2 \n"
                + "from dfs_test.\"%s\"",
            root);

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{0\\} order by \\[2\\].*COUNT\\(\\)",
      "Window.*partition \\{1\\} order by \\[2\\].*COUNT\\(\\), COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "Scan.*columns=\\[`a1`, `b1`, `c1`\\]"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("count1", "sum1", "count2")
        .baselineValues(1L, 0L, 2L)
        .baselineValues(1L, 0L, 2L)
        .baselineValues(2L, 0L, 5L)
        .baselineValues(3L, 0L, 5L)
        .baselineValues(3L, 0L, 5L)
        .baselineValues(1L, 10L, 2L)
        .baselineValues(1L, 10L, 2L)
        .baselineValues(2L, 20L, 5L)
        .baselineValues(3L, 30L, 5L)
        .baselineValues(3L, 30L, 5L)
        .build()
        .run();
  }

  @Test
  public void testMultiplePartitions2() throws Exception {
    String root = FileUtils.getResourceAsFile("/store/text/data/t.json").toURI().toString();
    String query =
        String.format(
            "select count(*) over(partition by b1 order by c1) as count1, \n"
                + "count(*) over(partition by a1 order by c1) as count2, \n"
                + "sum(a1)  over(partition by b1 order by c1) as sum1 \n"
                + "from dfs_test.\"%s\"",
            root);

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{0\\} order by \\[2\\].*COUNT\\(\\)",
      "Window.*partition \\{1\\} order by \\[2\\].*COUNT\\(\\), COUNT\\(\\$0\\), \\$SUM0\\(\\$0\\)",
      "Scan.*columns=\\[`a1`, `b1`, `c1`\\]"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`\\*`\\]"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("count1", "count2", "sum1")
        .baselineValues(1L, 2L, 0L)
        .baselineValues(1L, 2L, 0L)
        .baselineValues(2L, 5L, 0L)
        .baselineValues(3L, 5L, 0L)
        .baselineValues(3L, 5L, 0L)
        .baselineValues(1L, 2L, 10L)
        .baselineValues(1L, 2L, 10L)
        .baselineValues(2L, 5L, 20L)
        .baselineValues(3L, 5L, 30L)
        .baselineValues(3L, 5L, 30L)
        .build()
        .run();
  }

  @Test // see DRILL-3574
  public void testWithAndWithoutPartitions() throws Exception {
    String root = FileUtils.getResourceAsFile("/store/text/data/t.json").toURI().toString();
    String query =
        String.format(
            "select sum(a1) over(partition by b1, c1) as s1, sum(a1) over() as s2 \n"
                + "from dfs_test.\"%s\" \n"
                + "order by a1",
            root);

    test("alter session set \"planner.slice_target\" = 1");
    try {
      // Validate the plan
      final String[] expectedPlan = {
        "Window\\(window#0=\\[window\\(partition \\{\\}.*\n" + ".*UnionExchange"
      };
      PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, new String[] {});

      testBuilder()
          .sqlQuery(query)
          .unOrdered()
          .baselineColumns("s1", "s2")
          .baselineValues(0L, 50L)
          .baselineValues(0L, 50L)
          .baselineValues(0L, 50L)
          .baselineValues(0L, 50L)
          .baselineValues(0L, 50L)
          .baselineValues(10L, 50L)
          .baselineValues(10L, 50L)
          .baselineValues(10L, 50L)
          .baselineValues(20L, 50L)
          .baselineValues(20L, 50L)
          .build()
          .run();
    } finally {
      test("alter session set \"planner.slice_target\" = " + ExecConstants.SLICE_TARGET_DEFAULT);
    }
  }

  @Test // see DRILL-3657
  public void testConstantsInMultiplePartitions() throws Exception {
    String root = FileUtils.getResourceAsFile("/store/text/data/t.json").toURI().toString();
    String query =
        String.format(
            "select sum(1) over(partition by b1 order by a1) as sum1, sum(1) over(partition by a1) as sum2, rank() over(order by b1) as rank1 \n"
                + "from dfs_test.\"%s\" t\n"
                + "order by 1, 2, 3",
            root);

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*SUM\\(\\$3\\).*\n" + ".*Sort.*\n" + ".*Window.*SUM\\(\\$2\\).*"
    };
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, new String[] {});

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("sum1", "sum2", "rank1")
        .baselineValues(2L, 5L, 1L)
        .baselineValues(2L, 5L, 1L)
        .baselineValues(2L, 5L, 6L)
        .baselineValues(2L, 5L, 6L)
        .baselineValues(3L, 5L, 3L)
        .baselineValues(3L, 5L, 3L)
        .baselineValues(3L, 5L, 3L)
        .baselineValues(3L, 5L, 8L)
        .baselineValues(3L, 5L, 8L)
        .baselineValues(3L, 5L, 8L)
        .build()
        .run();
  }

  @Test
  public void testExpressionInWindowFunction() throws Exception {
    String root = FileUtils.getResourceAsFile("/store/text/data/t.json").toURI().toString();
    String query =
        String.format(
            "select a1, b1, sum(b1) over (partition by a1) as c1, sum(a1 + b1) over (partition by a1) as c2\n"
                + "from dfs_test.\"%s\"",
            root);

    // Validate the plan
    final String[] expectedPlan = {
      "Window\\(window#0=\\[window\\(partition \\{0\\} order by \\[\\].*\\[COUNT\\(\\$1\\), \\$SUM0\\(\\$1\\), COUNT\\(\\$2\\), \\$SUM0\\(\\$2\\)\\]"
    };
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, new String[] {});

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("a1", "b1", "c1", "c2")
        .baselineValues(0L, 1L, 8L, 8L)
        .baselineValues(0L, 1L, 8L, 8L)
        .baselineValues(0L, 2L, 8L, 8L)
        .baselineValues(0L, 2L, 8L, 8L)
        .baselineValues(0L, 2L, 8L, 8L)
        .baselineValues(10L, 3L, 21L, 71L)
        .baselineValues(10L, 3L, 21L, 71L)
        .baselineValues(10L, 5L, 21L, 71L)
        .baselineValues(10L, 5L, 21L, 71L)
        .baselineValues(10L, 5L, 21L, 71L)
        .build()
        .run();
  }

  @Test // see DRILL-3657
  public void testProjectPushPastWindow() throws Exception {
    String query =
        "select sum(n_nationkey) over(partition by 1) as col1, \n"
            + "count(n_nationkey) over(partition by 1) as col2 \n"
            + "from cp.\"tpch/nation.parquet\" \n"
            + "limit 5";

    // Validate the plan
    final String[] expectedPlan = {"TableFunction.*columns=\\[`n_nationkey`\\].*"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, new String[] {});

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col1", "col2")
        .baselineValues(300L, 25L)
        .baselineValues(300L, 25L)
        .baselineValues(300L, 25L)
        .baselineValues(300L, 25L)
        .baselineValues(300L, 25L)
        .build()
        .run();
  }

  @Test
  public void testWindowFunInNestSubQ() throws Exception {
    test("set planner.slice_target = 1");
    final String query =
        " select n_nationkey , n_regionkey , "
            + "        lead(n_regionkey) OVER ( PARTITION BY n_regionkey ORDER BY n_nationkey) lead_c2 "
            + " FROM (SELECT n_nationkey ,n_regionkey, "
            + "          ntile(3) over(PARTITION BY n_regionkey ORDER BY n_nationkey) "
            + "       FROM cp.\"tpch/nation.parquet\") "
            + " order by n_regionkey, n_nationkey";
    test(query);

    final String baselineQuery =
        "select n_nationkey , n_regionkey , "
            + "       lead(n_regionkey) OVER ( PARTITION BY n_regionkey ORDER BY n_nationkey) lead_c2 "
            + "FROM cp.\"tpch/nation.parquet\"   "
            + "order by n_regionkey, n_nationkey";

    testBuilder().sqlQuery(query).ordered().sqlBaselineQuery(baselineQuery).build().run();
  }

  @Test
  public void testWindowFunInNestSubQSlice1() throws Exception {
    //
    final String query =
        " select n_nationkey , n_regionkey , "
            + "        lead(n_regionkey) OVER ( PARTITION BY n_regionkey ORDER BY n_nationkey) lead_c2 "
            + " FROM (SELECT n_nationkey ,n_regionkey, "
            + "          ntile(3) over(PARTITION BY n_regionkey ORDER BY n_nationkey) "
            + "       FROM cp.\"tpch/nation.parquet\") "
            + " order by n_regionkey, n_nationkey";
    test(query);

    final String baselineQuery =
        "select n_nationkey , n_regionkey , "
            + "       lead(n_regionkey) OVER ( PARTITION BY n_regionkey ORDER BY n_nationkey) lead_c2 "
            + "FROM cp.\"tpch/nation.parquet\"   "
            + "order by n_regionkey, n_nationkey";

    try {
      testBuilder()
          .sqlQuery(query)
          .ordered()
          .optionSettingQueriesForTestQuery("alter session set \"planner.slice_target\" = 1")
          .sqlBaselineQuery(baselineQuery)
          .build()
          .run();
    } finally {
      test("alter session set \"planner.slice_target\" = " + ExecConstants.SLICE_TARGET_DEFAULT);
    }
  }

  @Test
  public void testWindowFunInNestSubQ2() throws Exception {

    final String query2 =
        " select rnum, position_id, "
            + "   ntile(4) over(order by position_id) "
            + " from (select position_id, row_number() "
            + "       over(order by position_id) as rnum "
            + "       from cp.\"employee.json\")";

    final String baselineQuery2 =
        " select row_number() over(order by position_id) as rnum, "
            + "    position_id, "
            + "    ntile(4) over(order by position_id) "
            + " from cp.\"employee.json\"";

    testBuilder().sqlQuery(query2).ordered().sqlBaselineQuery(baselineQuery2).build().run();
  }

  @Test
  public void testStatisticalWindowFunctions() throws Exception {
    final String sqlWindowFunctionQuery =
        "select "
            + "stddev_samp(employee_id) over (partition by 1) c1, "
            + "stddev_pop(employee_id) over (partition by 1) c2, "
            + "var_samp(employee_id) over (partition by 1) c3, "
            + "var_pop(employee_id) over (partition by 1) c4 from "
            + "cp.\"employee.json\" limit 1";

    testBuilder()
        .sqlQuery(sqlWindowFunctionQuery)
        .unOrdered()
        .baselineColumns("c1", "c2", "c3", "c4")
        .baselineValues(
            333.56708470261117d, 333.4226520980038d, 111266.99999699896d, 111170.66493206649d)
        .build()
        .run();
  }

  @Test
  public void testStatisticalWindowAvgFunction() throws Exception {
    final String sqlWindowFunctionQuery =
        "select "
            + "avg(employee_id) over (partition by 1) c1 from "
            + "cp.\"employee.json\" limit 1";

    testBuilder()
        .sqlQuery(sqlWindowFunctionQuery)
        .unOrdered()
        .baselineColumns("c1")
        .baselineValues(578.9982683982684)
        .build()
        .run();
  }

  @Test
  public void testNestedAggregates() throws Exception {

    final String query =
        "select sum(min(l_extendedprice))"
            + " over (partition by l_suppkey order by l_suppkey) as totprice"
            + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10 group by l_suppkey order by 1 desc";

    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{0\\} order by \\[0\\].*\\$SUM0\\(\\$1\\).*",
      "HashAgg\\(group=\\[\\{0\\}\\].*\\[MIN\\(\\$1\\)\\]\\)"
    };
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, new String[] {});

    // Validate the results
    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("totprice")
        .baselineValues(1107.2)
        .baselineValues(998.09)
        .baselineValues(957.05)
        .baselineValues(953.05)
        .baselineValues(931.03)
        .baselineValues(926.02)
        .baselineValues(909.0)
        .baselineValues(906.0)
        .baselineValues(904.0)
        .baselineValues(904.0)
        .go();
  }

  @Test
  public void testNestedAggregates1() throws Exception {
    UserExceptionAssert.assertThatThrownBy(
            () -> {
              String query =
                  "select sum(min(l_extendedprice)) over (partition by l_suppkey)\n"
                      + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10";
              test(query);
            })
        .hasMessageContaining("Expression 'l_suppkey' is not being grouped");

    UserExceptionAssert.assertThatThrownBy(
            () -> {
              String query =
                  "select sum(min(l_extendedprice)) over (partition by l_suppkey) as totprice\n"
                      + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10";
              test(query);
            })
        .hasMessageContaining("Expression 'l_suppkey' is not being grouped");

    UserExceptionAssert.assertThatThrownBy(
            () -> {
              String query =
                  "select sum(min(l_extendedprice)) over w1 as totprice\n"
                      + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10\n"
                      + " window w1 as (partition by l_suppkey)";
              test(query);
            })
        .hasMessageContaining("Expression 'l_suppkey' is not being grouped");

    UserExceptionAssert.assertThatThrownBy(
            () -> {
              String query =
                  "select sum(min(l_extendedprice)) over (partition by l_partkey)\n"
                      + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10 group by l_suppkey";
              test(query);
            })
        .hasMessageContaining("Expression 'l_partkey' is not being grouped");

    UserExceptionAssert.assertThatThrownBy(
            () -> {
              String query =
                  "select sum(min(l_extendedprice)) over (partition by l_partkey) as totprice\n"
                      + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10 group by l_suppkey";
              test(query);
            })
        .hasMessageContaining("Expression 'l_partkey' is not being grouped");

    UserExceptionAssert.assertThatThrownBy(
            () -> {
              String query =
                  "select sum(min(l_extendedprice)) over w2 as totprice\n"
                      + " from cp.\"tpch/lineitem.parquet\" where l_suppkey <= 10 group by l_suppkey\n"
                      + " window w2 as (partition by l_partkey)";
              test(query);
            })
        .hasMessageContaining("Expression 'l_partkey' is not being grouped");
  }

  @Test
  public void testWindowAndStreamingAgg() throws Exception {
    String query =
        "select data_date as col1, sum(score1) as col2, sum(scorelag) as col3 \n"
            + "from \n"
            + "(\n"
            + "\tselect \n"
            + "\t  id, \n"
            + "\t  data_date, \n"
            + "\t  score1, \n"
            + "\t  lag(score1) over (partition by id order by data_date) as scorelag \n"
            + "\tfrom (\n"
            + "      SELECT x as id, cast(y as bigint) as score1, cast(z as date) as data_date\n"
            + "      FROM (values ('test1', 10, '2018-01-01'), ('test1', 20, '2018-02-01'), ('test1', 30, '2018-03-01'), ('test2', 10, '2018-01-01'), ('test2', 20, '2018-02-01'), ('test2', 30, '2018-03-01')) as tbl(x, y, z)\n"
            + "    )\n"
            + ") a \n"
            + "group by data_date \n"
            + "order by data_date\n";

    try (AutoCloseable ignored = withOption(PlannerSettings.HASHAGG, false); ) {
      testBuilder()
          .sqlQuery(query)
          .ordered()
          .baselineColumns("col1", "col2", "col3")
          .baselineValues(new LocalDateTime(2018, 1, 01, 0, 0), 20L, null)
          .baselineValues(new LocalDateTime(2018, 2, 01, 0, 0), 40L, 20L)
          .baselineValues(new LocalDateTime(2018, 3, 01, 0, 0), 60L, 40L)
          .build()
          .run();
    }
  }

  @Test
  public void testWindowCount() throws Exception {
    final String query = "select count(*) over() as cnt \n" + "from cp.\"tpch/nation.parquet\"";
    // Validate the plan
    final String[] expectedPlan = {
      "Window.*partition \\{\\} order by \\[\\].*\\[COUNT\\(\\)\\]",
      "TableFunction.*columns=\\[\\].*"
    };
    final String[] excludedPatterns = {"Scan.*columns=\\[`.*`\\].*"};
    PlanTestBase.testPlanMatchingPatterns(query, expectedPlan, excludedPatterns);
    TestBuilder builder = testBuilder().sqlQuery(query).unOrdered().baselineColumns("cnt");

    // Adding 25 times 25
    IntStream.range(0, 25).forEach((ignored) -> builder.baselineValues(25L));

    builder.go();
  }

  @Test
  public void testGroupRemoved() throws Exception {
    String query =
        "select bar FROM (select sum(min(l_extendedprice)) over (partition by l_partkey) as foo,\n"
            + " sum(max(l_extendedprice)) over (partition by l_partkey, l_suppkey) as bar"
            + " from cp.\"tpch/lineitem.parquet\" group by l_partkey, l_suppkey)";
    // Confirming that query can be planned. See DX-14211
    test(query);
  }

  @Test
  public void testEmptyOver() throws Exception {
    String query = "select count(*) over () from cp.\"tpch/lineitem.parquet\"";
    test(query);
  }

  @Test
  public void testEmptyOverWithLag() throws Exception {
    try {
      String query = "select lag(l_extendedprice) over () from cp.\"tpch/lineitem.parquet\"";
      test(query);
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "LAG, LEAD or NTILE functions require ORDER BY clause in window specification"));
    }
  }

  @Test
  public void testEmptyOverWithLead() throws Exception {
    try {
      String query = "select lead(l_extendedprice) over () from cp.\"tpch/lineitem.parquet\"";
      test(query);
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "LAG, LEAD or NTILE functions require ORDER BY clause in window specification"));
    }
  }

  @Test
  public void testEmptyOverWithNtile() throws Exception {
    try {
      String query = "select ntile(10) over () from cp.\"tpch/lineitem.parquet\"";
      test(query);
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "LAG, LEAD or NTILE functions require ORDER BY clause in window specification"));
    }
  }

  @Test
  public void testOrderByOrdinal() throws Exception {
    try {
      test("select lead(100) over (order by 1) from cp.\"tpch/lineitem.parquet\"");
    } catch (Exception e) {
      assertTrue(
          e.getMessage()
              .contains("Dremio does not currently support order by with ordinals in over clause"));
    }
  }

  @Test
  public void testWindowUnderPrecedentOperation() {
    UserExceptionAssert.assertThatThrownBy(
            () ->
                test(
                    "select 1/(count(distinct n_nationKey) over (partition by n_nationKey)) \n"
                        + "from cp.\"tpch/nation.parquet\""))
        .hasErrorType(UNSUPPORTED_OPERATION)
        .hasMessageContaining("DISTINCT for window aggregate functions is not currently supported");
  }

  @Test
  public void testWindowUnderNestedPrecedentOperation() {
    UserExceptionAssert.assertThatThrownBy(
            () ->
                test(
                    "select 1/(1/(count(distinct n_nationKey) over (partition by n_nationKey))) \n"
                        + "from cp.\"tpch/nation.parquet\""))
        .hasErrorType(UNSUPPORTED_OPERATION)
        .hasMessageContaining("DISTINCT for window aggregate functions is not currently supported");
  }

  @Test
  public void testAggregateWindowCountUnderPrecedentOperation() {
    UserExceptionAssert.assertThatThrownBy(
            () ->
                test(
                    "select 1/(count(distinct n_nationKey) over (partition by n_nationKey)) \n"
                        + "from cp.\"tpch/nation.parquet\""))
        .hasErrorType(UNSUPPORTED_OPERATION)
        .hasMessageContaining("DISTINCT for window aggregate functions is not currently supported");
  }

  @Test
  public void testAggregateWindowCountUnderNestedPrecedentOperation() {
    UserExceptionAssert.assertThatThrownBy(
            () ->
                test(
                    "select 1/(1/(count(distinct n_nationKey) over (partition by n_nationKey))) \n"
                        + "from cp.\"tpch/nation.parquet\""))
        .hasErrorType(UNSUPPORTED_OPERATION)
        .hasMessageContaining("DISTINCT for window aggregate functions is not currently supported");
  }
}
