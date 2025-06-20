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
package com.dremio.exec.store.dfs.easy;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.spi.SingleInputOperator;

public class EasyWriterOperatorCreator implements SingleInputOperator.Creator<EasyWriter> {
  static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(EasyWriterOperatorCreator.class);

  @Override
  public SingleInputOperator create(OperatorContext context, EasyWriter operator)
      throws ExecutionSetupException {
    return operator.getEasyFormatPlugin().getWriterBatch(context, operator);
  }
}
