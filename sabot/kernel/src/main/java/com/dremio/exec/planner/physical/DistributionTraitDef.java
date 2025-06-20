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
package com.dremio.exec.planner.physical;

import static com.dremio.exec.ExecConstants.ADAPTIVE_HASH;

import com.dremio.exec.planner.physical.DistributionTrait.DistributionType;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.rel.RelNode;

public class DistributionTraitDef extends RelTraitDef<DistributionTrait> {
  public static final DistributionTraitDef INSTANCE = new DistributionTraitDef();

  private DistributionTraitDef() {
    super();
  }

  @Override
  public boolean canConvert(
      RelOptPlanner planner, DistributionTrait fromTrait, DistributionTrait toTrait) {
    return fromTrait.equals(toTrait)
        || fromTrait.getType() != DistributionType.BROADCAST_DISTRIBUTED
        || toTrait.getType() != DistributionType.HASH_DISTRIBUTED;
  }

  @Override
  public Class<DistributionTrait> getTraitClass() {
    return DistributionTrait.class;
  }

  @Override
  public DistributionTrait getDefault() {
    return DistributionTrait.DEFAULT;
  }

  @Override
  public String getSimpleName() {
    return this.getClass().getSimpleName();
  }

  // implement RelTraitDef
  @Override
  public RelNode convert(
      RelOptPlanner planner,
      RelNode rel,
      DistributionTrait toDist,
      boolean allowInfiniteCostConverters) {
    switch (toDist.getType()) {
      // UnionExchange, HashToRandomExchange, OrderedPartitionExchange and BroadcastExchange
      // destroy the ordering property,
      // therefore RelCollation is set to default, which is EMPTY.
      case SINGLETON:
        return new UnionExchangePrel(
            rel.getCluster(), planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist), rel);
      case HASH_DISTRIBUTED:
        return new HashToRandomExchangePrel(
            rel.getCluster(),
            planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist),
            rel,
            toDist.getFields());
      case ADAPTIVE_HASH_DISTRIBUTED:
        boolean supportAdaptiveHash =
            PrelUtil.getPlannerSettings(rel.getCluster()).getOptions().getOption(ADAPTIVE_HASH);
        if (supportAdaptiveHash) {
          return new AdaptiveHashExchangePrel(
              rel.getCluster(),
              planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist),
              rel,
              toDist.getFields());
        } else {
          return new HashToRandomExchangePrel(
              rel.getCluster(),
              planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist),
              rel,
              toDist.getFields());
        }
      case RANGE_DISTRIBUTED:
        return new OrderedPartitionExchangePrel(
            rel.getCluster(), planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist), rel);
      case BROADCAST_DISTRIBUTED:
        return new BroadcastExchangePrel(
            rel.getCluster(), planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist), rel);
      case ROUND_ROBIN_DISTRIBUTED:
        return new RoundRobinExchangePrel(
            rel.getCluster(), planner.emptyTraitSet().plus(Prel.PHYSICAL).plus(toDist), rel);
      case ANY:
        // If target is "any", any input would satisfy "any". Return input directly.
        return rel;
      default:
        return null;
    }
  }
}
