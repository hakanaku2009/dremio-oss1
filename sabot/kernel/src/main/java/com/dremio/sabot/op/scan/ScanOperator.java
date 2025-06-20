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
package com.dremio.sabot.op.scan;

import static com.google.common.base.Preconditions.checkNotNull;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ErrorHelper;
import com.dremio.common.exceptions.InvalidMetadataErrorContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.serde.ProtobufByteStringSerDe;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.exception.SchemaChangeException;
import com.dremio.exec.exception.SchemaChangeExceptionContext;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.SubScan;
import com.dremio.exec.physical.config.BoostPOP;
import com.dremio.exec.physical.config.Screen;
import com.dremio.exec.planner.fragment.PlanFragmentFull;
import com.dremio.exec.proto.CoordExecRPC;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.ExecProtos;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.MetricDef.AggregationType;
import com.dremio.exec.proto.UserBitShared.MetricDef.DisplayType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.RuntimeFilter;
import com.dremio.exec.store.parquet.ParquetSubScan;
import com.dremio.exec.store.parquet.RecordReaderIterator;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.util.VectorUtil;
import com.dremio.exec.work.foreman.ForemanSetupException;
import com.dremio.sabot.exec.context.MetricDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.exec.fragment.OutOfBandMessage;
import com.dremio.sabot.op.join.vhash.spill.slicer.CombinedSizer;
import com.dremio.sabot.op.join.vhash.spill.slicer.Sizer;
import com.dremio.sabot.op.spi.ProducerOperator;
import com.dremio.sabot.op.values.EmptyValuesCreator.EmptyRecordReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.AllocationHelper;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.CallBack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Record batch used for a particular scan. Operators against one or more */
public class ScanOperator implements ProducerOperator {
  private static final Logger logger = LoggerFactory.getLogger(ScanOperator.class);
  protected static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(ScanOperator.class);
  public long warnThreshold;
  private long maxAvgRowSize;

  /** Main collection of fields' value vectors. */
  protected final VectorContainer outgoing;

  private CombinedSizer sizer;
  private int maxRowSize;

