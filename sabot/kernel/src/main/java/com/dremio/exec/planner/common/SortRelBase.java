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
package com.dremio.exec.planner.common;

import java.util.Objects;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Sort;

/** Base class for logical and physical Sort implemented in Dremio */
public abstract class SortRelBase extends Sort {
  /** Creates a SortRel with offset and fetch. */
  protected SortRelBase(
      RelOptCluster cluster, RelTraitSet traits, RelNode input, RelCollation collation) {
    super(cluster, traits, input, extractRelCollation(traits, collation), null, null);
    // Dremio does not support offset and fetch
    assert offset == null : "offset is not supported";
    assert fetch == null : "fetch is not supported";
  }

  protected static RelTraitSet adjustTraits(RelTraitSet traits, RelCollation collation) {
    return traits.plus(collation);
  }

  private static RelCollation extractRelCollation(RelTraitSet traits, RelCollation relCollation) {
    RelCollation relCollationFromTrait = traits.getTrait(RelCollationTraitDef.INSTANCE);
    if (!Objects.equals(relCollationFromTrait, relCollation)) {
      throw new IllegalArgumentException(
          "RelCollationTraitDef is not consistent with the provided RelCollation. Trait: "
              + relCollationFromTrait
              + ", RelCollation: "
              + relCollation);
    }
    // The collation from the trait needs to be returned due to a reference comparison in Sort.
    return relCollationFromTrait;
  }
}
