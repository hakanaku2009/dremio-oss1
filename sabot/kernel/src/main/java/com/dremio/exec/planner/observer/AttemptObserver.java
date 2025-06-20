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
package com.dremio.exec.planner.observer;

import com.dremio.common.exceptions.UserCancellationException;
import com.dremio.common.utils.protos.QueryWritableBatch;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.RelWithInfo;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionInfo;
import com.dremio.exec.planner.fragment.PlanningSet;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.plancache.PlanCacheEntry;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.proto.UserBitShared.AccelerationProfile;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.FragmentRpcSizeStats;
import com.dremio.exec.proto.UserBitShared.LayoutMaterializedViewProfile;
import com.dremio.exec.proto.UserBitShared.PlannerPhaseRulesStats;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.exec.work.QueryWorkUnit;
import com.dremio.exec.work.foreman.ExecutionPlan;
import com.dremio.exec.work.protector.UserRequest;
import com.dremio.exec.work.protector.UserResult;
import com.dremio.reflection.hints.ReflectionExplanationsAndQueryDistance;
import com.dremio.resource.GroupResourceInformation;
import com.dremio.resource.ResourceSchedulingDecisionInfo;
import java.util.List;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlNode;

public interface AttemptObserver {

  /**
   * Called to report the beginning of a new state. Called multiple times during a query lifetime
   */
  void beginState(AttemptEvent event);

  /**
   * Query was started.
   *
   * @param query Query configuration
   * @param user User.
   */
  void queryStarted(UserRequest query, String user);

  /**
   * Called to report the wait in the command pool. May be called multiple times during a query
   * lifetime, as often as the query's tasks are put into the command pool
   */
  void commandPoolWait(long waitInMillis);

  /**
   * Planning started using provided plan.
   *
   * @param rawPlan Typically SQL but could also be a logical or physical plan.
   */
  void planStart(String rawPlan);

  /**
   * Resource information about executors collected for planning.
   *
   * @param resourceInformation
   * @param millisTaken
   */
  void resourcesPlanned(GroupResourceInformation resourceInformation, long millisTaken);

  /**
   * Parsing of query completed and validated.
   *
   * @param rowType The validated row type.
   * @param node The AST of the validated SQL
   * @param millisTaken
   * @param isMaterializationCacheInitialized
   */
  void planValidated(
      RelDataType rowType,
      SqlNode node,
      long millisTaken,
      boolean isMaterializationCacheInitialized);

  /**
   * Generic method to provide information about some step within planning.
   *
   * @param phaseName
   * @param text
   * @param millisTaken
   */
  void planStepLogging(String phaseName, String text, long millisTaken);

  /** Adding updated Acceleration profile into cached plan */
  default void addAccelerationProfileToCachedPlan(PlanCacheEntry plan) {}

  /** Sets the cached acceleration profile that the profile should show */
  default void restoreAccelerationProfileFromCachedPlan(AccelerationProfile accelerationProfile) {}

  /**
   * Plan that is serializable, just before convertible scans are converted
   *
   * @param serializable
   */
  void planSerializable(RelNode serializable);

  /**
   * Convert validated query to rel tree
   *
   * @param converted rel tree generated from validated query
   * @param millisTaken
   */
  void planConvertedToRel(RelNode converted, long millisTaken);

  /**
   * Generic ability to record extra information in a job.
   *
   * @param name The name of the extra info. This can be thought of as a list rather than set and
   *     calls with the same name will all be recorded.
   * @param bytes The data to persist.
   */
  void recordExtraInfo(String name, byte[] bytes);

  /**
   * Convert Scan query
   *
   * @param converted rel tree generated from query
   * @param millisTaken
   */
  void planConvertedScan(RelNode converted, long millisTaken);

  /**
   * Display the refresh decision - full refresh, incremental refresh, incremental refresh by
   * partition, etc.
   *
   * @param text Decision text
   * @param millisTaken time taken in planning
   */
  public void planRefreshDecision(String text, long millisTaken);

  /**
   * A view just expanded into a rel tree.
   *
   * @param expanded The new rel tree that will be used in place of the defined view.
   * @param schemaPath The schema path of the view.
   * @param nestingLevel The amount of nesting of the view.
   * @param sql The sql associated with the view.
   */
  void planExpandView(RelRoot expanded, List<String> schemaPath, int nestingLevel, String sql);

  /**
   * Called multiple times, describing transformations that occurred during planning.
   *
   * @param phase The phase of planning that was run.
   * @param planner The planner used to do this transformation.
   * @param before The graph before the transformation occurred.
   * @param after The graph after the planning transformation took place
   * @param millisTaken The amount of time taken to complete the planning.
   * @param rulesBreakdownStats Breakdown of time spent by different rules.
   */
  void planRelTransform(
      PlannerPhase phase,
      RelOptPlanner planner,
      RelNode before,
      RelNode after,
      long millisTaken,
      List<PlannerPhaseRulesStats> rulesBreakdownStats);

  /**
   * Called when all tables have been collected from the plan
   *
   * @param tables all dremio tables requested from the Catalog during planning
   */
  void tablesCollected(Iterable<DremioTable> tables);

  /**
   * The text of the final query plan was produced and break down of time taken by each visitor.
   *
   * @param text Text based explain plan.
   * @param millisTaken Total time taken by final physical transformation.
   * @param stats Time taken by each visitor during final physical transformation.
   */
  void planFinalPhysical(String text, long millisTaken, List<PlannerPhaseRulesStats> stats);

  void finalPrelPlanGenerated(Prel prel);

  /** Parallelization planning started */
  void planParallelStart();

