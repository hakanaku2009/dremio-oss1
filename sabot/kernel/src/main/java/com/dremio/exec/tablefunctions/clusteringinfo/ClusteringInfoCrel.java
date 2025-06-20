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
package com.dremio.exec.tablefunctions.clusteringinfo;

import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;

/** Crel implementation of clustering_information table function. */
public class ClusteringInfoCrel extends ClusteringInfoRelBase {

  public ClusteringInfoCrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      ClusteringInfoContext context,
      ClusteringInfoCatalogMetadata metadata) {
    super(cluster, traitSet, context, metadata);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new ClusteringInfoCrel(
        getCluster(), traitSet, getContext(), getClusteringInfoMetadata());
  }
}
