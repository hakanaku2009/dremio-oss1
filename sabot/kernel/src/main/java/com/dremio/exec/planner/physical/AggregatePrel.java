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

import com.dremio.common.logical.data.NamedExpression;
import com.dremio.exec.expr.fn.ItemsSketch.ItemsSketchFunctions;
import com.dremio.exec.expr.fn.tdigest.TDigest;
import com.dremio.exec.planner.StatelessRelShuttleImpl;
import com.dremio.exec.planner.common.AggregateRelBase;
import com.dremio.exec.planner.common.JdbcRelBase;
import com.dremio.exec.planner.logical.RexToExpr;
import com.dremio.exec.planner.physical.DistributionTrait.DistributionField;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.planner.sql.DremioSqlOperatorTable;
import com.dremio.service.Pointer;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;

public abstract class AggregatePrel extends AggregateRelBase implements Prel {

  public enum OperatorPhase {
    PHASE_1of1,
    PHASE_1of2,
    PHASE_2of2
  }

  protected OperatorPhase operPhase;
  protected List<NamedExpression> keys;
  protected List<NamedExpression> aggExprs;
  protected List<Pair<AggregateCall, RexLiteral>> phase2AggCallList = new ArrayList<>();

  protected AggregatePrel(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      ImmutableBitSet groupSet,
      List<AggregateCall> aggCalls,
      OperatorPhase phase)
      throws InvalidRelException {
    super(cluster, traits, child, groupSet, null, aggCalls);
    this.operPhase = phase;
    this.keys = RexToExpr.groupSetToExpr(child, groupSet);
    this.aggExprs = RexToExpr.aggCallsToExpr(getRowType(), child, groupSet, aggCalls);
    int delimiterCount = 0;
    int numInputFields = groupSet.cardinality() + aggCalls.size();

    for (Ord<AggregateCall> aggCall : Ord.zip(aggCalls)) {
      int aggExprOrdinal = groupSet.cardinality() + aggCall.i;
      if (getOperatorPhase() == OperatorPhase.PHASE_1of2) {
        if (aggCall.e.getAggregation().getName().equals(SqlStdOperatorTable.COUNT.getName())) {
          // If we are doing a COUNT aggregate in Phase1of2, then in Phase2of2 we should SUM the
          // COUNTs,
          SqlAggFunction sumAggFun = SqlStdOperatorTable.SUM0;
          AggregateCall newAggCall =
              AggregateCall.create(
                  sumAggFun,
                  aggCall.e.isDistinct(),
                  false,
                  Collections.singletonList(aggExprOrdinal),
                  -1,
                  aggCall.e.getType(),
                  aggCall.e.getName());

          phase2AggCallList.add(Pair.of(newAggCall, null));
        } else if (aggCall
            .e
            .getAggregation()
            .getName()
            .equals(DremioSqlOperatorTable.HLL.getName())) {
          SqlAggFunction hllMergeFunction = DremioSqlOperatorTable.HLL_MERGE;
          AggregateCall newAggCall =
              AggregateCall.create(
                  hllMergeFunction,
                  aggCall.e.isDistinct(),
                  // The function is by definition approximate, so we don't need to say this is the
                  // approximate variant.
                  false,
                  Collections.singletonList(aggExprOrdinal),
                  -1,
                  aggCall.getValue().getType(),
                  aggCall.e.getName());
          phase2AggCallList.add(Pair.of(newAggCall, null));
        } else if (aggCall.e.getAggregation().getName().equals("TDIGEST")) {
          SqlAggFunction tDigestMergeFunction =
              new TDigest.SqlTDigestMergeAggFunction(aggCall.e.getType());
          AggregateCall newAggCall =
              AggregateCall.create(
                  tDigestMergeFunction,
                  aggCall.e.isDistinct(),
                  // The function is by definition approximate, so we don't need to say this is the
                  // approximate variant.
                  false,
                  Collections.singletonList(aggExprOrdinal),
                  -1,
                  aggCall.e.getType(),
                  aggCall.e.getName());
          phase2AggCallList.add(Pair.of(newAggCall, null));
        } else if (aggCall
            .e
            .getAggregation()
            .getName()
            .equalsIgnoreCase(ItemsSketchFunctions.FUNCTION_NAME)) {
          int fieldInd = aggCall.e.getArgList().get(0);
          RelDataType type = input.getRowType().getFieldList().get(fieldInd).getType();
          SqlAggFunction function;
          switch (type.getSqlTypeName()) {
            case BOOLEAN:
              function =
                  new ItemsSketchFunctions.SqlItemsSketchMergeBooleanAggFunction(
                      aggCall.e.getType());
              break;
            case DOUBLE:
            case DECIMAL:
              function =
                  new ItemsSketchFunctions.SqlItemsSketchMergeDoubleAggFunction(
                      aggCall.e.getType());
              break;
            case VARCHAR:
              function =
                  new ItemsSketchFunctions.SqlItemsSketchMergeVarCharAggFunction(
                      aggCall.e.getType());
              break;
            case FLOAT:
            case INTEGER:
            case SMALLINT:
            case TINYINT:
            case TIME:
            case INTERVAL_YEAR:
            case INTERVAL_DAY:
            case INTERVAL_DAY_SECOND:
            case INTERVAL_YEAR_MONTH:
              function =
                  new ItemsSketchFunctions.SqlItemsSketchMergeNumbersAggFunction(
                      aggCall.e.getType());
              break;
            case BIGINT:
            case DATE:
            case TIMESTAMP:
              function =
                  new ItemsSketchFunctions.SqlItemsSketchMergeLongAggFunction(aggCall.e.getType());
              break;
            default:
              throw new UnsupportedOperationException(
                  String.format(
                      "Cannot merge items_sketch functions for dataType, %s.",
                      type.getSqlTypeName().getName()));
          }
          AggregateCall newAggCall =
              AggregateCall.create(
                  function,
                  aggCall.e.isDistinct(),
                  // The function is by definition approximate, so we don't need to say this is the
                  // approximate variant.
                  false,
                  Collections.singletonList(aggExprOrdinal),
                  -1,
                  aggCall.e.getType(),
                  aggCall.e.getName());
          phase2AggCallList.add(Pair.of(newAggCall, null));
        } else if (aggCall.e.getAggregation().getKind() == SqlKind.LISTAGG) {
          // create 2 phase list agg. This process requires figuring out extra delimiter argument in
          // the function call
          // as we need this information to create the second phase argument.
          // Also, new argument index and collation index should be aware of this field.
          final List<Integer> phase2Args = new ArrayList<>();
          RexLiteral delimiter = null;
          phase2Args.add(aggExprOrdinal);
          if (aggCall.e.getArgList().size() == 2) {
            delimiter = findDelimiterInListAgg(aggCall.e, input);
            if (delimiter == null) {
              throw new UnsupportedOperationException(
                  "Cannot create 2 phase aggregate with the delimiter added to ListAgg call in the plan");
            }
            phase2Args.add(numInputFields + delimiterCount);
            delimiterCount++;
          }

          // update collation fields
          final RelCollation oldCollation = aggCall.e.collation;
          RelCollation newColation;
          if (oldCollation.getFieldCollations().isEmpty()) {
            newColation = RelCollations.EMPTY;
          } else {
            Pair<Integer, Integer> mapping =
                new Pair<>(aggCall.e.getArgList().get(0), aggExprOrdinal);
            newColation =
                RelCollations.of(
                    oldCollation.getFieldCollations().stream()
                        .map(
                            collation -> {
                              if (collation.getFieldIndex() != mapping.getKey()) {
                                throw new UnsupportedOperationException(
                                    "ORDER BY columns must be subset LISTAGG columns");
                              }
                              return collation.copy(mapping.getValue());
                            })
                        .collect(Collectors.toList()));
          }
          SqlAggFunction listAggMerge = DremioSqlOperatorTable.LISTAGG_MERGE;
          AggregateCall newAggCall =
              AggregateCall.create(
                  listAggMerge,
                  aggCall.e.isDistinct(),
                  aggCall.e.isApproximate(),
                  phase2Args,
                  -1,
                  newColation,
                  DremioSqlOperatorTable.LISTAGG_MERGE.inferReturnType(
                      getCluster().getTypeFactory(), ImmutableList.of(aggCall.e.getType())),
                  aggCall.e.getName());
          phase2AggCallList.add(Pair.of(newAggCall, delimiter));
        } else {
          AggregateCall newAggCall =
              AggregateCall.create(
                  aggCall.e.getAggregation(),
                  aggCall.e.isDistinct(),
                  aggCall.e.isApproximate(),
                  Collections.singletonList(aggExprOrdinal),
                  -1,
                  aggCall.e.getType(),
                  aggCall.e.getName());

          phase2AggCallList.add(Pair.of(newAggCall, null));
        }
      }
    }
  }

