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
package com.dremio.exec.planner.sql;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCallBinding;

public final class UnionedSqlOperand extends SqlOperand {
  private final ImmutableList<SqlOperand> sqlOperands;

  private UnionedSqlOperand(Type type, ImmutableList<SqlOperand> sqlOperands) {
    super(type);
    this.sqlOperands = Preconditions.checkNotNull(sqlOperands);
  }

  @Override
  public boolean accepts(RelDataType relDataType, SqlCallBinding sqlCallBinding) {
    return sqlOperands.stream()
        .anyMatch(sqlOperand -> sqlOperand.accepts(relDataType, sqlCallBinding));
  }

  public static SqlOperand create(SqlOperand... operands) {
    return create(Arrays.asList(operands));
  }

  public static SqlOperand create(List<SqlOperand> operands) {
    if (operands.size() < 2) {
      throw new IllegalArgumentException("At least two SqlOperands are required.");
    }

    Type type = operands.get(0).type;
    Preconditions.checkArgument(
        operands.stream().allMatch(operand -> operand.type == type),
        "All SqlOperands must have the same type.");
    return new UnionedSqlOperand(type, ImmutableList.copyOf(operands));
  }
}
