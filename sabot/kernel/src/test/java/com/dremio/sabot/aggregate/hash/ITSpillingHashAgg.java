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
package com.dremio.sabot.aggregate.hash;

import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.OUT_OF_MEMORY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.expression.CompleteType;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.util.TestTools;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.HashAggregate;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.server.SabotContext;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.sabot.BaseTestOperator;
import com.dremio.sabot.CustomHashAggDataGenerator;
import com.dremio.sabot.CustomHashAggDataGeneratorDecimal;
import com.dremio.sabot.CustomHashAggDataGeneratorLargeAccum;
import com.dremio.sabot.Fixtures;
import com.dremio.sabot.exec.context.OperatorContextImpl;
import com.dremio.sabot.op.aggregate.vectorized.VectorizedHashAggOperator;
import com.dremio.sabot.op.aggregate.vectorized.VectorizedHashAggSpillStats;
import com.dremio.sabot.op.common.ht2.FieldVectorPair;
import com.dremio.sabot.op.common.ht2.PivotBuilder;
import com.dremio.sabot.op.common.ht2.PivotDef;
import com.dremio.test.AllocatorRule;
import com.dremio.test.UserExceptionAssert;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

public class ITSpillingHashAgg extends BaseTestOperator {

  @Rule public final TestRule timeoutRule = TestTools.getTimeoutRule(1000, TimeUnit.SECONDS);

  @Rule public final AllocatorRule allocatorRule = AllocatorRule.defaultAllocator();

  public static HashAggregate getHashAggregate(long reserve, long max, int hashTableBatchSize) {
    OpProps props = PROPS.cloneWithNewReserve(reserve).cloneWithMemoryExpensive(true);
    props.setMemLimit(max);
    return new HashAggregate(
        props,
        null,
        Arrays.asList(
            n("INT_KEY"),
            n("BIGINT_KEY"),
            n("VARCHAR_KEY"),
            n("FLOAT_KEY"),
            n("DOUBLE_KEY"),
            n("BOOLEAN_KEY"),
            n("DECIMAL_KEY")),
        Arrays.asList(
            n("sum(INT_MEASURE)", "SUM_INT"),
            n("min(INT_MEASURE)", "MIN_INT"),
            n("max(INT_MEASURE)", "MAX_INT"),
            n("sum(BIGINT_MEASURE)", "SUM_BIGINT"),
            n("min(BIGINT_MEASURE)", "MIN_BIGINT"),
            n("max(BIGINT_MEASURE)", "MAX_BIGINT"),
            n("sum(FLOAT_MEASURE)", "SUM_FLOAT"),
            n("min(FLOAT_MEASURE)", "MIN_FLOAT"),
            n("max(FLOAT_MEASURE)", "MAX_FLOAT"),
            n("sum(DOUBLE_MEASURE)", "SUM_DOUBLE"),
            n("min(DOUBLE_MEASURE)", "MIN_DOUBLE"),
            n("max(DOUBLE_MEASURE)", "MAX_DOUBLE"),
            n("sum(DECIMAL_MEASURE)", "SUM_DECIMAL"),
            n("min(DECIMAL_MEASURE)", "MIN_DECIMAL"),
            n("max(DECIMAL_MEASURE)", "MAX_DECIMAL")),
        true,
        true,
        1f,
        hashTableBatchSize);
  }

  protected HashAggregate getHashAggregateWithLargeAccum(
      long reserve, long max, int hashTableBatchSize, int numAccum) {
    OpProps props = PROPS.cloneWithNewReserve(reserve).cloneWithMemoryExpensive(true);
    props.setMemLimit(max);
    List<NamedExpression> aggExpr = new ArrayList<>();
    for (int i = 0; i < numAccum; ++i) {
      aggExpr.add(n("sum(INT_MEASURE_" + i + " )", "SUM_INT"));
    }

    return new HashAggregate(
        props,
        null,
        Arrays.asList(
            n("INT_KEY"),
            n("BIGINT_KEY"),
            n("VARCHAR_KEY"),
            n("FLOAT_KEY"),
            n("DOUBLE_KEY"),
            n("BOOLEAN_KEY"),
            n("DECIMAL_KEY")),
        aggExpr,
        true,
        true,
        1f,
        hashTableBatchSize);
  }

