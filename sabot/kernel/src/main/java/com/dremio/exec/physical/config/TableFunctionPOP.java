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
package com.dremio.exec.physical.config;

import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.ImmutableSet;
import java.util.Collections;
import java.util.Set;

/** POP for table function */
@JsonTypeName("table-function")
public class TableFunctionPOP extends AbstractTableFunctionPOP {

  @JsonCreator
  public TableFunctionPOP(
      @JsonProperty("props") OpProps props,
      @JsonProperty("child") PhysicalOperator child,
      @JsonProperty("function") TableFunctionConfig config) {
    super(props, child, config);
  }

  @Override
  protected PhysicalOperator getNewWithChild(PhysicalOperator child) {
    return new TableFunctionPOP(this.props, child, function);
  }

  @Override
  public PhysicalOperator getNewWithConfig(TableFunctionConfig config) {
    return new TableFunctionPOP(this.props, child, config);
  }

  /**
   * The visible fragment assignment in OperatorContext is based on the return of this function. In
   * DataFileGroupingTableFunction, we need to send clustering status OOB message to
   * WriterCommitterOperator. Hence, we need to return WriterCommitterOperator's major fragment id
   * so that DataFileGroupingTableFunction's operatorContext can create the tunnel to
   * WriterCommitterOperator in
   */
  @Override
  public Set<Integer> getExtCommunicableMajorFragments() {
    if (function.getFunctionContext() instanceof DataFileGroupingTableFunctionContext
        && ((DataFileGroupingTableFunctionContext) function.getFunctionContext())
                .getTargetClusteringStatusReceiver()
            != null) {
      return ImmutableSet.of(
          ((DataFileGroupingTableFunctionContext) function.getFunctionContext())
              .getTargetClusteringStatusReceiver()
              .getMajorFragmentId());
    }
    return Collections.emptySet();
  }
}
