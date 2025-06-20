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
package com.dremio.exec.planner.transpose;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.tools.RelBuilder;

public final class FilterFilterTransposeRule extends RelRule<FilterFilterTransposeRule.Config>
    implements TransformationRule {
  private FilterFilterTransposeRule(FilterFilterTransposeRule.Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Filter topFilter = call.rel(0);
    Filter bottomFilter = call.rel(1);

    RelBuilder relBuilder = call.builder();

    RelNode transposed =
        relBuilder
            .push(bottomFilter.getInput())
            .filter(topFilter.getCondition())
            .filter(bottomFilter.getCondition())
            .build();

    call.transformTo(transposed);
  }

  public interface Config extends RelRule.Config {
    FilterFilterTransposeRule.Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription("FilterFilterTransposeRule")
            .withOperandSupplier(
                op ->
                    op.operand(Filter.class)
                        .oneInput(filter -> filter.operand(Filter.class).anyInputs()))
            .as(FilterFilterTransposeRule.Config.class);

    @Override
    default FilterFilterTransposeRule toRule() {
      return new FilterFilterTransposeRule(this);
    }
  }
}