  /**
   * This call finds delimiter literal used in a ListAgg call.
   *
   * <p>For example, in the following plan, AggregateCall($0,$1) Project($0, RexLiteral=',')
   *
   * <p>This call should return ',' literal as an output.
   *
   * @param call an AggregateCall
   * @return a delimiter literal
   */
  public static RexLiteral findDelimiterInListAgg(AggregateCall call, RelNode input) {
    if (call.getAggregation().getKind() != SqlKind.LISTAGG || call.getArgList().size() != 2) {
      return null;
    }

    final Pointer<Integer> index = new Pointer<>(call.getArgList().get(1));
    final Pointer<RexLiteral> literal = new Pointer<>(null);
    input.accept(
        new StatelessRelShuttleImpl() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof RelSubset) {
              RelNode rel = ((RelSubset) other).getOriginal();
              return rel == null ? other : rel.accept(this);
            } else if (other instanceof HepRelVertex) {
              RelNode rel = ((HepRelVertex) other).getCurrentRel();
              return rel == null ? other : rel.accept(this);
            } else if (other instanceof JdbcRelBase) {
              RelNode subTree = ((JdbcRelBase) other).getSubTree();
              return subTree == null ? other : subTree.accept(this);
            } else if (other instanceof Project) {
              List<RexNode> exprs = ((Project) other).getProjects();
              if (index.value >= exprs.size()) {
                // this case cannot happen with valid plan. If happens then stop here.
                return other;
              }
              RexNode expr = exprs.get(index.value);
              if (expr instanceof RexLiteral) {
                literal.value = (RexLiteral) expr;
                return other;
              } else if (expr instanceof RexInputRef) {
                // could not find the actual literal. Keep tracing down with updated index.
                index.value = ((RexInputRef) expr).getIndex();
              } else {
                return other;
              }
            }
            return super.visit(other);
          }
        });
    return literal.value;
  }

  protected static RelTraitSet adjustTraits(
      RelTraitSet traits, RelNode child, ImmutableBitSet groupSet) {
    return adjustTraits(traits)
        .replaceIf(
            DistributionTraitDef.INSTANCE,
            () -> {
              final PlannerSettings settings =
                  PrelUtil.getPlannerSettings(child.getCluster().getPlanner());
              if (!settings.shouldPullDistributionTrait()) {
                // Do not change distribution trait (even if not valid from a planner point of view)
                return traits.getTrait(DistributionTraitDef.INSTANCE);
              }
              // distribution might need to be adjusted to match column remapping
              DistributionTrait distribution =
                  Optional.ofNullable(child.getTraitSet().getTrait(DistributionTraitDef.INSTANCE))
                      .orElse(DistributionTrait.ANY);

              final ImmutableList<DistributionField> inputFields = distribution.getFields();
              if (inputFields == null || inputFields.isEmpty()) {
                // if no distribution fields, no remapping necessary
                return distribution;
              }

              // Mapping input fields -> groups
              final Map<Integer, Integer> mapping = new HashMap<>();
              int groupIndex = 0;
              for (int field : groupSet) {
                mapping.put(field, groupIndex);
                groupIndex++;
              }

              final ImmutableList.Builder<DistributionField> builder = ImmutableList.builder();
              for (DistributionField inputField : inputFields) {
                Integer target = mapping.get(inputField.getFieldId());
                if (target == null) {
                  // if target not found, it means not distributed on a group column
                  // so field is not preserved
                  continue;
                }
                builder.add(new DistributionField(mapping.get(inputField.getFieldId())));
              }
              // If no fields preserved, no point preserving the distribution type
              final ImmutableList<DistributionField> fields = builder.build();
              if (fields.isEmpty()) {
                return DistributionTrait.DEFAULT;
              }
              return new DistributionTrait(distribution.getType(), fields);
            });
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .itemIf(
            "OperatorPhase", operPhase, operPhase != null && operPhase != OperatorPhase.PHASE_1of1);
  }

  public OperatorPhase getOperatorPhase() {
    return operPhase;
  }

  public List<NamedExpression> getKeys() {
    return keys;
  }

  public List<NamedExpression> getAggExprs() {
    return aggExprs;
  }

  public List<Pair<AggregateCall, RexLiteral>> getPhase2AggCalls() {
    return phase2AggCallList;
  }

  public ImmutableBitSet getPhase2GroupSet() {
    return ImmutableBitSet.range(0, groupSet.cardinality());
  }

  @Override
  public Iterator<Prel> iterator() {
    return PrelUtil.iter(getInput());
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitAggregate(this, value);
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return true;
  }
}
