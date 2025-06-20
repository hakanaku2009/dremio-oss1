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

import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.SupportsFsMutablePlugin;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.TableFormatWriterOptions;
import com.dremio.exec.physical.config.WriterCommitterPOP;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.store.RecordWriter;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.LongValidator;
import com.dremio.options.TypeValidators.PositiveLongValidator;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;

@Options
public class WriterCommitterPrel extends SingleRel implements Prel {

  public static final LongValidator RESERVE =
      new PositiveLongValidator(
          "planner.op.writercommiter.reserve_bytes", Long.MAX_VALUE, DEFAULT_RESERVE);
  public static final LongValidator LIMIT =
      new PositiveLongValidator(
          "planner.op.writercommiter.limit_bytes", Long.MAX_VALUE, DEFAULT_LIMIT);

  private final String tempLocation;
  private final String finalLocation;
  private final SupportsFsMutablePlugin plugin;
  private final String userName;
  private final CreateTableEntry createTableEntry;
  private final Optional<DatasetConfig> datasetConfig;
  private final boolean isPartialRefresh;
  private final boolean readSignatureEnabled;
  private final StoragePluginId sourceTablePluginId;

  public WriterCommitterPrel(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode child,
      SupportsFsMutablePlugin plugin,
      String tempLocation,
      String finalLocation,
      String userName,
      CreateTableEntry createTableEntry,
      Optional<DatasetConfig> datasetConfig,
      boolean partialRefresh,
      boolean readSignatureEnabled,
      StoragePluginId sourceTablePluginId) {
    super(cluster, traits, child);
    this.tempLocation = tempLocation;
    this.finalLocation = finalLocation;
    this.plugin = plugin;
    this.userName = userName;
    this.createTableEntry = createTableEntry;
    this.datasetConfig = datasetConfig;
    this.isPartialRefresh = partialRefresh;
    this.readSignatureEnabled = readSignatureEnabled;
    this.sourceTablePluginId = sourceTablePluginId;
  }

  @Override
  public WriterCommitterPrel copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new WriterCommitterPrel(
        getCluster(),
        traitSet,
        sole(inputs),
        plugin,
        tempLocation,
        finalLocation,
        userName,
        createTableEntry,
        datasetConfig,
        isPartialRefresh,
        readSignatureEnabled,
        sourceTablePluginId);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    TableFormatWriterOptions tableFormatOptions =
        createTableEntry.getOptions().getTableFormatOptions();
    return super.explainTerms(pw)
        .itemIf("temp", tempLocation, tempLocation != null)
        .item("final", finalLocation)
        .itemIf(
            "iceberg_operation",
            tableFormatOptions.getOperation().name(),
            tableFormatOptions != null
                && !tableFormatOptions
                    .getOperation()
                    .equals(TableFormatWriterOptions.TableFormatOperation.NONE))
        .itemIf(
            "min_input_files",
            tableFormatOptions.getMinInputFilesBeforeOptimize(),
            tableFormatOptions != null
                && tableFormatOptions.getMinInputFilesBeforeOptimize() != null);
  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    return new WriterCommitterPOP(
        creator.props(this, userName, RecordWriter.SCHEMA, RESERVE, LIMIT),
        tempLocation,
        finalLocation,
        createTableEntry.getIcebergTableProps(),
        createTableEntry.getDatasetPath(),
        datasetConfig,
        getChildPhysicalOperator(creator),
        plugin,
        null,
        isPartialRefresh,
        readSignatureEnabled,
        createTableEntry.getOptions().getTableFormatOptions(),
        sourceTablePluginId,
        createTableEntry.getUserId());
  }

  @Override
  public Iterator<Prel> iterator() {
    return PrelUtil.iter(getInput());
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitWriterCommitter(this, value);
  }

  @Override
  public SelectionVectorMode[] getSupportedEncodings() {
    return SelectionVectorMode.DEFAULT;
  }

  @Override
  public SelectionVectorMode getEncoding() {
    return SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return true;
  }

  public String getUserName() {
    return userName;
  }

  public boolean isPartialRefresh() {
    return isPartialRefresh;
  }

  public boolean isReadSignatureEnabled() {
    return readSignatureEnabled;
  }

  protected String getTempLocation() {
    return tempLocation;
  }

  protected String getFinalLocation() {
    return finalLocation;
  }

  protected CreateTableEntry getCreateTableEntry() {
    return createTableEntry;
  }

  protected Optional<DatasetConfig> getDatasetConfig() {
    return datasetConfig;
  }

  protected StoragePluginId getSourceTablePluginId() {
    return sourceTablePluginId;
  }

  protected SupportsFsMutablePlugin getPlugin() {
    return plugin;
  }

  protected PhysicalOperator getChildPhysicalOperator(PhysicalPlanCreator creator)
      throws IOException {
    Prel child = (Prel) this.getInput();
    return child.getPhysicalOperator(creator);
  }
}
