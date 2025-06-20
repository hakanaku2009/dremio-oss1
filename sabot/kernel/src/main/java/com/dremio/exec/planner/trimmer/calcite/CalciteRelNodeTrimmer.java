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
package com.dremio.exec.planner.trimmer.calcite;

import com.dremio.exec.planner.trimmer.RelNodeTrimmer;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilderFactory;

public final class CalciteRelNodeTrimmer implements RelNodeTrimmer {
  private final DremioFieldTrimmerParameters parameters;

  public CalciteRelNodeTrimmer(DremioFieldTrimmerParameters parameters) {
    this.parameters = parameters;
  }

  @Override
  public RelNode trim(RelNode relNode, RelBuilderFactory relBuilderFactory) {
    DremioFieldTrimmer dremioFieldTrimmer =
        new DremioFieldTrimmer(relBuilderFactory.create(relNode.getCluster(), null), parameters);
    return dremioFieldTrimmer.trim(relNode);
  }
}
