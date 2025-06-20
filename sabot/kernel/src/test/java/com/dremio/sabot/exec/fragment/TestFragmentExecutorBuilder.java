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
package com.dremio.sabot.exec.fragment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.OutOfMemoryOrResourceExceptionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.memory.DremioRootAllocator;
import com.dremio.config.DremioConfig;
import com.dremio.exec.compile.CodeCompiler;
import com.dremio.exec.expr.ExpressionSplitCache;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.fragment.CachedFragmentReader;
import com.dremio.exec.planner.fragment.PlanFragmentFull;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.ExecProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.server.NodeDebugContextProvider;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.options.OptionManager;
import com.dremio.sabot.driver.OperatorCreatorRegistry;
import com.dremio.sabot.exec.EventProvider;
import com.dremio.sabot.exec.FragmentExecutors;
import com.dremio.sabot.exec.FragmentTicket;
import com.dremio.sabot.exec.FragmentWorkManager;
import com.dremio.sabot.exec.MaestroProxy;
import com.dremio.sabot.exec.QueriesClerk;
import com.dremio.sabot.exec.QueryTicket;
import com.dremio.sabot.exec.context.ContextInformationFactory;
import com.dremio.sabot.exec.heap.HeapLowMemController;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.spill.SpillService;
import com.dremio.test.DremioTest;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import javax.inject.Provider;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.memory.RootAllocatorFactory;
import org.junit.Assert;
import org.junit.Test;

public class TestFragmentExecutorBuilder extends DremioTest {

  @Test(expected = UserException.class)
  public void testOOMExMessageDuringBuild() throws Exception {
    QueriesClerk queriesClerk = mock(QueriesClerk.class);
    FragmentTicket fragmentTicket = mock(FragmentTicket.class);
    when(queriesClerk.newFragmentTicket(any(), any(), any())).thenReturn(fragmentTicket);
    when(fragmentTicket.newChildAllocator(anyString(), anyLong(), anyLong()))
        .thenThrow(new OutOfMemoryException("No more memory"));

    PlanFragmentFull planFragmentFull = mock(PlanFragmentFull.class);
    when(planFragmentFull.getHandle())
        .thenReturn(
            ExecProtos.FragmentHandle.newBuilder()
                .setMajorFragmentId(0)
                .setMinorFragmentId(0)
                .build());
    when(planFragmentFull.getMemInitial()).thenReturn(0L);
    when(planFragmentFull.getMemInitial()).thenReturn(0L);

    DremioRootAllocator rootAllocator =
        (DremioRootAllocator) RootAllocatorFactory.newRoot(DEFAULT_SABOT_CONFIG);
    FragmentExecutorBuilder fragmentExecutorBuilder =
        new FragmentExecutorBuilder(
            queriesClerk,
            mock(FragmentExecutors.class),
            CoordinationProtos.NodeEndpoint.newBuilder().build(),
            mock(MaestroProxy.class),
            mock(SabotConfig.class),
            mock(DremioConfig.class),
            mock(ClusterCoordinator.class),
            mock(ExecutorService.class),
            mock(OptionManager.class),
            mock(FragmentWorkManager.ExecConnectionCreator.class),
            mock(OperatorCreatorRegistry.class),
            mock(PhysicalPlanReader.class),
            mock(NamespaceService.class),
            mock(CatalogService.class),
            mock(ContextInformationFactory.class),
            mock(FunctionImplementationRegistry.class),
            mock(FunctionImplementationRegistry.class),
            getNodeDebugContext(rootAllocator),
            mock(SpillService.class),
            mock(CodeCompiler.class),
            mock(Set.class),
            mock(Provider.class),
            mock(Provider.class),
            mock(ExpressionSplitCache.class),
            mock(HeapLowMemController.class));

    try {
      fragmentExecutorBuilder.build(
          mock(QueryTicket.class),
          planFragmentFull,
          1,
          null,
          mock(EventProvider.class),
          null,
          mock(CachedFragmentReader.class));
    } catch (UserException ex) {
      Assert.assertEquals(UserBitShared.DremioPBError.ErrorType.OUT_OF_MEMORY, ex.getErrorType());
      OutOfMemoryOrResourceExceptionContext oomExceptionContext =
          OutOfMemoryOrResourceExceptionContext.fromUserException(ex);
      //  Need to mock so that rootAllocator is not null,
      //      Assert.assertTrue(oomExceptionContext != null);
      //      String additionalInfo = oomExceptionContext.getAdditionalInfo();
      //      Assert.assertTrue(additionalInfo.contains("Allocator dominators:\nAllocator(ROOT)"));
      throw ex;
    } finally {
      rootAllocator.close();
    }
  }

  private NodeDebugContextProvider getNodeDebugContext(DremioRootAllocator rootAllocator) {
    SabotContext.NodeDebugContextProviderImpl nodeDebugContextProvider =
        mock(SabotContext.NodeDebugContextProviderImpl.class);

    SabotContext sabotContext = mock(SabotContext.class);
    when(sabotContext.getNodeDebugContext()).thenReturn(nodeDebugContextProvider);
    return sabotContext.getNodeDebugContext();
  }
}
