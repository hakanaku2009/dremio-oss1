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
package com.dremio.dac.server.admin.profile;

import static com.dremio.dac.server.admin.profile.HostProcessingRateUtil.computeRecordProcRateAtPhaseHostLevel;
import static com.dremio.dac.server.admin.profile.HostProcessingRateUtil.computeRecordProcRateAtPhaseOperatorHostLevel;

import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.dac.explore.model.DatasetPath;
import com.dremio.dac.server.admin.profile.TableBuilder.Alignment;
import com.dremio.exec.planner.MatchCountListener;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.AttemptEvent;
import com.dremio.exec.proto.UserBitShared.AttemptEvent.State;
import com.dremio.exec.proto.UserBitShared.CoreOperatorType;
import com.dremio.exec.proto.UserBitShared.MajorFragmentProfile;
import com.dremio.exec.proto.UserBitShared.MinorFragmentProfile;
import com.dremio.exec.proto.UserBitShared.NodeQueryProfile;
import com.dremio.exec.proto.UserBitShared.OperatorProfile;
import com.dremio.exec.proto.UserBitShared.PlanPhaseProfile;
import com.dremio.exec.proto.UserBitShared.PlannerPhaseRulesStats;
import com.dremio.exec.proto.UserBitShared.QueryProfile;
import com.dremio.service.accelerator.AccelerationDetailsUtils;
import com.dremio.service.accelerator.proto.AccelerationDetails;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;

