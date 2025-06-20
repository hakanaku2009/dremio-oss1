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
package com.dremio.exec.compile;

import com.dremio.common.config.SabotConfig;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.exception.ClassTransformationException;
import com.dremio.exec.expr.ClassGenerator;
import com.dremio.exec.expr.CodeGenerator;
import com.dremio.exec.expr.ExpressionEvalInfo;
import com.dremio.exec.expr.fn.FunctionErrorContext;
import com.dremio.options.OptionManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class CodeCompiler {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(CodeCompiler.class);

  private final ClassTransformer transformer;
  private final ClassCompilerSelector selector;
  private final LoadingCache<CodeGenerator.CodeDefinition<?>, GeneratedClassEntry>
      generatedCodeToCompiledClazzCache;
  private final LoadingCache<ExpressionsHolder, GeneratedClassEntryWithFunctionErrorContextInfo>
      expressionsToCompiledClazzCache;

  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  public CodeCompiler(final SabotConfig config, final OptionManager optionManager) {
    transformer = new ClassTransformer(optionManager);
    selector = new ClassCompilerSelector(config, optionManager);
    final int cacheMaxSize = config.getInt(ExecConstants.MAX_LOADING_CACHE_SIZE_CONFIG);
    generatedCodeToCompiledClazzCache =
        CacheBuilder.newBuilder()
            .softValues()
            .maximumSize(cacheMaxSize)
            .build(new GeneratedCodeToCompiledClazzCacheLoader());
    expressionsToCompiledClazzCache =
        CacheBuilder.newBuilder()
            .softValues()
            .maximumSize(cacheMaxSize)
            .build(new ExpressionsToCompiledClazzCacheLoader());
  }

  @SuppressWarnings("unchecked")
  public <T> T getImplementationClass(final CodeGenerator<?> cg) {
    return (T) getImplementationClass(cg, 1).get(0);
  }

  @SuppressWarnings("unchecked")
  public <T> List<T> getImplementationClass(final CodeGenerator<?> cg, int instanceNumber) {
    try {
      if (cg.getRoot().getExpressionEvalInfos().size() > 0) {
        if (cg.getRoot().doesLazyExpsContainComplexWriterFunctionHolder()) {
          cg.getRoot().evaluateAllLazyExps();
        } else {
          ExpressionsHolder expressionsHolder = new ExpressionsHolder(cg);
          return getImplementationClassFromExpToCompiledClazzCache(
              expressionsHolder, instanceNumber);
        }
      }
      cg.generate();
      final GeneratedClassEntry ce = generatedCodeToCompiledClazzCache.get(cg.getCodeDefinition());
      return getInstances(instanceNumber, ce);
    } catch (ExecutionException
        | InstantiationException
        | IllegalAccessException
        | IOException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw new ClassTransformationException(e);
    }
  }

  public <T> List<T> getImplementationClassFromExpToCompiledClazzCache(
      final ExpressionsHolder expressionsHolder, int instanceNumber) {
    try {
      ClassGenerator<?> rootGenerator = expressionsHolder.cg.getRoot();
      final GeneratedClassEntryWithFunctionErrorContextInfo ce =
          expressionsToCompiledClazzCache.get(expressionsHolder);
      logger.debug("Expressions Cache access with key '{}' and value '{}'", expressionsHolder, ce);
      rootGenerator.registerFunctionErrorContext(
          ce.functionErrorContextsCount, ce.contextIdToFieldIdMap);
      expressionsHolder.cg = null;
      return getInstances(instanceNumber, ce.generatedClassEntry);
    } catch (ExecutionException
        | InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw new ClassTransformationException(e);
    } finally {
      if (expressionsHolder.cg != null) {
        // some error occurred, invalidate key if in cache
        expressionsToCompiledClazzCache.invalidate(expressionsHolder);
        expressionsHolder.cg = null;
      }
    }
  }

  private <T> List<T> getInstances(int instanceNumber, GeneratedClassEntry ce)
      throws InstantiationException,
          IllegalAccessException,
          InvocationTargetException,
          NoSuchMethodException {
    List<T> tList = Lists.newArrayList();
    for (int i = 0; i < instanceNumber; i++) {
      tList.add((T) ce.clazz.getDeclaredConstructor().newInstance());
    }
    return tList;
  }

  @VisibleForTesting
  public void invalidateExpToCompiledClazzCache() {
    this.expressionsToCompiledClazzCache.invalidateAll();
  }

  private class ExpressionsToCompiledClazzCacheLoader
      extends CacheLoader<ExpressionsHolder, GeneratedClassEntryWithFunctionErrorContextInfo> {
    @Override
    public GeneratedClassEntryWithFunctionErrorContextInfo load(
        final ExpressionsHolder expressionsHolder) throws Exception {
      final QueryClassLoader loader = new QueryClassLoader(selector);
      ClassGenerator<?> rootGenerator = expressionsHolder.cg.getRoot();
      // adjust count as the root generator is per operator while the cache entry is per split. So
      // cached counts
      // should only reflect the count of function error contexts in this split.
      final int currentCount = rootGenerator.getFunctionErrorContextsCount();
      CodeGenerator<?> cg = expressionsHolder.cg;
      cg.getRoot().evaluateAllLazyExps();
      cg.generate();
      final CodeGenerator.CodeDefinition<?> cgd = cg.getCodeDefinition();
      final Class<?> c =
          transformer.getImplementationClass(
              loader, cgd.getDefinition(), cgd.getGeneratedCode(), cgd.getMaterializedClassName());
      final GeneratedClassEntryWithFunctionErrorContextInfo ce =
          new GeneratedClassEntryWithFunctionErrorContextInfo(
              c, rootGenerator.getFunctionErrorContexts(currentCount));
      logger.debug("Expressions Cache loaded with key '{}' and value '{}'", expressionsHolder, ce);
      return ce;
    }
  }

  private class GeneratedCodeToCompiledClazzCacheLoader
      extends CacheLoader<CodeGenerator.CodeDefinition<?>, GeneratedClassEntry> {
    @Override
    public GeneratedClassEntry load(final CodeGenerator.CodeDefinition<?> cgd) throws Exception {
      logger.debug("In Cache load; Compile code");
      final QueryClassLoader loader = new QueryClassLoader(selector);
      final Class<?> c =
          transformer.getImplementationClass(
              loader, cgd.getDefinition(), cgd.getGeneratedCode(), cgd.getMaterializedClassName());
      logger.debug("Exit Cache load");
      return new GeneratedClassEntry(c);
    }
  }

  private static class GeneratedClassEntry {
    private final Class<?> clazz;

    public GeneratedClassEntry(final Class<?> clazz) {
      this.clazz = clazz;
    }
  }

  private static class GeneratedClassEntryWithFunctionErrorContextInfo {
    private final GeneratedClassEntry generatedClassEntry;
    private final Map<Integer, Integer> contextIdToFieldIdMap = new HashMap<>();
    private final int functionErrorContextsCount;

    private GeneratedClassEntryWithFunctionErrorContextInfo(
        final Class<?> clazz, Iterator<FunctionErrorContext> errorContexts) {
      int count = 0;
      while (errorContexts.hasNext()) {
        FunctionErrorContext errorContext = errorContexts.next();
        int fieldId = errorContext.getOutputFieldId();
        if (fieldId != -1) {
          contextIdToFieldIdMap.put(count, fieldId);
        }
        ++count;
      }
      this.generatedClassEntry = new GeneratedClassEntry(clazz);
      this.functionErrorContextsCount = count;
    }

    @Override
    public String toString() {
      return "{clazz="
          + generatedClassEntry.clazz.getName()
          + " cnt = "
          + functionErrorContextsCount
          + "}";
    }
  }

  private static class ExpressionsHolder {
    private final List<ExpressionEvalInfo> expressionEvalInfoList;
    // the starting error context idx has to match to avoid a split being matched with another
    // expression's split
    // that is located at a different starting point (say of another operator). As long as the split
    // starts at the
    // same starting point the generated code for the split can be reused even if the split is of
    // another
    // unrelated operator.
    private final int startingErrorContextIdx;

    // This is only used once and we can make it null after that and also this is not used in the
    // equals method,
    // hence keeping it non final and mutable, but rest of the fields are and should be immutable
    private CodeGenerator<?> cg;

    public ExpressionsHolder(CodeGenerator<?> cg) {
      List<ExpressionEvalInfo> expEvalInfos = cg.getRoot().getExpressionEvalInfos();
      startingErrorContextIdx = cg.getFunctionContext().getFunctionErrorContextSize();
      if (expEvalInfos == null) {
        expEvalInfos = Collections.emptyList();
      } else {
        expEvalInfos = ImmutableList.copyOf(expEvalInfos);
      }
      this.expressionEvalInfoList = expEvalInfos;
      this.cg = cg;
    }

    @Override
    public String toString() {
      return "{"
          + expressionEvalInfoList.stream()
              .map(ExpressionEvalInfo::toString)
              .collect(Collectors.joining(" "))
          + "}";
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || this.getClass() != o.getClass()) {
        return false;
      }

      ExpressionsHolder that = (ExpressionsHolder) o;
      return startingErrorContextIdx == that.startingErrorContextIdx
          && expressionEvalInfoList.equals(that.expressionEvalInfoList);
    }

    @Override
    public int hashCode() {
      return Objects.hash(expressionEvalInfoList, startingErrorContextIdx);
    }
  }
}