  protected HashAggregate getHashAggregate(long reserve, long max) {
    return getHashAggregate(reserve, max, 3968);
  }

  public HashAggregate getHashAggregateDecimal(long reserve, long max, int hashTableBatchSize) {
    OpProps props = PROPS.cloneWithNewReserve(reserve).cloneWithMemoryExpensive(true);
    props.setMemLimit(max);
    return new HashAggregate(
        props,
        null,
        Arrays.asList(n("DECIMAL_KEY")),
        Arrays.asList(
            n("sum(DECIMAL_MEASURE)", "SUM_DECIMAL"),
            n("min(DECIMAL_MEASURE)", "MIN_DECIMAL"),
            n("max(DECIMAL_MEASURE)", "MAX_DECIMAL"),
            n("$sum0(DECIMAL_MEASURE)", "SUM0_DECIMAL")),
        true,
        true,
        1f,
        hashTableBatchSize);
  }

  /**
   * Test no spilling
   *
   * @throws Exception
   */
  @Test
  public void testNoSpill() throws Exception {
    final HashAggregate agg = getHashAggregate(1_000_000, 12_000_000);
    try (CustomHashAggDataGenerator generator =
        new CustomHashAggDataGenerator(2000, getTestAllocator(), true)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
      final VectorizedHashAggSpillStats stats = agg.getSpillStats();
      assertEquals(0, stats.getSpills());
      assertEquals(0, stats.getOoms());
      assertEquals(1, stats.getIterations());
      assertEquals(0, stats.getRecursionDepth());
    }
  }

  @Test
  public void testRowSizeLimitHashAgg() throws Exception {
    final HashAggregate agg = getHashAggregate(1_000_000, 12_000_000);
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(2000, getTestAllocator(), true);
        AutoCloseable ac = with(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = with(ExecConstants.LIMIT_ROW_SIZE_BYTES, 1250); ) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
  }

  /**
   * Tests with varchar key of length > 32k
   *
   * @throws Exception
   */
  @Test
  public void testVeryLargeVarcharKey() throws Exception {
    testVeryLargeVarcharKey(getHashAggregate(1_000_000, 12_000_000));
  }