/** Wrapper class for a {@link #profile query profile}, so it to be presented through web UI. */
public class ProfileWrapper {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ProfileWrapper.class);

  private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance(Locale.US);

  private QueryProfile profile;
  private String id;
  private final List<FragmentWrapper> fragmentProfiles;
  private final List<NodeWrapper> nodeProfiles;
  private final List<OperatorWrapper> operatorProfiles;
  private final AccelerationWrapper accelerationDetails;
  private final Map<AttemptEvent.State, Long> stateDurations;
  private Long totalDuration; // null if the query hasn't terminated
  private final Table<Integer, Integer, String> majorMinorHostTable = HashBasedTable.create();
  private List<SourceVersionWrapper> sourceVersionWrapperList;

  public ProfileWrapper(final QueryProfile profile, boolean debug) {
    this.profile = profile;
    this.id = QueryIdHelper.getQueryId(profile.getId());

    final List<MajorFragmentProfile> majors = new ArrayList<>(profile.getFragmentProfileList());
    Collections.sort(majors, Comparators.majorId);

    final List<NodeWrapper> nodeProfiles = new ArrayList<>();

    final List<NodeQueryProfile> nodeQueryProfiles = new ArrayList<>(profile.getNodeProfileList());
    Collections.sort(nodeQueryProfiles, Comparators.endpoint);

    for (final NodeQueryProfile nodeQueryProfile : nodeQueryProfiles) {
      nodeProfiles.add(new NodeWrapper(nodeQueryProfile, debug));
    }
    this.nodeProfiles = nodeProfiles;

    sourceVersionWrapperList = new ArrayList<>();
    profile
        .getContextInfo()
        .getSourceVersionSettingList()
        .forEach(
            (sv) ->
                sourceVersionWrapperList.add(
                    new SourceVersionWrapper(
                        sv.getSource(), sv.getVersionContext(), sv.getUsage())));

    final List<OperatorWrapper> ows = new ArrayList<>();
    // temporary map to store (major_id, operator_id) -> [(op_profile, minor_id)]
    final Map<ImmutablePair<Integer, Integer>, List<ImmutablePair<OperatorProfile, Integer>>>
        opmap = new HashMap<>();

    Collections.sort(majors, Comparators.majorId);
    for (final MajorFragmentProfile major : majors) {

      final List<MinorFragmentProfile> minors =
          new ArrayList<>(major.getMinorFragmentProfileList());
      Collections.sort(minors, Comparators.minorId);
      for (final MinorFragmentProfile minor : minors) {
        majorMinorHostTable.put(
            major.getMajorFragmentId(),
            minor.getMinorFragmentId(),
            minor.getEndpoint().getAddress());

        final List<OperatorProfile> ops = new ArrayList<>(minor.getOperatorProfileList());
        Collections.sort(ops, Comparators.operatorId);
        for (final OperatorProfile op : ops) {

          final ImmutablePair<Integer, Integer> ip =
              new ImmutablePair<>(major.getMajorFragmentId(), op.getOperatorId());
          if (!opmap.containsKey(ip)) {
            final List<ImmutablePair<OperatorProfile, Integer>> l = new ArrayList<>();
            opmap.put(ip, l);
          }
          opmap.get(ip).add(new ImmutablePair<>(op, minor.getMinorFragmentId()));
        }
      }
    }

    final List<ImmutablePair<Integer, Integer>> keys = new ArrayList<>(opmap.keySet());
    Collections.sort(keys);

    Map<Integer, Set<HostProcessingRate>> majorHostProcRateSetMap = new HashMap<>();

    for (final ImmutablePair<Integer, Integer> ip : keys) {
      int majorId = ip.getLeft();
      Set<HostProcessingRate> hostProcessingRateSet =
          computeRecordProcRateAtPhaseOperatorHostLevel(
              majorId, opmap.get(ip), majorMinorHostTable);

      Set<HostProcessingRate> phaseLevelSet = new HashSet<>();
      if (majorHostProcRateSetMap.containsKey(majorId)) {
        phaseLevelSet = majorHostProcRateSetMap.get(majorId);
      }
      phaseLevelSet.addAll(hostProcessingRateSet);
      majorHostProcRateSetMap.put(majorId, phaseLevelSet);

      ows.add(
          new OperatorWrapper(
              majorId,
              opmap.get(ip),
              profile.hasOperatorTypeMetricsMap() ? profile.getOperatorTypeMetricsMap() : null,
              majorMinorHostTable,
              hostProcessingRateSet));
    }
    this.operatorProfiles = ows;

    final List<FragmentWrapper> fragmentProfiles = new ArrayList<>();
    for (final MajorFragmentProfile major : majors) {
      Set<HostProcessingRate> unAggregatedSetForMajor =
          majorHostProcRateSetMap.get(major.getMajorFragmentId());
      Set<HostProcessingRate> hostProcessingRateSet =
          computeRecordProcRateAtPhaseHostLevel(
              major.getMajorFragmentId(), unAggregatedSetForMajor);
      fragmentProfiles.add(
          new FragmentWrapper(major, profile.getStart(), debug, hostProcessingRateSet));
    }
    this.fragmentProfiles = fragmentProfiles;

    AccelerationWrapper wrapper = null;
    try {
      AccelerationDetails details =
          AccelerationDetailsUtils.deserialize(
              profile.getAccelerationProfile().getAccelerationDetails());
      if (details != null) {
        wrapper = new AccelerationWrapper(details);
      }
    } catch (Exception e) {
      // do not fail if we are unable to deserialize the acceleration details
      logger.warn("Failed to deserialize acceleration details", e);
    }
    accelerationDetails = wrapper;

    Map<AttemptEvent.State, Long> stateDurations = new HashMap<>();
    final List<AttemptEvent> events = new ArrayList<>(profile.getStateListList());
    Collections.sort(events, Comparators.stateStartTime);

    for (int i = 0; i < events.size(); i++) {
      if (isTerminal(events.get(i).getState())) {
        totalDuration = events.get(i).getStartTime() - events.get(0).getStartTime();
        break;
      }
      if (i + 1 < events.size()) {
        long timeSpent = events.get(i + 1).getStartTime() - events.get(i).getStartTime();
        stateDurations.compute(
            events.get(i).getState(), (k, v) -> (v == null) ? timeSpent : v + timeSpent);
      }
    }
    this.stateDurations = stateDurations;
  }

  private boolean isTerminal(AttemptEvent.State state) {
    return (state == State.COMPLETED || state == State.CANCELED || state == State.FAILED);
  }

  private String getDuration(AttemptEvent.State state) {
    if (state == profile.getStateList(profile.getStateListCount() - 1).getState()) {
      return "in progress";
    }
    if (!stateDurations.containsKey(state)) {
      return "-";
    }
    return NUMBER_FORMAT.format(stateDurations.get(state)) + "ms";
  }

  public String getPendingTime() {
    return hasStateDurations() ? getDuration(State.PENDING) : "-";
  }

  public String getMetadataRetrievalTime() {
    return hasStateDurations() ? getDuration(State.METADATA_RETRIEVAL) : "-";
  }

  public String getPlanningTime() {
    return hasStateDurations() ? getDuration(State.PLANNING) : getLegacyPlanningTime();
  }

  public String getQueuedTime() {
    return hasStateDurations() ? getDuration(State.QUEUED) : getLegacyQueueTime();
  }

  public String getEngineStartTime() {
    return hasStateDurations() ? getDuration(State.ENGINE_START) : "-";
  }

  public String getExecutionPlanningTime() {
    return hasStateDurations() ? getDuration(State.EXECUTION_PLANNING) : "-";
  }

  public String getStartingTime() {
    return hasStateDurations() ? getDuration(State.STARTING) : "-";
  }

  public String getRunningTime() {
    return hasStateDurations() ? getDuration(State.RUNNING) : "-";
  }

  public String getStartTimeInUTC() {
    return profile.hasStart() ? convertTimestampToString(profile.getStart()) : "-";
  }

  public String getEndTimeInUTC() {
    return profile.hasEnd() && profile.getEnd() > 0
        ? convertTimestampToString(profile.getEnd())
        : "-";
  }

  public String getCancelStartTimeInUTC() {
    return profile.hasCancelStartTime()
        ? convertTimestampToString(profile.getCancelStartTime())
        : "-";
  }

  private boolean hasQueryTerminated() {
    UserBitShared.QueryResult.QueryState queryState = profile.getState();
    return queryState == UserBitShared.QueryResult.QueryState.COMPLETED
        || queryState == UserBitShared.QueryResult.QueryState.CANCELED
        || queryState == UserBitShared.QueryResult.QueryState.FAILED;
  }

  public String getTotalTime() {
    if (!hasQueryTerminated()) {
      return "in progress";
    }

    if (totalDuration != null) {
      return NUMBER_FORMAT.format(totalDuration) + "ms";
    }

    // for legacy queries
    long startTime = profile.getStart();
    long endTime = profile.getEnd();
    if (endTime >= startTime) {
      return NUMBER_FORMAT.format(endTime - startTime) + "ms";
    } else {
      return "-";
    }
  }

  public static String convertTimestampToString(long epochTimestamp) {
    return Instant.ofEpochMilli(epochTimestamp).toString();
  }

  /**
   * @return command pool wait time or "None" if not available.
   */
  @SuppressWarnings("unused")
  public String getCommandPoolWaitMillis() {
    final QueryProfile profile = getProfile();
    if (!profile.hasCommandPoolWaitMillis()) {
      return "None";
    }
    return NUMBER_FORMAT.format(profile.getCommandPoolWaitMillis()) + "ms";
  }

  /**
   * @return Get query planning time. If the planning hasn't started, returns "Planning not
   *     started". If planning hasn't completed, returns "Still planning".
   */
  @SuppressWarnings("unused")
  public String getLegacyPlanningTime() {
    final QueryProfile profile = getProfile();
    if (!profile.hasPlanningStart() || profile.getPlanningStart() == 0) {
      return "Planning not started";
    }

    if (!profile.hasPlanningEnd() || profile.getPlanningEnd() == 0) {
      return "Still planning";
    }

    // Starting from 3.0, the planning time includes the resource queueing time. Thus, correcting
    // for it when resource scheduling time exists
    long planningPlusSchedulingTime = profile.getPlanningEnd() - profile.getPlanningStart();

    UserBitShared.ResourceSchedulingProfile r = profile.getResourceSchedulingProfile();
    if (r == null || r.getResourceSchedulingStart() == 0 || r.getResourceSchedulingEnd() == 0) {
      return NUMBER_FORMAT.format(planningPlusSchedulingTime) + "ms";
    }
    long schedulingTime = r.getResourceSchedulingEnd() - r.getResourceSchedulingStart();

    return NUMBER_FORMAT.format(planningPlusSchedulingTime - schedulingTime) + "ms";
  }

  @SuppressWarnings("unused")
  public String getLegacyQueueTime() {
    UserBitShared.ResourceSchedulingProfile r = profile.getResourceSchedulingProfile();
    if (r == null || r.getResourceSchedulingStart() == 0 || r.getResourceSchedulingEnd() == 0) {
      return "";
    }
    return NUMBER_FORMAT.format(r.getResourceSchedulingEnd() - r.getResourceSchedulingStart())
        + "ms";
  }

  @SuppressWarnings("unused")
  public String getResourceSchedulingOverview() {
    UserBitShared.ResourceSchedulingProfile r = profile.getResourceSchedulingProfile();
    if (r == null) {
      return "";
    }
    DescriptionListBuilder dlb = new DescriptionListBuilder();
    if (r.hasEngineName()) {
      dlb.addItem("Engine Name:", r.getEngineName());
    }
    if (r.hasQueueName()) {
      dlb.addItem("Queue Name:", r.getQueueName());
    }
    if (r.hasQueueId()) {
      dlb.addItem("Queue Id:", r.getQueueId());
    }
    if (r.hasRuleName()) {
      dlb.addItem("Rule Name:", r.getRuleName());
    }
    if (r.hasRuleId()) {
      dlb.addItem("Rule Id:", r.getRuleId());
    }
    if (r.hasRuleContent()) {
      dlb.addItem("Rule Content:", r.getRuleContent());
    }
    if (r.hasRuleAction()) {
      dlb.addItem("Rule Action:", r.getRuleAction());
    }
    if (r.hasSchedulingProperties()) {
      UserBitShared.ResourceSchedulingProperties rsp = r.getSchedulingProperties();
      if (rsp.hasQueryCost()) {
        dlb.addItem("Query Cost:", String.format("%.0f", rsp.getQueryCost()));
      }
      if (rsp.hasQueryType()) {
        dlb.addItem("Query Type:", rsp.getQueryType()); // this maps to WorkloadType internally
      }
    }
    if (profile.hasCancelReason()) {
      dlb.addItem("Cancellation Reason:", profile.getCancelReason());
    }
    return dlb.build();
  }

  @SuppressWarnings("unused")
  public boolean hasError() {
    return profile.hasError() && profile.getError() != null;
  }

  @SuppressWarnings("unused")
  public String getQuerySchema() {
    final String schema = profile.getFullSchema();
    if (Strings.isNullOrEmpty(schema)) {
      return null;
    }
    return schema;
  }

  @SuppressWarnings("unused")
  public String getNonDefaultOptions() {
    final String options = profile.getNonDefaultOptionsJSON();
    if (Strings.isNullOrEmpty(options)) {
      return null;
    }

    return options;
  }

  public QueryProfile getProfile() {
    return profile;
  }

  public boolean hasStateDurations() {
    return profile.getStateListCount() > 0;
  }

  public String getStateName() {
    return hasStateDurations()
        ? profile.getStateList(profile.getStateListCount() - 1).getState().name()
        : profile.getState().name();
  }

  public AccelerationWrapper getAccelerationDetails() {
    return accelerationDetails;
  }

  public String getQueryId() {
    return id;
  }

  public String getPlanText() {
    return StringEscapeUtils.escapeJson(profile.getPlan());
  }

  @SuppressWarnings("unused")
  public List<FragmentWrapper> getFragmentProfiles() {
    return fragmentProfiles;
  }

  public int getFragmentProfilesSize() {
    return fragmentProfiles.size();
  }

  @SuppressWarnings("unused")
  public String getFragmentsOverview() {
    TableBuilder tb = new TableBuilder(FragmentWrapper.FRAGMENT_OVERVIEW_COLUMNS);
    for (final FragmentWrapper fw : fragmentProfiles) {
      fw.addSummary(tb);
    }
    return tb.build();
  }

  @SuppressWarnings("unused")
  public String getNodesOverview() {
    TableBuilder tb = new TableBuilder(NodeWrapper.NODE_OVERVIEW_COLUMNS);
    for (final NodeWrapper fw : nodeProfiles) {
      fw.addSummary(tb);
    }
    return tb.build();
  }

  @SuppressWarnings("unused")
  public List<OperatorWrapper> getOperatorProfiles() {
    return operatorProfiles;
  }

  @SuppressWarnings("unused")
  public String getOperatorsOverview() {
    final TableBuilder tb = new TableBuilder(OperatorWrapper.OPERATORS_OVERVIEW_COLUMNS);
    for (final OperatorWrapper ow : operatorProfiles) {
      ow.addSummary(tb);
    }
    return tb.build();
  }

  @SuppressWarnings("unused")
  public String getOperatorsJSON() {
    final StringBuilder sb = new StringBuilder("{");
    String sep = "";
    for (final CoreOperatorType op : CoreOperatorType.values()) {
      sb.append(String.format("%s\"%d\" : \"%s\"", sep, op.getNumber(), op));
      sep = ", ";
    }
    return sb.append("}").toString();
  }

  public String getContext() {
    return profile.getContextInfo().getSchemaPathContext();
  }

  public String getSourceVersionMapping() {
    TableBuilder tb = new TableBuilder(SourceVersionWrapper.SOURCE_VERSION_COLUMNS);
    for (final SourceVersionWrapper svw : sourceVersionWrapperList) {
      if (svw.getUsage() == UserBitShared.SourceVersionSetting.Usage.USED_BY_QUERY) {
        svw.addSummary(tb);
      }
    }
    return tb.build();
  }

  public int getPlanCacheUsed() {
    return profile.getNumPlanCacheUsed();
  }

  public boolean getResultsCacheUsed() {
    return profile.hasResultsCacheProfile();
  }

  public Map<DatasetPath, List<UserBitShared.LayoutMaterializedViewProfile>>
      getDatasetGroupedLayoutList() {
    Map<DatasetPath, List<UserBitShared.LayoutMaterializedViewProfile>> map = Maps.newHashMap();

    UserBitShared.AccelerationProfile accelerationProfile = profile.getAccelerationProfile();
    List<UserBitShared.LayoutMaterializedViewProfile> layoutProfilesList =
        accelerationProfile.getLayoutProfilesList();

    for (UserBitShared.LayoutMaterializedViewProfile viewProfile : layoutProfilesList) {
      String reflectionDatasetPath =
          accelerationDetails.getReflectionDatasetPath(viewProfile.getLayoutId());

      DatasetPath path;

      if ("".equals(reflectionDatasetPath)) {
        path = new DatasetPath(Arrays.asList("unknown", "missing dataset"));
      } else {
        String datasetVersion =
            accelerationDetails.getReflectionDatasetVersion(viewProfile.getLayoutId());
        path = new DatasetPath(reflectionDatasetPath, datasetVersion);
      }

      if (!map.containsKey(path)) {
        map.put(path, new ArrayList<UserBitShared.LayoutMaterializedViewProfile>());
      }
      map.get(path).add(viewProfile);
    }

    return map;
  }

  @SuppressWarnings("unused")
  public String getFragmentsJSON() throws IOException {
    final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    final JsonGenerator jsonGenerator = new JsonFactory().createGenerator(outputStream);

    jsonGenerator.writeStartObject();

    for (FragmentWrapper fragmentWrapper : getFragmentProfiles()) {
      fragmentWrapper.addFragment(jsonGenerator);
    }

    jsonGenerator.writeEndObject();

    jsonGenerator.flush();
    return outputStream.toString();
  }

  @SuppressWarnings("unused")
  public String getOperatorProfilesJSON() throws IOException {
    final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    final JsonGenerator jsonGenerator = new JsonFactory().createGenerator(outputStream);

    jsonGenerator.writeStartObject();

    for (OperatorWrapper operatorWrapper : getOperatorProfiles()) {
      operatorWrapper.addOperator(jsonGenerator);
    }

    jsonGenerator.writeEndObject();

    jsonGenerator.flush();
    return outputStream.toString();
  }

  public int getConsideredReflectionsCount() {
    return profile.hasAccelerationProfile()
        ? profile.getAccelerationProfile().getLayoutProfilesCount()
        : 0;
  }

  public int getMatchedReflectionsCount() {
    int ret = 0;
    if (profile.hasAccelerationProfile()
        && profile.getAccelerationProfile().getLayoutProfilesCount() > 0) {
      UserBitShared.AccelerationProfile accelerationProfile = profile.getAccelerationProfile();
      for (UserBitShared.LayoutMaterializedViewProfile profile :
          accelerationProfile.getLayoutProfilesList()) {
        if (profile.hasNumSubstitutions() && profile.getNumSubstitutions() > 0) {
          ret++;
        }
      }
    }
    return ret;
  }

  public int getChosenReflectionsCount() {
    int ret = 0;
    if (profile.hasAccelerationProfile()
        && profile.getAccelerationProfile().getLayoutProfilesCount() > 0) {
      UserBitShared.AccelerationProfile accelerationProfile = profile.getAccelerationProfile();
      for (UserBitShared.LayoutMaterializedViewProfile profile :
          accelerationProfile.getLayoutProfilesList()) {
        if (profile.hasNumUsed() && profile.getNumUsed() > 0) {
          ret++;
        }
      }
    }
    return ret;
  }

  /** Aggregates across used reflections for a job to find the timestamp of the oldest data */
  public Long getEarliestReflectionRefresh() {
    long ret = Long.MAX_VALUE;
    if (profile.hasAccelerationProfile()
        && profile.getAccelerationProfile().getLayoutProfilesCount() > 0
        && accelerationDetails != null) {
      UserBitShared.AccelerationProfile accelerationProfile = profile.getAccelerationProfile();
      for (UserBitShared.LayoutMaterializedViewProfile profile :
          accelerationProfile.getLayoutProfilesList()) {
        Long refreshChainStartTime =
            accelerationDetails.getRefreshChainStartTime(profile.getLayoutId());
        if (profile.hasLayoutId()
            && profile.hasNumUsed()
            && profile.getNumUsed() > 0
            && refreshChainStartTime != null) {
          ret = Math.min(ret, refreshChainStartTime);
        }
      }
    }
    return ret == Long.MAX_VALUE ? null : ret;
  }

  public String getRulesBreakdownTable() throws IOException {
    @SuppressWarnings("deprecation") // checking backward compatibility
    boolean oldStyle =
        profile.getPlanPhasesList().stream()
            .anyMatch(phaseProfile -> !phaseProfile.getTimeBreakdownPerRuleMap().isEmpty());

    if (oldStyle) {
      // Old way of phase-wise breakdown, kept for backward compatibility
      TableBuilder tb = new TableBuilder(new String[0], Alignment.LEFT);
      for (PlanPhaseProfile phaseProfile : profile.getPlanPhasesList()) {
        @SuppressWarnings("deprecation") // checking backward compatibility
        Map<String, Long> ruleMap = phaseProfile.getTimeBreakdownPerRuleMap();
        if (!ruleMap.isEmpty()) {
          tb.appendCell(phaseProfile.getPhaseName());
          for (String key : ruleMap.keySet()) {
            tb.appendCell(key + ", " + ruleMap.get(key) + " ms");
          }
        }
      }
      return tb.build();
    }

    TableBuilder tb = new TableBuilder(MatchCountListener.RULES_BREAKDOWN_COLUMNS, Alignment.LEFT);
    for (PlanPhaseProfile phaseProfile : profile.getPlanPhasesList()) {
      if (phaseProfile != null) {
        for (PlannerPhaseRulesStats stats : phaseProfile.getRulesBreakdownStatsList()) {
          tb.appendCell(phaseProfile.getPhaseName())
              .appendCell(stats.getRule())
              .appendCell(String.valueOf(stats.getTotalTimeMs()))
              .appendCell(String.valueOf(stats.getMatchedCount()))
              .appendCell(String.valueOf(stats.getTransformedCount()))
              .appendCell(String.valueOf(stats.getRelnodesCount()));
        }
      }
    }
    return tb.build();
  }
}