  public enum Metric implements MetricDef {
    @Deprecated
    SETUP_NS, // @deprecated use stats time in OperatorStats instead.
    NUM_READERS(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Number of readers"), // tracks how many readers were opened so far
    NUM_REMOTE_READERS, // tracks how many readers are non-local
    NUM_ROW_GROUPS, // number of rowGroups for the FileSplitParquetRecordReader
    NUM_VECTORIZED_COLUMNS,
    NUM_NON_VECTORIZED_COLUMNS,
    COPY_NS,
    FILTER_NS,
    PARQUET_EXEC_PATH, // type of readers (vectorized, non-vectorized or combination used) in
    // parquet
    FILTER_EXISTS, // Is there a filter pushed into scan?
    PARQUET_BYTES_READ, // Represents total number of actual bytes (uncompressed) read while parquet
    // scan.
    NUM_ASYNC_STREAMS, // Represents number of async streams created
    NUM_ASYNC_READS, // Total number of async read requests made
    NUM_ASYNC_BYTES_READ, // Total number of bytes read
    NUM_EXTENDING_READS, // Total number of extending chunks
    EXTENDING_READ_BYTES, // Size of the extending chunks.
    TOTAL_BYTES_READ, // Total bytes read
    LOCAL_BYTES_READ, // Total number of local bytes read using local I/O
    SHORT_CIRCUIT_BYTES_READ, // Total number of bytes read using short circuit reads
    PRELOADED_BYTES, // Number of bytes pre-loaded
    NUM_CACHE_HITS(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Number of cache hits"), // Number of C3 hits
    NUM_CACHE_MISSES(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Number of cache misses"), // Number of C3 misses
    BLOCK_AFFINITY_CACHE_HITS, // Number of block affinity cache hits
    BLOCK_AFFINITY_CACHE_MISSES, // Number of block affinity cache misses
    AVG_PROCESSING_TIME_NS, // Average processing time of request by C3
    JAVA_BUILD_TIME_NS, // time taken by Java (setup+evaluation) for type conversions in
    // CoercionReader
    JAVA_EXECUTE_TIME_NS,
    GANDIVA_BUILD_TIME_NS, // time taken by Gandiva (setup+evaluation) for type conversions in
    // CoercionReader
    GANDIVA_EXECUTE_TIME_NS,
    NUM_FILTERS_MODIFIED, // Number of parquet filters modified
    NUM_HIVE_PARQUET_TRUNCATE_VARCHAR, // Number of fixed-len varchar fields in hive_parquet
    TOTAL_HIVE_PARQUET_TRUNCATE_VARCHAR, // Total number of fixed-len varchar truncation in
    // haveParquetCoercion
    TOTAL_HIVE_PARQUET_TRANSFER_VARCHAR, //  Total number of fixed-len varchar transfers in
    // haveParquetCoercion
    HIVE_PARQUET_CHECK_VARCHAR_CAST_TIME_NS, // Time spent checking if truncation is required for a
    // varchar field
    NUM_ROW_GROUPS_PRUNED(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Number of row groups pruned"), // number of rowGroups pruned in ParquetVectorizedReader
    MAX_ROW_GROUPS_IN_HIVE_FILE_SPLITS, // max number of row groups across hive file splits
    NUM_HIVE_FILE_SPLITS_WITH_NO_ROWGROUPS, // Number of hive file splits with no rowgroups
    MIN_IO_READ_TIME_NS, // Minimum IO read time
    MAX_IO_READ_TIME_NS, // Maximum IO read time
    AVG_IO_READ_TIME_NS, // Average IO read time
    NUM_IO_READ, // Total Number of IO reads
    NUM_HIVE_PARQUET_DECIMAL_COERCIONS, // Number of decimal coercions in hive parquet
    NUM_ROW_GROUPS_TRIMMED, // Number of row groups trimmed from footer in memory
    NUM_COLUMNS_TRIMMED, // Number of columns trimmed from footer in memory
    NUM_PARTITIONS_PRUNED(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Number of partitions pruned using runtime filter"), // Number of partitions pruned from
    // runtime filter
    NUM_BOOSTED_FILE_READS, // Number of Boosted File Reads.
    MAX_BOOSTED_FILE_READ_TIME_NS, // Max Boosted IO read Time.
    AVG_BOOSTED_FILE_READ_TIME_NS, // Average Boosted IO time.
    TOTAL_BOOSTED_BYTES_READ, // Total Boosted Bytes Read.
    NUM_COLUMNS_BOOSTED, // Number of Boosted Files Read.
    OFFSET_INDEX_READ, // Offset Index Read,
    COLUMN_INDEX_READ, // Column Index Read.
    NUM_BOOSTED_RECORD_BATCHES_PRUNED, // Due to Filter how many record batches have been skipped
    NUM_PAGES_PRUNED(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Number of pages pruned"), // Number of pages skipped based on stats
    NUM_PAGES_READ, // Number of pages checked upon
    PAGE_DECOMPRESSION_TIME_NS, // Total time taken for page decompression in nanos
    NUM_RUNTIME_FILTERS(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.MAX,
        "Number of runtime filters received"), // Number of runtime filter received at scan
    RUNTIME_COL_FILTER_DROP_COUNT(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.MAX,
        "Number of runtime filters dropped"), // Number of non partition column filters dropped due
    // to schema incompatibility
    ROW_GROUPS_SCANNED_WITH_RUNTIME_FILTER, // Number of rowgroups scanned with runtime filter
    RLE_PAGE_DECODING_READ_TIME_NS, // Total time taken for reading during page decoding for RLE
    // encoding
    RLE_PAGE_DECODING_WRITE_TIME_NS, // Total time taken for writing arrow vectors during page
    // decoding for RLE encoding
    PACKED_PAGE_DECODING_READ_TIME_NS, // Total time taken for reading during page decoding for
    // PACKED encoding
    PACKED_PAGE_DECODING_WRITE_TIME_NS, // Total time taken for writing arrow vectors during page
    // decoding for PACKED encoding
    MIN_METADATA_IO_READ_TIME_NS, // Minimum IO read time for metadata operations
    MAX_METADATA_IO_READ_TIME_NS, // Maximum IO read time for metadata operations
    AVG_METADATA_IO_READ_TIME_NS, // Average IO read time for metadata operations
    NUM_METADATA_IO_READ,
    HIVE_FILE_FORMATS, // File Formats in hive sub scan represented as bitmap. Indices correspond to
    // HiveFilFormat enum
    NUM_ZERO_SIZED_COLUMN, // Number of zero sized column
    NUM_EXTRA_FOOTER_READS, // Number of times footer is read for a split.
    MAX_RECORD_READ_PER_READER, // Maximum number of record read from Reader
    ICEBERG_COMMIT_TIME, // Time to commit an operation to Iceberg table
    ORPHAN_FILE_DISCOVERY_TIME, // Time to discover the orphan files
    NUM_ORPHAN_FILES, // Number of orphan files
    NUM_READERS_SKIPPED, // The number of readers skipped due to errors (COPY INTO 'skip_file')
    DRY_RUN_READ_TIME_NS, // The read time of the dry-run phase (COPY INTO 'skip_file')
    ;

    private final DisplayType displayType;
    private final AggregationType aggregationType;
    private final String displayCode;

    Metric() {
      this(DisplayType.DISPLAY_NEVER, AggregationType.SUM, "");
    }

    Metric(DisplayType displayType, AggregationType aggregationType, String displayCode) {
      this.displayType = displayType;
      this.aggregationType = aggregationType;
      this.displayCode = displayCode;
    }

    @Override
    public int metricId() {
      return ordinal();
    }

    @Override
    public DisplayType getDisplayType() {
      return this.displayType;
    }

    @Override
    public AggregationType getAggregationType() {
      return this.aggregationType;
    }

    @Override
    public String getDisplayCode() {
      return this.displayCode;
    }
  }

  public enum HiveFileFormat {
    PARQUET,
    ORC,
    AVRO,
    TEXT,
    RCFILE,
    OTHER
  }

  /** Fields' value vectors indexed by fields' keys. */
  protected final Map<String, ValueVector> fieldVectorMap = Maps.newHashMap();

  protected State state = State.NEEDS_SETUP;
  protected final OperatorContext context;
  protected RecordReaderIterator readers;
  protected RecordReader currentReader;
  private final ScanMutator mutator;
  private MutatorSchemaChangeCallBack callBack = new MutatorSchemaChangeCallBack();
  private final BatchSchema schema;
  private final ImmutableList<SchemaPath> selectedColumns;
  private final List<String> tableSchemaPath;
  protected final SubScan config;
  // maps peerJoin -> number of fragments that will send a runtime filter. This is initialized as
  // part of creating the ScanOperator
  private final Map<Long, Integer> peerJoinFragmentMap = new HashMap<>();
  // maps peerJoin -> the filter to apply
  private final Map<Long, Object> peerJoinFilterMap = new HashMap<>();
  private final Stopwatch readTime = Stopwatch.createUnstarted();

  private final Set<SchemaPath> columnsToBoost;

  private final CoordinationProtos.NodeEndpoint foremanEndpoint;
  private final CoordExecRPC.QueryContextInformation queryContextInfo;

  private List<RuntimeFilter> runtimeFilters = new ArrayList<>();

  private final FragmentExecutionContext fec;
  private final boolean rowLimitEnabled;
  private final long rowSizeLimit;

  public ScanOperator(
      FragmentExecutionContext fec,
      SubScan config,
      OperatorContext context,
      RecordReaderIterator readers) {
    this(fec, config, context, readers, null, null);
  }

  public ScanOperator(
      FragmentExecutionContext fec,
      SubScan config,
      OperatorContext context,
      RecordReaderIterator readers,
      CoordinationProtos.NodeEndpoint foremanEndpoint,
      CoordExecRPC.QueryContextInformation queryContextInformation) {
    if (!readers.hasNext()) {
      this.readers = RecordReaderIterator.from(new EmptyRecordReader(context));
    } else {
      this.readers = readers;
    }
    this.fec = fec;
    this.context = context;
    this.config = config;
    this.schema = config.getFullSchema();
    // Arbitrarily take the first element of the referenced tables list.
    // Currently, the only way to have multiple entries in the referenced table list is to write a
    // Join across multiple JDBC tables. This scenario doesn't use checkAndLearnSchema(). If a
    // schema
    // change happens there, it gets corrected in the Foreman (when an InvalidMetadataError is
    // thrown).
    this.tableSchemaPath = Iterables.getFirst(config.getReferencedTables(), null);
    this.selectedColumns =
        config.getColumns() == null ? null : ImmutableList.copyOf(config.getColumns());
    this.columnsToBoost = new HashSet<>();

    final OperatorStats stats = context.getStats();
    try {
      // be in the processing state as the fetching the next reader could trigger wait which expects
      // the current state
      // to be processing.
      stats.startProcessing();
      this.currentReader = this.readers.next();
    } finally {
      stats.stopProcessing();
    }

    this.outgoing = context.createOutputVectorContainer();

    this.mutator = new ScanMutator(outgoing, fieldVectorMap, context, callBack);

    context.getStats().addLongStat(Metric.NUM_READERS, 1);

    this.foremanEndpoint = foremanEndpoint;
    this.queryContextInfo = queryContextInformation;
    this.warnThreshold = context.getOptions().getOption(ExecConstants.LIMIT_BATCH_ROW_SIZE_BYTES);
    this.maxAvgRowSize = warnThreshold;
    this.rowLimitEnabled =
        context.getOptions().getOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT);
    this.rowSizeLimit = context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES);
  }

  @Override
  public VectorAccessible setup() throws Exception {
    schema.maskAndReorder(config.getColumns()).materializeVectors(selectedColumns, mutator);
    outgoing.buildSchema(SelectionVectorMode.NONE);
    callBack.getSchemaChangedAndReset();
    setupReader(currentReader);

    state = State.CAN_PRODUCE;
    createSizer();
    return outgoing;
  }

  @Override
  public State getState() {
    return state;
  }

  public static void handleExceptionDuringScan(
      Exception e, Collection<List<String>> referencedTables, Logger logger) throws Exception {
    if (ErrorHelper.findWrappedCause(e, FileNotFoundException.class) != null) {
      if (e instanceof UserException) {
        throw UserException.invalidMetadataError(e.getCause())
            .addContext(((UserException) e).getOriginalMessage())
            .setAdditionalExceptionContext(
                new InvalidMetadataErrorContext(ImmutableList.copyOf(referencedTables)))
            .build(logger);
      } else {
        throw UserException.invalidMetadataError(e)
            .setAdditionalExceptionContext(
                new InvalidMetadataErrorContext(ImmutableList.copyOf(referencedTables)))
            .build(logger);
      }
    } else {
      throw e;
    }
  }

  protected void setupReader(RecordReader reader) throws Exception {
    try {
      BatchSchema initialSchema = outgoing.getSchema();
      runtimeFilters.stream().forEach(reader::addRuntimeFilter);
      setupReaderAsCorrectUser(reader);
      checkAndLearnSchema();
      Preconditions.checkArgument(
          initialSchema.equals(outgoing.getSchema()), "Schema changed but not detected.");
    } catch (Exception e) {
      ScanOperator.handleExceptionDuringScan(e, config.getReferencedTables(), logger);
    }
  }

  private void setupReaderAsCorrectUser(final RecordReader reader) throws Exception {
    checkNotNull(reader).setup(mutator);
  }

  @Override
  public int outputData() throws Exception {
    state.is(State.CAN_PRODUCE);

    injector.injectChecked(
        context.getExecutionControls(), "next-allocate", OutOfMemoryException.class);

    currentReader.allocate(fieldVectorMap);

    int recordCount;

    // get the next reader.
    readTime.start();

    final OperatorStats stats = context.getStats();

    while ((recordCount = currentReader.next()) == 0) {

      readTime.stop();

      // currentReader is done; get columnsToBoost
      if (currentReader.getColumnsToBoost() != null) {
        columnsToBoost.addAll(currentReader.getColumnsToBoost());
      }

      readTime.reset();
      readTime.start();
      if (!readers.hasNext()) {
        // We're on the last reader, and it has no (more) rows.
        // no need to close the reader (will be done when closing the operator)
        // but we might as well release any memory that we're holding.
        outgoing.zeroVectors();
        state = State.DONE;
        outgoing.setRecordCount(0);
        stats.batchReceived(0, 0, 0);
        stats.setReadIOStats();
        return 0;
      }

      // There are more readers, let's close the previous one and get the next one.
      currentReader.close();
      currentReader = readers.next();
      try {
        stats.startSetup();
        setupReader(currentReader);
      } finally {
        stats.stopSetup();
      }

      currentReader.allocate(fieldVectorMap);
      stats.addLongStat(Metric.NUM_READERS, 1);
    }

    readTime.stop();
    // Don't pass the batch of rows if the cancel is already initiated
    if (fec.isCancelled()) {
      outgoing.zeroVectors();
      outgoing.setRecordCount(0);
      stats.batchReceived(0, 0, 0);
      stats.setReadIOStats();
      logger.info("Query fragment is cancelled");
      return 0;
    }
    stats.batchReceived(0, recordCount, VectorUtil.getSize(outgoing));
    stats.setReadIOStats();
    checkAndLearnSchema();
    outgoing.setAllCount(recordCount);
    int incomingBatchAvgRowSize = Sizer.getAverageRowSize(outgoing, recordCount);
    if (!rowLimitEnabled && recordCount > 0 && incomingBatchAvgRowSize > maxAvgRowSize) {
      maxRowSize = Math.max(maxRowSize, sizer.getMaxRowLengthInBatch(recordCount));
      maxAvgRowSize = incomingBatchAvgRowSize;
    }
    return recordCount;
  }

  private void createSizer() {
    List<Sizer> sizerList = new ArrayList<>();

    for (VectorWrapper<?> vectorWrapper : outgoing) {
      sizerList.add(Sizer.get(vectorWrapper.getValueVector()));
    }
    this.sizer = new CombinedSizer(sizerList);
  }

  @Override
  public void workOnOOB(OutOfBandMessage message) {
    final String senderInfo =
        String.format(
            "Frag %d, OpId %d",
            message.getSendingMajorFragmentId(), message.getSendingOperatorId());
    if (message.getBuffers() == null || message.getBuffers().length == 0) {
      logger.warn("Empty runtime filter received from {}", senderInfo);
      return;
    }

    try (AutoCloseables.RollbackCloseable rollbackCloseable =
        new AutoCloseables.RollbackCloseable()) {
      // Operator ID int is transformed as follows - (fragmentId << 16) + opId;
      logger.info(
          "Filter received from {} minor fragment {} into op {}",
          senderInfo,
          message.getSendingMinorFragmentId(),
          config.getProps().getOperatorId());
      // scan operator handles the OOB message that it gets from the join operator
      final ExecProtos.RuntimeFilter protoFilter =
          message.getPayload(ExecProtos.RuntimeFilter.parser());
      String sourceJoinId =
          String.format(
              "%02d-%02d",
              message.getSendingMajorFragmentId(), message.getSendingOperatorId() & 0xFF);

      final RuntimeFilter filter =
          RuntimeFilter.getInstance(
              protoFilter,
              message.getOriginalBuffers(),
              senderInfo,
              sourceJoinId,
              context.getFragmentHandle(),
              context.getStats(),
              context.getBufferManager(),
              context.getOptions());
      rollbackCloseable.add(filter);

      boolean isAlreadyPresent =
          this.runtimeFilters.stream()
              .anyMatch(
                  r ->
                      r.getSenderInfo().equals(filter.getSenderInfo())
                          && r.isOnSameColumns(filter));
      if (isAlreadyPresent) {
        logger.debug("Skipping enforcement because filter is already present {}", filter);
      } else {
        logger.debug(
            "Adding filter to the record readers {}, current reader {}.",
            filter,
            this.currentReader.getClass().getName());
        this.runtimeFilters.add(filter);
        this.currentReader.addRuntimeFilter(filter);
        this.readers.addRuntimeFilter(filter);
        context.getStats().addLongStat(Metric.NUM_RUNTIME_FILTERS, 1);
        context.getStats().addRuntimeFilterDetailsInScan(filter.getFilterDetails());
        rollbackCloseable.commit();
      }
    } catch (Exception e) {
      logger.warn(
          "Error while merging runtime filter piece from "
              + message.getSendingMajorFragmentId()
              + ":"
              + message.getSendingMinorFragmentId(),
          e);
    }
  }

  @VisibleForTesting
  List<RuntimeFilter> getRuntimeFilters() {
    return runtimeFilters;
  }

  protected void checkAndLearnSchema() {
    if (mutator.getSchemaChanged()) {
      outgoing.buildSchema(SelectionVectorMode.NONE);
      final BatchSchema newSchema = mutator.transformFunction.apply(outgoing.getSchema());
      if (config.mayLearnSchema() && tableSchemaPath != null) {
        throw UserException.schemaChangeError()
            .addContext("Original Schema", config.getFullSchema().toString())
            .addContext("New Schema", newSchema.toString())
            .message(
                "New schema found. Please reattempt the query. Multiple attempts may be necessary to fully learn the schema.")
            .setAdditionalExceptionContext(
                new SchemaChangeExceptionContext(tableSchemaPath, newSchema))
            .build(logger);
      } else {
        // TODO: change error if we can't update. No reason to re-run if we didn't update.
        throw UserException.schemaChangeError()
            .message(
                "Schema change detected but unable to learn schema, query failed. Original: %s, New: %s.",
                config.getFullSchema(), newSchema)
            .build(logger);
      }
    }
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitProducer(this, value);
  }

  public static class ScanMutator implements OutputMutator {
    private final VectorContainer outgoing;
    private final Map<String, ValueVector> fieldVectorMap;
    private final OperatorContext context;
    private final MutatorSchemaChangeCallBack callBack;
    private Function<BatchSchema, BatchSchema> transformFunction = Functions.identity();

    public ScanMutator(
        VectorContainer outgoing,
        Map<String, ValueVector> fieldVectorMap,
        OperatorContext context,
        MutatorSchemaChangeCallBack callBack) {
      this.outgoing = outgoing;
      this.fieldVectorMap = fieldVectorMap;
      this.context = context;
      this.callBack = callBack;
    }

    public ScanMutator(
        VectorContainer outgoing, OperatorContext context, MutatorSchemaChangeCallBack callBack) {
      this.outgoing = outgoing;
      this.fieldVectorMap = new HashMap<>();
      this.context = context;
      this.callBack = callBack;

      outgoing
          .getSchema()
          .getFields()
          .forEach(
              field ->
                  fieldVectorMap.put(
                      field.getName().toLowerCase(),
                      outgoing
                          .getValueAccessorById(
                              FieldVector.class,
                              outgoing
                                  .getValueVectorId(SchemaPath.getSimplePath(field.getName()))
                                  .getFieldIds())
                          .getValueVector()));
    }

    @Override
    public void removeField(Field field) throws SchemaChangeException {
      ValueVector vector = fieldVectorMap.remove(field.getName().toLowerCase());
      if (vector == null) {
        throw new SchemaChangeException("Failure attempting to remove an unknown field.");
      }
      try (ValueVector v = vector) {
        outgoing.remove(vector);
      }
    }

    @Override
    public <T extends ValueVector> T addField(Field field, Class<T> clazz)
        throws SchemaChangeException {
      // Check if the field exists.
      final ValueVector v = fieldVectorMap.get(field.getName().toLowerCase());
      if (v == null
          || !clazz.isAssignableFrom(v.getClass())
          || checkIfDecimalsTypesAreDifferent(v, field)) {
        // Field does not exist--add it to the map and the output container.
        ValueVector newVector = TypeHelper.getNewVector(field, context.getAllocator(), callBack);
        if (!clazz.isAssignableFrom(newVector.getClass())) {
          throw new SchemaChangeException(
              String.format(
                  "The class that was provided, %s, does not correspond to the "
                      + "expected vector type of %s.",
                  clazz.getSimpleName(), newVector.getClass().getSimpleName()));
        }

        final ValueVector old = fieldVectorMap.put(field.getName().toLowerCase(), newVector);
        if (old != null) {
          old.clear();
          outgoing.remove(old);
        }

        outgoing.add(newVector);
        return clazz.cast(newVector);
      }

      return clazz.cast(v);
    }

    @Override
    public VectorContainer getContainer() {
      return outgoing;
    }

    private <T extends ValueVector> boolean checkIfDecimalsTypesAreDifferent(
        ValueVector v, Field field) {
      if (field.getType().getTypeID() != ArrowType.ArrowTypeID.Decimal) {
        return false;
      }
      return !v.getField().getType().equals(field.getType());
    }

    @Override
    public ValueVector getVector(String name) {
      return fieldVectorMap.get((name != null) ? name.toLowerCase() : name);
    }

    @Override
    public Collection<ValueVector> getVectors() {
      return fieldVectorMap.values();
    }

    @Override
    public void allocate(int recordCount) {
      for (final ValueVector v : fieldVectorMap.values()) {
        AllocationHelper.allocate(v, recordCount, 50, 10);
      }
    }

    public void allocate() {
      for (final ValueVector v : fieldVectorMap.values()) {
        v.allocateNew();
      }
    }

    @Override
    public ArrowBuf getManagedBuffer() {
      return context.getManagedBuffer();
    }

    @Override
    public CallBack getCallBack() {
      return callBack;
    }

    @Override
    public boolean getAndResetSchemaChanged() {
      boolean schemaChanged = callBack.getSchemaChangedAndReset() || outgoing.isNewSchema();
      Preconditions.checkState(!callBack.getSchemaChanged(), "Unexpected state");
      return schemaChanged;
    }

    @Override
    public boolean getSchemaChanged() {
      return outgoing.isNewSchema() || callBack.getSchemaChanged();
    }
  }

  @Override
  public void close() throws Exception {
    final List<AutoCloseable> closeables = new ArrayList<>(runtimeFilters.size() + 4);
    closeables.add(outgoing);
    closeables.add(currentReader);
    closeables.add(readers);
    closeables.addAll(runtimeFilters);
    AutoCloseables.close(closeables);
    addDisplayStatsWithZeroValue(context, EnumSet.allOf(Metric.class));
    OperatorStats operatorStats = context.getStats();
    operatorStats.setReadIOStats();
    operatorStats.setScanRuntimeFilterDetailsInProfile();
    operatorStats.setParquetDecodingDetailsInfosInProfile();
    onScanDone();
    if (maxRowSize >= warnThreshold) {
      logger.warn("Max row size scanned by this Scan Operator #" + this + " - " + maxRowSize);
    }
  }

  protected void onScanDone() {
    if (!context.getOptions().getOption(ExecConstants.ENABLE_BOOSTING)) {
      logger.debug("Not starting boost fragment since support option is disabled");
      return;
    }

    if (!(config instanceof ParquetSubScan)) {
      logger.debug("Not starting boost fragment since scan is not parquet-scan");
      return;
    }

    if (!((ParquetSubScan) config).isArrowCachingEnabled()
        && !context.getOptions().getOption(ExecConstants.ENABLE_PARQUET_ARROW_CACHING)) {
      logger.debug("Not starting boost fragment since boost flag/support option is disabled");
      return;
    }

    if (columnsToBoost.isEmpty()) {
      logger.debug("Not starting boost fragment since columnsToBoost is empty");
      return;
    }

    try {
      createAndExecuteBoostFragment();
    } catch (Exception e) {
      logger.error("Failure while executing boost fragment.", e);
    }
  }

  private void createAndExecuteBoostFragment() throws ForemanSetupException {
    BoostPOP boost = config.getBoostConfig(new ArrayList<>(columnsToBoost));

    Screen root =
        new Screen(
            OpProps.prototype(boost.getProps().getOperatorId() + 1, 1_000_000, Long.MAX_VALUE),
            boost,
            true); // screen operator should not send any messages to coord

    UserBitShared.QueryId queryId = context.getQueryIdForLocalQuery();

    int minorFragmentId = 0;
    int majorFragmentId = 0;
    ExecProtos.FragmentHandle handle =
        ExecProtos.FragmentHandle.newBuilder()
            .setMajorFragmentId(majorFragmentId)
            .setMinorFragmentId(minorFragmentId)
            .setQueryId(queryId)
            .build();

    // get plan as JSON
    ByteString plan;
    ByteString optionsData;
    try {
      plan =
          ProtobufByteStringSerDe.writeValue(
              context.getLpPersistence().getMapper(), root, ProtobufByteStringSerDe.Codec.NONE);
      optionsData =
          ProtobufByteStringSerDe.writeValue(
              context.getLpPersistence().getMapper(),
              context.getOptions().getNonDefaultOptions(),
              ProtobufByteStringSerDe.Codec.NONE);
    } catch (JsonProcessingException e) {
      throw new ForemanSetupException("Failure while trying to convert fragment into json.", e);
    }

    CoordExecRPC.PlanFragmentMajor major =
        CoordExecRPC.PlanFragmentMajor.newBuilder()
            .setForeman(foremanEndpoint) // get foreman from Scan
            .setFragmentJson(plan)
            .setHandle(handle.toBuilder().clearMinorFragmentId().build())
            .setLeafFragment(true)
            .setContext(queryContextInfo)
            .setMemInitial(root.getProps().getMemReserve() + boost.getProps().getMemReserve())
            .setOptionsJson(optionsData)
            .setCredentials(
                UserBitShared.UserCredentials.newBuilder()
                    .setUserName(config.getProps().getUserName())
                    .build())
            .setPriority(
                CoordExecRPC.FragmentPriority.newBuilder()
                    .setWorkloadClass(UserBitShared.WorkloadClass.BACKGROUND)
                    .build())
            .setFragmentCodec(CoordExecRPC.FragmentCodec.NONE)
            .addAllAllAssignment(Collections.emptyList())
            .build();

    // minor with empty assignment, collector, attrs
    CoordExecRPC.PlanFragmentMinor minor =
        CoordExecRPC.PlanFragmentMinor.newBuilder()
            .setMajorFragmentId(majorFragmentId)
            .setMinorFragmentId(minorFragmentId)
            .setAssignment(CoordinationProtos.NodeEndpoint.newBuilder().build())
            .setMemMax(queryContextInfo.getQueryMaxAllocation())
            .addAllCollector(Collections.emptyList())
            .addAllAttrs(Collections.emptyList())
            .build();

    logger.debug(
        "Starting boost fragment with queryID: {} to boost columns {} of table [{}]",
        QueryIdHelper.getQueryId(queryId),
        boost.getColumns(),
        config.getReferencedTables().stream()
            .flatMap(Collection::stream)
            .collect(Collectors.joining(".")));

    context.startFragmentOnLocal(new PlanFragmentFull(major, minor));
  }
}