  /**
   * The decisions made for parallelizations and fragments were completed.
   *
   * @param planningSet
   */
  void planParallelized(PlanningSet planningSet);

  /**
   * The decisions for distribution of work are completed.
   *
   * @param unit The distribution decided for each node.
   */
  void plansDistributionComplete(QueryWorkUnit unit);

  /** Report considered materializations */
  void planFindMaterializations(long millisTaken);

  /** Report normalization completion */
  void planNormalized(long millisTaken, List<RelWithInfo> normalizedQueryPlans);

  /** Report BUPFinder time across all materializations excluding normalization times */
  void planSubstituted(long millisTaken);

  /**
   * Report considered target materializations.
   *
   * @param profile
   * @param target
   */
  void planConsidered(LayoutMaterializedViewProfile profile, RelWithInfo target);

  /**
   * Report substitution matches.
   *
   * @param materialization
   * @param substitutions
   * @param target
   * @param millisTaken - Time to generate the match including query and target normalization
   * @param defaultReflection
   */
  void planSubstituted(
      DremioMaterialization materialization,
      List<RelWithInfo> substitutions,
      RelWithInfo target,
      long millisTaken,
      boolean defaultReflection);

  /**
   * Report errors occurred during substitution.
   *
   * @param errors all errors occurred during substitution
   */
  void substitutionFailures(Iterable<String> errors);

  /**
   * Report materializations that have been chosen by VolcanoPlanner to accelerate query.
   *
   * @param info acceleration info.
   */
  void planAccelerated(SubstitutionInfo info);

  /**
   * The planning and parallelization phase of the query is completed.
   *
   * @param plan The {@link ExecutionPlan execution plan} provided to the observer
   * @param batchSchema only used when plan is null
   */
  void planCompleted(ExecutionPlan plan, BatchSchema batchSchema);

  /**
   * The execution of the query started.
   *
   * @param profile The initial query profile for the query.
   */
  void execStarted(QueryProfile profile);

  /**
   * Some data is now returned from the query.
   *
   * @param outcomeListener Listener used to inform that observer is done consuming data.
   * @param result The data to consume.
   */
  void execDataArrived(RpcOutcomeListener<Ack> outcomeListener, QueryWritableBatch result);

  @Deprecated
  /**
   * Exists due to existing stuff but needs to be removed.
   *
   * @param text
   */
  void planJsonPlan(String text);

  /**
   * The current query attempt is completed and has been cleaned up. Another attempt may be started
   * after this one, but will use a new instance of AttemptObserver
   *
   * @param result The result of the query.
   */
  void attemptCompletion(UserResult result);

  void putExecutorProfile(String nodeEndpoint);

  void removeExecutorProfile(String nodeEndpoint);

  void queryClosed();

  /** Executor nodes were selected for the query */
  void executorsSelected(
      long millisTaken,
      int idealNumFragments,
      int idealNumNodes,
      int numExecutors,
      String detailsText);

  /**
   * Number of output records
   *
   * @param endpoint Executor Node Endpoint
   * @param recordCount output records
   */
  void recordsOutput(CoordinationProtos.NodeEndpoint endpoint, long recordCount);

  /** Mark output limited */
  void outputLimited();

  /**
   * Time taken to generate fragments.
   *
   * @param millisTaken time in milliseconds
   */
  void planGenerationTime(long millisTaken);

  /**
   * Time taken to assign fragments to nodes.
   *
   * @param millisTaken time in milliseconds
   */
  void planAssignmentTime(long millisTaken);

  /**
   * Time taken for sending start fragment rpcs to all nodes.
   *
   * @param millisTaken
   */
  void fragmentsStarted(long millisTaken, FragmentRpcSizeStats stats);

  /**
   * Time taken for sending activate fragment rpcs to all nodes.
   *
   * @param millisTaken
   */
  void fragmentsActivated(long millisTaken);

  /**
   * Failed to activate fragment.
   *
   * @param ex
   */
  void activateFragmentFailed(Exception ex);

  /**
   * Number of joins in the user-provided query
   *
   * @param joins
   */
  void setNumJoinsInUserQuery(Integer joins);

  /**
   * Number of joins in the final Prel plan
   *
   * @param joins
   */
  void setNumJoinsInFinalPrel(Integer joins);

  /**
   * ResourceScheduling related information
   *
   * @param resourceSchedulingDecisionInfo
   */
  void resourcesScheduled(ResourceSchedulingDecisionInfo resourceSchedulingDecisionInfo);

  void updateReflectionsWithHints(
      ReflectionExplanationsAndQueryDistance reflectionExplanationsAndQueryDistance);

  void putProfileFailed();

  void putProfileUpdateComplete();

  static AttemptEvent toEvent(AttemptEvent.State state) {
    return toEvent(state, System.currentTimeMillis());
  }

  static AttemptEvent toEvent(AttemptEvent.State state, long startTime) {
    return AttemptEvent.newBuilder().setState(state).setStartTime(startTime).build();
  }

  static String toStringOrEmpty(final RelNode plan, boolean verbose, boolean ensureDump) {
    if (!verbose && !ensureDump) {
      return "";
    }

    if (plan == null) {
      return "";
    }

    try {
      return RelOptUtil.dumpPlan(
          "",
          plan,
          SqlExplainFormat.TEXT,
          verbose ? SqlExplainLevel.ALL_ATTRIBUTES : SqlExplainLevel.EXPPLAN_ATTRIBUTES);
    } catch (UserCancellationException userCancellationException) {
      return "";
    }
  }

  default boolean isVerbose() {
    return false;
  }
}