  public void testVeryLargeVarcharKey(HashAggregate hashagg) throws Exception {
    HashAggregate agg = hashagg;

    boolean exceptionThrown = false;

    final int shortLen = (120 * 1024);
    final int largeLen = (128 * 1024);

    // shortLen size must not fail, any subsequent largeLen inserts would fail
    boolean shortLenSuccess = false;
    try (CustomHashAggDataGenerator generator =
        new CustomHashAggDataGenerator(1000, getTestAllocator(), shortLen)) {
      try (CustomHashAggDataGenerator generator1 =
          new CustomHashAggDataGenerator(1000, getTestAllocator(), largeLen)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 1000);

        // shortLen key size should have worked. must reach here!
        shortLenSuccess = true;

        // add some largeLen keys
        Fixtures.Table table1 = generator1.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator1, table1, 1000);

        // must not reach here
        Assert.assertEquals(0, 1);
      }
    } catch (UnsupportedOperationException userExp) {
      exceptionThrown = true;
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      Assert.assertEquals(true, shortLenSuccess);
      Assert.assertEquals(true, exceptionThrown);
    }

    // fails to pivot with largeLen key size
    try (CustomHashAggDataGenerator generator =
        new CustomHashAggDataGenerator(1000, getTestAllocator(), largeLen)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 1000);

      // must not reach here
      Assert.assertEquals(0, 1);
    } catch (UnsupportedOperationException userExp) {
      exceptionThrown = true;
    } finally {
      Assert.assertEquals(true, exceptionThrown);
    }
  }

  @Test
  public void testVeryLargeVarcharKey_1() throws Exception {
    testVeryLargeVarcharKey_1(getHashAggregate(1_000_000, 24_000_000, 3968 * 2), true);
  }

  public void testVeryLargeVarcharKey_1(HashAggregate hashagg, boolean expectedExpection)
      throws Exception {
    // passes largeLen key size with increased batch size.
    HashAggregate agg = hashagg;

    final int largeLen = (128 * 1024);

    // shortLen size must not fail, any subsequent largeLen inserts would fail
    boolean exceptionThrown = true;
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1000, getTestAllocator(), largeLen);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 2048 * 1024);
        AutoCloseable options2 = with(ExecConstants.TARGET_BATCH_RECORDS_MAX, 8192)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 1000);
    } catch (Exception e) {
      System.err.println("Exception message: " + e.getMessage());
      e.printStackTrace();
      exceptionThrown = true;
    } finally {
      Assert.assertEquals(expectedExpection, exceptionThrown);
    }

    // should fail with 1MB key size
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1000, getTestAllocator(), (1024 * 1024));
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 2048 * 1024);
        AutoCloseable options2 = with(ExecConstants.TARGET_BATCH_RECORDS_MAX, 8192)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 1000);
    } catch (UnsupportedOperationException userExp) {
      exceptionThrown = true;
    } finally {
      Assert.assertEquals(true, exceptionThrown);
    }
  }

  /**
   * Test failure during operator setup when provided memory constraints are lower than the memory
   * required for preallocating data structures.
   */
  @Test
  public void testSetupFailureForHashTableInit() {
    final HashAggregate agg = getHashAggregate(1_000_000, 2_100_000);
    UserExceptionAssert.assertThatThrownBy(
            () -> {
              try (CustomHashAggDataGenerator generator =
                  new CustomHashAggDataGenerator(2000, getTestAllocator(), true)) {
                Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
                validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
                final VectorizedHashAggSpillStats stats = agg.getSpillStats();
                assertEquals(0, stats.getSpills());
                assertEquals(0, stats.getOoms());
                assertEquals(1, stats.getIterations());
                assertEquals(0, stats.getRecursionDepth());
              }
            })
        .hasErrorType(OUT_OF_MEMORY)
        .hasMessageContaining(
            "Query was cancelled because it exceeded the memory limits set by the administrator.")
        .hasContext(VectorizedHashAggOperator.PREALLOC_FAILURE_PARTITIONS);
  }

  @Test
  public void testSetupFailureForPreallocation() {
    testSetupFailureForPreallocation(getHashAggregate(1_000_000, 5_000_000));
  }

  public void testSetupFailureForPreallocation(HashAggregate hashagg) {
    final HashAggregate agg = hashagg;
    UserExceptionAssert.assertThatThrownBy(
            () -> {
              try (CustomHashAggDataGenerator generator =
                  new CustomHashAggDataGenerator(2000, getTestAllocator(), true)) {
                Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
                validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
                final VectorizedHashAggSpillStats stats = agg.getSpillStats();
                assertEquals(0, stats.getSpills());
                assertEquals(0, stats.getOoms());
                assertEquals(1, stats.getIterations());
                assertEquals(0, stats.getRecursionDepth());
              }
            })
        .hasErrorType(OUT_OF_MEMORY)
        .hasMessageContaining(
            "Query was cancelled because it exceeded the memory limits set by the administrator.")
        .hasContext(VectorizedHashAggOperator.PREALLOC_FAILURE_PARTITIONS);
  }

  @Test
  public void testSetupFailureForExtraPartition() {
    testSetupFailureForExtraPartition(getHashAggregate(1_000_000, 8_800_000));
  }

  public void testSetupFailureForExtraPartition(HashAggregate hashagg) {
    final HashAggregate agg = hashagg;
    UserExceptionAssert.assertThatThrownBy(
            () -> {
              try (CustomHashAggDataGenerator generator =
                  new CustomHashAggDataGenerator(2000, getTestAllocator(), true)) {
                Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
                validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
                final VectorizedHashAggSpillStats stats = agg.getSpillStats();
                assertEquals(0, stats.getSpills());
                assertEquals(0, stats.getOoms());
                assertEquals(1, stats.getIterations());
                assertEquals(0, stats.getRecursionDepth());
              }
            })
        .hasErrorType(OUT_OF_MEMORY)
        .hasMessageContaining(
            "Query was cancelled because it exceeded the memory limits set by the administrator.")
        .hasContext(VectorizedHashAggOperator.PREALLOC_FAILURE_LOADING_PARTITION);
  }

  @Test
  public void testSetupFailureForAuxStructures() throws Exception {
    testSetupFailureForAuxStructures(getHashAggregate(1_000_000, 9_900_000));
  }

  public void testSetupFailureForAuxStructures(HashAggregate hashagg) throws Exception {
    final HashAggregate agg = hashagg;
    UserExceptionAssert.assertThatThrownBy(
            () -> {
              try (CustomHashAggDataGenerator generator =
                  new CustomHashAggDataGenerator(2000, getTestAllocator(), true)) {
                Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
                validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
                final VectorizedHashAggSpillStats stats = agg.getSpillStats();
                assertEquals(0, stats.getSpills());
                assertEquals(0, stats.getOoms());
                assertEquals(1, stats.getIterations());
                assertEquals(0, stats.getRecursionDepth());
              }
            })
        .hasErrorType(OUT_OF_MEMORY)
        .hasMessageContaining(
            "Query was cancelled because it exceeded the memory limits set by the administrator.")
        .hasContext(VectorizedHashAggOperator.PREALLOC_FAILURE_AUX_STRUCTURES);
  }

  /*
   * Note on the usage of ExecConstants.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS
   * The algorithm that picks up victim partitions to spill first targets the set of spilled
   * partitions to see if there is a suitable candidate. This helps in keeping the total
   * number of unique partitions spilled to minimum. In other words, it helps to prevent
   * situations where all partitions are spilled and we are not left with anything in
   * memory. This also means that we may end up choosing a spilled partition as the next
   * victim partition where there is another non-spilled partition having the potential
   * to release more memory. Thus there are chances of hitting slightly higher OOMs
   * with this approach.
   *
   * We saw this behavior in these unit tests where after the victim partition selection
   * algorithm was correctly implemented, the stats for each unit test increased considerably.
   * There was an increase in the number of times we hit OOM but the total number of unique
   * partitions spilled was low.
   *
   * Since we want near-deterministic behavior in these unit tests, we introduced a way
   * to disable the selection algorithm partially. Instead of first looking at the set
   * of spilled partitions, it directly looks at all active partitions and picks
   * the partition with highest memory usage.
   */

  /**
   * Test spill of 3K rows -- no recursive spilling
   *
   * @throws Exception
   */
  @Test
  public void testSpill3K() throws Exception {
    testSpill3K(getHashAggregate(1_000_000, 4_000_000, 990), true);
  }

  public void testSpill3K(HashAggregate hashagg, boolean runWithMinMemory) throws Exception {
    final HashAggregate agg = hashagg;
    try (AutoCloseable maxHashTableBatchSizeBytes =
        with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 128 * 1024)) {
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(3000, getTestAllocator(), true);
          AutoCloseable options =
              with(
                  VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                  false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
        final VectorizedHashAggSpillStats stats = agg.getSpillStats();
        //        assertEquals(12, stats.getSpills());
        //        assertEquals(6, stats.getOoms());
        //        assertEquals(7, stats.getIterations());
        //        assertEquals(1, stats.getRecursionDepth());
      }
      if (runWithMinMemory) {
        /* run with allocator limit same as minimum reservation */
        try (CustomHashAggDataGenerator generator =
                new CustomHashAggDataGenerator(3000, getTestAllocator(), true);
            AutoCloseable options =
                with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
          Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
          validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
        }
      }
      /* run with micro spilling disabled */
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(3000, getTestAllocator(), true);
          AutoCloseable options =
              with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
      }
    }
  }

  @Test
  public void testSpill100KWithLargeAccum() throws Exception {
    final int numAccum = 128;
    testSpill100KWithLargeAccum(
        getHashAggregateWithLargeAccum(1_000_000, 13_000_000, 990, numAccum));
  }

  public void testSpill100KWithLargeAccum(HashAggregate hashagg) throws Exception {
    final int numAccum = 128;
    final HashAggregate agg = hashagg;
    try (AutoCloseable maxHashTableBatchSizeBytes =
        with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 128 * 1024)) {
      try (CustomHashAggDataGeneratorLargeAccum generator =
              new CustomHashAggDataGeneratorLargeAccum(100000, getTestAllocator(), numAccum);
          AutoCloseable options =
              with(
                  VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                  false)) {
        validateSingle(agg, VectorizedHashAggOperator.class, generator, null, 3000);
        final VectorizedHashAggSpillStats stats = agg.getSpillStats();

        // it must spill
        assertTrue(stats.getSpills() > 0);
      }
    }
  }

  @Test
  public void testSpill100KDecimal() throws Exception {
    testSpill100KDecimal(getHashAggregateDecimal(1_000_000, 2_100_000, 990));
  }

  public void testSpill100KDecimal(HashAggregate hashagg) throws Exception {
    final HashAggregate agg = hashagg;
    try (AutoCloseable maxHashTableBatchSizeBytes =
        with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 64 * 1024)) {
      try (CustomHashAggDataGeneratorDecimal generator =
              new CustomHashAggDataGeneratorDecimal(100000, getTestAllocator(), true);
          AutoCloseable options =
              with(
                  VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                  false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
        final VectorizedHashAggSpillStats stats = agg.getSpillStats();
        // assertTrue(stats.getSpills() > 0);
      }
      /* run with allocator limit same as minimum reservation */
      try (CustomHashAggDataGeneratorDecimal generator =
              new CustomHashAggDataGeneratorDecimal(100000, getTestAllocator(), true);
          AutoCloseable options =
              with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
      }
      /* run with micro spilling disabled */
      try (CustomHashAggDataGeneratorDecimal generator =
              new CustomHashAggDataGeneratorDecimal(100000, getTestAllocator(), true);
          AutoCloseable options =
              with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
      }
    }
  }

  public HashAggregate getHashAggregateWithCount(long reserve, long max, int hashTableBatchSize) {
    OpProps props = PROPS.cloneWithNewReserve(reserve);
    props.setMemLimit(max);
    return new HashAggregate(
        props,
        null,
        Arrays.asList(
            n("INT_KEY"),
            n("BIGINT_KEY"),
            n("VARCHAR_KEY"),
            n("FLOAT_KEY"),
            n("DOUBLE_KEY"),
            n("BOOLEAN_KEY"),
            n("DECIMAL_KEY")),
        Arrays.asList(
            n("sum(INT_MEASURE)", "SUM_INT"),
            n("min(INT_MEASURE)", "MIN_INT"),
            n("max(INT_MEASURE)", "MAX_INT"),
            n("count(INT_MEASURE)", "COUNT_INT"),
            n("sum(BIGINT_MEASURE)", "SUM_BIGINT"),
            n("min(BIGINT_MEASURE)", "MIN_BIGINT"),
            n("max(BIGINT_MEASURE)", "MAX_BIGINT"),
            n("count(BIGINT_MEASURE)", "COUNT_BIGINT"),
            n("sum(FLOAT_MEASURE)", "SUM_FLOAT"),
            n("min(FLOAT_MEASURE)", "MIN_FLOAT"),
            n("max(FLOAT_MEASURE)", "MAX_FLOAT"),
            n("count(FLOAT_MEASURE)", "COUNT_FLOAT"),
            n("sum(DOUBLE_MEASURE)", "SUM_DOUBLE"),
            n("min(DOUBLE_MEASURE)", "MIN_DOUBLE"),
            n("max(DOUBLE_MEASURE)", "MAX_DOUBLE"),
            n("count(DOUBLE_MEASURE)", "COUNT_DOUBLE"),
            n("sum(DECIMAL_MEASURE)", "SUM_DECIMAL"),
            n("min(DECIMAL_MEASURE)", "MIN_DECIMAL"),
            n("max(DECIMAL_MEASURE)", "MAX_DECIMAL"),
            n("count(DECIMAL_MEASURE)", "COUNT_DECIMAL")),
        true,
        true,
        1f,
        hashTableBatchSize);
  }

  /**
   * Same as (number of rows, memory) previous test but with count accumulator resulting in slightly
   * more spilling
   *
   * @throws Exception
   */
  @Test
  public void testSpill3KWithCount() throws Exception {
    testSpill3KWithCount(getHashAggregateWithCount(1_000_000, 4_000_000, 990), true);
  }

  public void testSpill3KWithCount(HashAggregate hashagg, boolean runWithMinMemory)
      throws Exception {
    final HashAggregate agg = hashagg;
    try (AutoCloseable maxHashTableBatchSizeBytes =
        with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 128 * 1024)) {
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(3000, getTestAllocator(), true);
          AutoCloseable options =
              with(
                  VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                  false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregationsWithCount();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
        final VectorizedHashAggSpillStats stats = agg.getSpillStats();
      }
      if (runWithMinMemory) {
        /* run with allocator limit same as minimum reservation */
        try (CustomHashAggDataGenerator generator =
                new CustomHashAggDataGenerator(3000, getTestAllocator(), true);
            AutoCloseable options =
                with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
          Fixtures.Table table = generator.getExpectedGroupsAndAggregationsWithCount();
          validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
        }
      }
      /* run with micro spilling disabled */
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(3000, getTestAllocator(), true);
          AutoCloseable options =
              with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregationsWithCount();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 3000);
      }
    }
  }

  /**
   * Test spill of 4K rows -- no recursive spilling
   *
   * @throws Exception
   */
  @Test
  public void testSpill4K() throws Exception {
    testSpill4K(getHashAggregate(1_000_000, 4_000_000, 990), true);
  }

  public void testSpill4K(HashAggregate hashagg, boolean runWithMinMemory) throws Exception {
    final HashAggregate agg = hashagg;
    try (AutoCloseable maxHashTableBatchSizeBytes =
        with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 128 * 1024)) {
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(4000, getTestAllocator(), true);
          AutoCloseable options =
              with(
                  VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                  false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
        final VectorizedHashAggSpillStats stats = agg.getSpillStats();
        assertTrue(stats.getSpills() > 0);
        assertTrue(stats.getOoms() > 0);
        assertTrue(stats.getIterations() > 0);
        assertEquals(1, stats.getRecursionDepth());
      }
      if (runWithMinMemory) {
        /* run with allocator limit same as minimum reservation */
        try (CustomHashAggDataGenerator generator =
                new CustomHashAggDataGenerator(4000, getTestAllocator(), true);
            AutoCloseable options =
                with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
          Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
          validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
        }
      }
      /* run with micro spilling disabled */
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(4000, getTestAllocator(), true);
          AutoCloseable options =
              with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
      }
    }
  }

  /**
   * Test spilll of 20K rows with very large varchars (10KB-20KB) causing excessive spilling with
   * recursion
   *
   * @throws Exception
   */
  @Test
  public void testSpill20K() throws Exception {
    testSpill20K(getHashAggregate(1_000_000, 12_000_000));
  }

  public void testSpill20K(HashAggregate agg) throws Exception {
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(20000, getTestAllocator(), true);
        AutoCloseable options =
            with(
                VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                false)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
      /* all partitions spilled with recursive spilling -- 20K rows with largeVarChar
       * set to true ends up generating some varchar column values of size
       * 10KB-20KB and so per varchar block in hashtable, we can store only few records
       * and thus the request for having gap in ordinals and adding a new batch
       * keeps on increasing. This is why extremely large number of spills with each
       * partition being spilled multiple times and recursive spilling
       */
      final VectorizedHashAggSpillStats stats = agg.getSpillStats();
      assertTrue(stats.getSpills() > 0);
    }
    /* run with allocator limit same as minimum reservation */
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(20000, getTestAllocator(), true);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
    /* run with micro spilling disabled */
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(20000, getTestAllocator(), true);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
  }

  /**
   * Test spill of 100K rows -- reasonably sized varchars so no recursive spilling
   *
   * @throws Exception
   */
  @Test
  public void testSpill100K() throws Exception {
    testSpill100K(getHashAggregate(1_000_000, 4_000_000, 990), true);
  }

  public void testSpill100K(HashAggregate hashagg, boolean runWithMinMemory) throws Exception {
    final HashAggregate agg = hashagg;
    try (AutoCloseable maxHashTableBatchSizeBytes =
        with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_MAX_BATCHSIZE_BYTES, 128 * 1024)) {
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(100000, getTestAllocator(), false);
          AutoCloseable options =
              with(
                  VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                  false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
        final VectorizedHashAggSpillStats stats = agg.getSpillStats();
        assertTrue(stats.getSpills() > 0);
        assertTrue(stats.getOoms() > 0);
        assertTrue(stats.getIterations() > 0);
        assertEquals(1, stats.getRecursionDepth());
      }
      if (runWithMinMemory) {
        /* run with allocator limit same as minimum reservation */
        try (CustomHashAggDataGenerator generator =
                new CustomHashAggDataGenerator(100000, getTestAllocator(), false);
            AutoCloseable options =
                with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
          Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
          validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
        }
      }
      /* run with micro spilling disabled */
      try (CustomHashAggDataGenerator generator =
              new CustomHashAggDataGenerator(100000, getTestAllocator(), false);
          AutoCloseable options =
              with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
        Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
        validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
      }
    }
  }

  /**
   * Test spill of 1million rows with slightly more memory and no recursive spilling
   *
   * @throws Exception
   */
  @Test
  public void testSpill1M() throws Exception {
    testSpill1M(getHashAggregate(1_000_000, 12_000_000));
  }

  public void testSpill1M(HashAggregate hashagg) throws Exception {
    final HashAggregate agg = hashagg;
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(
                VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                false)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
      final VectorizedHashAggSpillStats stats = agg.getSpillStats();
    }
    /* run with allocator limit same as minimum reservation */
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
    /* run with micro spilling disabled */
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
  }

  /**
   * Similar to previous test with twice as many rows causing recursive spilling
   *
   * @throws Exception
   */
  @Test
  public void testSpill2M() throws Exception {
    testSpill2M(getHashAggregate(1_000_000, 12_000_000));
  }

  public void testSpill2M(HashAggregate hashagg) throws Exception {
    final HashAggregate agg = hashagg;
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(2_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(
                VectorizedHashAggOperator.VECTORIZED_HASHAGG_MINIMIZE_DISTINCT_SPILLED_PARTITIONS,
                false)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
    /* run with allocator limit same as minimum reservation */
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(2_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_USE_MINIMUM_AS_LIMIT, true)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
    /* run with micro spilling disabled */
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(2_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_ENABLE_MICRO_SPILLS, false)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
  }

  @Test
  public void testCloseWithoutSetup() throws Exception {
    final HashAggregate agg = getHashAggregate(1_000_000, 12_000_000);
    SabotContext context = mock(SabotContext.class);
    try (BufferAllocator allocator =
        allocatorRule.newAllocator("test-spilling-hashagg", 0, Long.MAX_VALUE)) {
      when(context.getAllocator()).thenReturn(allocator);
      OptionManager optionManager = mock(OptionManager.class);
      when(optionManager.getOptionValidatorListing())
          .thenReturn(mock(OptionValidatorListing.class));
      try (BufferAllocator alloc =
              context.getAllocator().newChildAllocator("sample-alloc", 0, Long.MAX_VALUE);
          OperatorContextImpl operatorContext =
              new OperatorContextImpl(
                  context.getConfig(),
                  context.getDremioConfig(),
                  alloc,
                  optionManager,
                  1000,
                  context.getExpressionSplitCache());
          final VectorizedHashAggOperator op =
              new VectorizedHashAggOperator(agg, operatorContext)) {
        // test for no exceptions
      }
    }
  }

  @Test
  public void testSpillWithDifferentAllocationThresholds() throws Exception {
    final HashAggregate agg = getHashAggregate(1_000_000, 12_000_000);
    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_JOINT_ALLOCATION_MAX, 16 * 1024)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }

    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_JOINT_ALLOCATION_MAX, 32 * 1024)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }

    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_JOINT_ALLOCATION_MAX, 64 * 1024)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }

    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_JOINT_ALLOCATION_MAX, 128 * 1024)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }

    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_JOINT_ALLOCATION_MAX, 256 * 1024)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }

    try (CustomHashAggDataGenerator generator =
            new CustomHashAggDataGenerator(1_000_000, getTestAllocator(), false);
        AutoCloseable options =
            with(VectorizedHashAggOperator.VECTORIZED_HASHAGG_JOINT_ALLOCATION_MAX, 512 * 1024)) {
      Fixtures.Table table = generator.getExpectedGroupsAndAggregations();
      validateSingle(agg, VectorizedHashAggOperator.class, generator, table, 2000);
    }
  }

  private PivotDef createPivotAndPopulateData(
      final int numUniqueKeys,
      final int inputRecords,
      final String minKey,
      final String maxKey,
      final BatchSchema schema,
      final VectorContainer incoming) {
    IntVector col1 = incoming.addOrGet(CompleteType.INT.toField("col1"));
    VarCharVector col2 = incoming.addOrGet(CompleteType.VARCHAR.toField("col2"));
    IntVector m1 = incoming.addOrGet(CompleteType.INT.toField("m1"));
    VarCharVector m2 = incoming.addOrGet(CompleteType.VARCHAR.toField("m2"));
    VarCharVector m3 = incoming.addOrGet(CompleteType.VARCHAR.toField("m3"));

    int counter = 0;
    for (int i = 0; i < inputRecords; i += 2, ++counter) {
      final String v1 = RandomStringUtils.randomAlphanumeric(2) + String.format("%05d", i);
      col1.setSafe(i, counter);
      col1.setSafe((i + 1), counter);
      col2.setSafe(i, v1.getBytes());
      col2.setSafe((i + 1), v1.getBytes());
    } // populate keys

    for (int i = 0; i < inputRecords; i += 2) {
      m1.setSafe(i, 1);
    }

    // Measure column 2: min varchar accumulator
    for (int i = 0; i < (inputRecords); i += 2) {
      m2.setSafe(i, maxKey.getBytes());
      m2.setSafe(i + 1, minKey.getBytes());
    }

    // Measure column 3: max varchar accumulator
    for (int i = 0; i < inputRecords; i += 2) {
      m3.setSafe(i, minKey.getBytes());
      m3.setSafe(i + 1, maxKey.getBytes());
    }
    final int records = incoming.setAllCount(inputRecords);

    final PivotDef pivot =
        PivotBuilder.getBlockDefinition(
            new FieldVectorPair(col1, col1), new FieldVectorPair(col2, col2));

    return pivot;
  }
}
