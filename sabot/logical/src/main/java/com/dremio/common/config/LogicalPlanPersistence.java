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
package com.dremio.common.config;

import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.common.logical.FormatPluginConfigBase;
import com.dremio.common.logical.StoragePluginConfigBase;
import com.dremio.common.logical.data.LogicalOperator;
import com.dremio.common.logical.data.LogicalOperatorBase;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.common.store.StoragePluginConfig;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class LogicalPlanPersistence {
  private ObjectMapper mapper;

  public ObjectMapper getMapper() {
    return mapper;
  }

  @Inject
  public LogicalPlanPersistence(ScanResult scanResult) {
    this(
        LogicalOperatorBase.getSubTypes(scanResult),
        StoragePluginConfigBase.getSubTypes(scanResult),
        FormatPluginConfigBase.getSubTypes(scanResult));
  }

  /** Constructor use sets extract from scan result. */
  public LogicalPlanPersistence(
      Set<Class<? extends LogicalOperator>> logicalOperatorSubTypes,
      Set<Class<? extends StoragePluginConfig>> storagePluginSubTypes,
      Set<Class<? extends FormatPluginConfig>> formatPluginSubTypes) {
    mapper = new ObjectMapper();

    SimpleModule deserModule =
        new SimpleModule("LogicalExpressionDeserializationModule")
            .addDeserializer(LogicalExpression.class, new LogicalExpression.De())
            .addDeserializer(SchemaPath.class, new SchemaPath.De())
            .addDeserializer(FieldReference.class, new FieldReference.De());

    mapper.registerModule(new AfterburnerModule());
    mapper.registerModule(deserModule);
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    mapper.configure(Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    mapper.configure(JsonGenerator.Feature.QUOTE_FIELD_NAMES, true);
    mapper.configure(Feature.ALLOW_COMMENTS, true);
    logicalOperatorSubTypes.forEach(mapper::registerSubtypes);
    storagePluginSubTypes.forEach(mapper::registerSubtypes);
    formatPluginSubTypes.forEach(mapper::registerSubtypes);
  }
}
