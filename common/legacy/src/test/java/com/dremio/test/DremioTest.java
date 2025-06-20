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
package com.dremio.test;

import static com.dremio.common.util.TestToolUtils.readTestResourceAsString;

import com.dremio.common.config.SabotConfig;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.common.util.DremioStringUtils;
import com.dremio.common.util.TestTools;
import com.dremio.config.DremioConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;

/** Basic instrumentation for Dremio's Tests. */
public class DremioTest {

  protected static final ObjectMapper objectMapper;

  static {
    System.setProperty("line.separator", "\n");
    objectMapper = new ObjectMapper();
  }

  /** The default Sabot config. */
  private static final Properties TEST_CONFIGURATIONS =
      new Properties() {
        {
          put("dremio.exec.http.enabled", "false");
          put("dremio.test.parquet.schema.fallback.disabled", "true");
        }
      };

  public static final SabotConfig DEFAULT_SABOT_CONFIG = SabotConfig.create(TEST_CONFIGURATIONS);

  public static final DremioConfig DEFAULT_DREMIO_CONFIG =
      DremioConfig.create(null, DremioTest.DEFAULT_SABOT_CONFIG);

  /** The scan result for the current classpath */
  public static final ScanResult CLASSPATH_SCAN_RESULT =
      ClassPathScanner.fromPrescan(DEFAULT_SABOT_CONFIG);

  static final SystemManager manager = new SystemManager();

  static final Logger testReporter = org.slf4j.LoggerFactory.getLogger("com.dremio.TestReporter");
  static final TestLogReporter LOG_OUTCOME = new TestLogReporter();

  private static MemWatcher memWatcher;
  private static String className;

  @ClassRule
  public static final TestRule CLASS_TIMEOUT = TestTools.getTimeoutRule(1000, TimeUnit.SECONDS);

  @ClassRule
  public static final ClearInlineMocksRule CLEAR_INLINE_MOCKS = new ClearInlineMocksRule();

  @Rule public final TestRule timeoutRule = TestTools.getTimeoutRule(50, TimeUnit.SECONDS);

  @Rule public final TestLogReporter logOutcome = LOG_OUTCOME;

  @Rule public final TestRule repeatRule = TestTools.getRepeatRule(false);

  @Rule public TestName testName = new TestName();

  @Before
  public void printID() throws Exception {
    System.out.printf("Running %s#%s\n", getClass().getName(), testName.getMethodName());
  }

  @BeforeClass
  public static void initDremioTest() throws Exception {
    memWatcher = new MemWatcher();
  }

  @AfterClass
  public static void finiDremioTest() throws InterruptedException {
    testReporter.info(
        String.format("Test Class done (%s): %s.", memWatcher.getMemString(true), className));
    LOG_OUTCOME.sleepIfFailure();
  }

  protected static class MemWatcher {
    private long startDirect;
    private long startHeap;
    private long startNonHeap;

    public MemWatcher() {
      startDirect = manager.getMemDirect();
      startHeap = manager.getMemHeap();
      startNonHeap = manager.getMemNonHeap();
    }

    public Object getMemString() {
      return getMemString(false);
    }

    public String getMemString(boolean runGC) {
      if (runGC) {
        Runtime.getRuntime().gc();
      }
      long endDirect = manager.getMemDirect();
      long endHeap = manager.getMemHeap();
      long endNonHeap = manager.getMemNonHeap();
      return String.format(
          "d: %s(%s), h: %s(%s), nh: %s(%s)", //
          DremioStringUtils.readable(endDirect - startDirect),
          DremioStringUtils.readable(endDirect), //
          DremioStringUtils.readable(endHeap - startHeap),
          DremioStringUtils.readable(endHeap), //
          DremioStringUtils.readable(endNonHeap - startNonHeap),
          DremioStringUtils.readable(endNonHeap) //
          );
    }
  }

  private static final class TestLogReporter extends TestWatcher {

    private MemWatcher memWatcher;
    private int failureCount = 0;

    @Override
    protected void starting(Description description) {
      super.starting(description);
      className = description.getClassName();
      memWatcher = new MemWatcher();
    }

    @Override
    protected void failed(Throwable e, Description description) {
      testReporter.error(
          String.format(
              "Test Failed (%s): %s", memWatcher.getMemString(), description.getDisplayName()),
          e);
      failureCount++;
    }

    @Override
    public void succeeded(Description description) {
      testReporter.info(
          String.format(
              "Test Succeeded (%s): %s", memWatcher.getMemString(), description.getDisplayName()));
    }

    public void sleepIfFailure() throws InterruptedException {
      if (failureCount > 0) {
        Thread.sleep(2000);
        failureCount = 0;
      } else {
        // pause to get logger to catch up.
        Thread.sleep(250);
      }
    }
  }

  public static String escapeJsonString(String original) {
    try {
      return objectMapper.writeValueAsString(original);
    } catch (JsonProcessingException e) {
      return original;
    }
  }

  protected static String readResourceAsString(String fileName) {
    return readTestResourceAsString(fileName);
  }

  private static class SystemManager {

    private final BufferPoolMXBean directBean;
    private final MemoryMXBean memoryBean;

    public SystemManager() {
      memoryBean = ManagementFactory.getMemoryMXBean();
      BufferPoolMXBean localBean = null;
      List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
      for (BufferPoolMXBean b : pools) {
        if (b.getName().equals("direct")) {
          localBean = b;
        }
      }
      directBean = localBean;
    }

    public long getMemDirect() {
      return directBean.getMemoryUsed();
    }

    public long getMemHeap() {
      return memoryBean.getHeapMemoryUsage().getUsed();
    }

    public long getMemNonHeap() {
      return memoryBean.getNonHeapMemoryUsage().getUsed();
    }
  }
}
