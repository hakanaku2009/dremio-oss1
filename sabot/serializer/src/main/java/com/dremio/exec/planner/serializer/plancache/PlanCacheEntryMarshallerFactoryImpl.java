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
package com.dremio.exec.planner.serializer.plancache;

import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.plancache.distributable.PlanCacheEntryMarshaller;
import com.dremio.exec.planner.serializer.RelSerdeRegistry;
import com.dremio.exec.planner.serializer.RexDeserializer;
import com.dremio.exec.planner.serializer.RexSerializer;
import com.dremio.exec.planner.serializer.SqlOperatorSerde;
import com.dremio.exec.planner.serializer.TypeSerde;
import com.dremio.exec.planner.serializer.catalog.CatalogSerializer;
import com.dremio.exec.planner.serializer.physical.PrelSerializer;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.sql.SqlOperatorTable;

public class PlanCacheEntryMarshallerFactoryImpl implements PlanCacheEntryMarshaller.Factory {

  @Override
  public PlanCacheEntryMarshaller build(
      PhysicalPlanReader physicalPlanReader,
      SqlOperatorTable sqlOperatorTable,
      RelOptCluster relOptCluster) {

    PrelSerializer prelSerializer =
        buildPrelSerializer(physicalPlanReader, sqlOperatorTable, relOptCluster);
    PlanCacheEntrySerializer planCacheEntrySerializer =
        new PlanCacheEntrySerializer(prelSerializer);
    return new PlanCacheEntryMarshallerImpl(planCacheEntrySerializer);
  }

  public PrelSerializer buildPrelSerializer(
      PhysicalPlanReader physicalPlanReader,
      SqlOperatorTable sqlOperatorTable,
      RelOptCluster relOptCluster) {
    TypeSerde typeSerde = new TypeSerde(relOptCluster.getTypeFactory());
    SqlOperatorSerde sqlOperatorSerde = new SqlOperatorSerde(sqlOperatorTable);

    RexSerializer rexSerializer =
        new RexSerializer(
            relOptCluster.getRexBuilder(), typeSerde, RelSerdeRegistry.EMPTY, sqlOperatorSerde);
    RexDeserializer rexDeserializer =
        new RexDeserializer(
            relOptCluster.getRexBuilder(),
            typeSerde,
            RelSerdeRegistry.EMPTY,
            null,
            null,
            relOptCluster,
            new SqlOperatorSerde(sqlOperatorTable));
    CatalogSerializer catalogSerializer =
        new CatalogSerializer(
            physicalPlanReader.getPhysicalPlanReader(), physicalPlanReader.getMapper().writer());

    return new PrelSerializer(
        relOptCluster,
        rexSerializer,
        rexDeserializer,
        typeSerde,
        sqlOperatorSerde,
        catalogSerializer);
  }
}
