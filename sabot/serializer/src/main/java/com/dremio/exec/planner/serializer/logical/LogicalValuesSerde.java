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
package com.dremio.exec.planner.serializer.logical;

import com.dremio.exec.planner.serializer.RelNodeSerde;
import com.dremio.plan.serialization.PLogicalValues;
import com.dremio.plan.serialization.PLogicalValues.PLogicalValueTuple;
import com.google.common.collect.ImmutableList;
import java.util.stream.Collectors;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rex.RexLiteral;

/** Serde for LogicalValues */
public final class LogicalValuesSerde implements RelNodeSerde<LogicalValues, PLogicalValues> {
  @Override
  public PLogicalValues serialize(LogicalValues values, RelToProto s) {
    return PLogicalValues.newBuilder()
        .addAllFields(s.toProto(values.getRowType().getFieldList()))
        .addAllTuples(
            values.tuples.stream()
                .map(
                    c ->
                        PLogicalValueTuple.newBuilder()
                            .addAllLiteral(
                                c.asList().stream().map(s::toProto).collect(Collectors.toList()))
                            .build())
                .collect(Collectors.toList()))
        .build();
  }

  @Override
  public LogicalValues deserialize(PLogicalValues node, RelFromProto s) {
    return (LogicalValues)
        s.builder()
            .values(
                node.getTuplesList().stream()
                    .map(
                        rec ->
                            rec.getLiteralList().stream()
                                .map(s::toRex)
                                .map(RexLiteral.class::cast)
                                .collect(ImmutableList.toImmutableList()))
                    .collect(ImmutableList.toImmutableList()),
                s.toRowType(node.getFieldsList()))
            .build();
  }
}
