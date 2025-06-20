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
package com.dremio.exec.store;

import com.dremio.common.expression.CompleteType;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.types.Types;
import com.dremio.common.util.MajorTypeHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.SystemSchemas.SystemColumnStatistics;
import com.dremio.exec.store.iceberg.IcebergPartitionData;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

/** Record writer interface for writing a record batch to persistent storage. */
public interface RecordWriter extends AutoCloseable {

  String FRAGMENT_COLUMN = "Fragment";
  String FILESIZE_COLUMN = "FileSize";
  String PATH_COLUMN = "Path";
  String METADATA_COLUMN = "Metadata";
  String PARTITION_COLUMN = "Partition";
  String RECORDS_COLUMN = "Records";
  String ICEBERG_METADATA_COLUMN = "IcebergMetadata";
  String FILE_SCHEMA_COLUMN = "fileschema";
  String PARTITION_DATA_COLUMN = "PartitionData";
  String OPERATION_TYPE_COLUMN = "OperationType";
  String PARTITION_VALUE_COLUMN = "PartitionValue";
  String REJECTED_RECORDS_COLUMN = "RejectedRecords";
  String REFERENCED_DATA_FILES_COLUMN = "ReferencedDataFiles";

  BatchSchema SCHEMA =
      BatchSchema.newBuilder()
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  FRAGMENT_COLUMN, Types.optional(MinorType.VARCHAR)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  RECORDS_COLUMN, Types.optional(MinorType.BIGINT)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  PATH_COLUMN, Types.optional(MinorType.VARCHAR)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  METADATA_COLUMN, Types.optional(MinorType.VARBINARY)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  PARTITION_COLUMN, Types.optional(MinorType.INT)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  FILESIZE_COLUMN, Types.optional(MinorType.BIGINT)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  ICEBERG_METADATA_COLUMN, Types.optional(MinorType.VARBINARY)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  FILE_SCHEMA_COLUMN, Types.optional(MinorType.VARBINARY)))
          .addField(
              new Field(
                  PARTITION_DATA_COLUMN,
                  FieldType.nullable(CompleteType.LIST.getType()),
                  Collections.singletonList(
                      Field.nullable("$data$", CompleteType.VARBINARY.getType()))))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  OPERATION_TYPE_COLUMN, Types.optional(MinorType.INT)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  PARTITION_VALUE_COLUMN, Types.optional(MinorType.VARCHAR)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  REJECTED_RECORDS_COLUMN, Types.optional(MinorType.BIGINT)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  REFERENCED_DATA_FILES_COLUMN, Types.optional(MinorType.VARBINARY)))
          .setSelectionVectorMode(SelectionVectorMode.NONE)
          .build();

  Field FRAGMENT = SCHEMA.getColumn(0);
  Field RECORDS = SCHEMA.getColumn(1);
  Field PATH = SCHEMA.getColumn(2);
  Field METADATA = SCHEMA.getColumn(3);
  Field PARTITION = SCHEMA.getColumn(4);
  Field FILESIZE = SCHEMA.getColumn(5);
  Field ICEBERG_METADATA = SCHEMA.getColumn(6);
  Field FILE_SCHEMA = SCHEMA.getColumn(7);
  Field PARTITION_DATA = SCHEMA.getColumn(8);
  Field OPERATION_TYPE = SCHEMA.getColumn(9);
  Field PARTITION_VALUE = SCHEMA.getColumn(10);
  Field REJECTED_RECORDS = SCHEMA.getColumn(11);
  Field REFERENCED_DATA_FILES = SCHEMA.getColumn(12);

  /**
   * @param incoming
   * @param listener Listener informed when an output entry has been written out
   * @param statsListener Listener informed on details of write(s) of record batches
   */
  void setup(
      final VectorAccessible incoming,
      OutputEntryListener listener,
      WriteStatsListener statsListener)
      throws IOException;

  /**
   * Write the given record batch. It is callers responsibility to release the batch.
   *
   * @return Number of records written.
   */
  void startPartition(WritePartition partition) throws Exception;

  /**
   * Write the given record batch. It is callers responsibility to release the batch.
   *
   * @param offset The offset
   * @param length
   * @return
   * @throws IOException
   */
  int writeBatch(int offset, int length) throws IOException;

  /**
   * Stop writing, delete any written contents and cleanup.
   *
   * @throws IOException
   */
  void abort() throws IOException;

  default FileSystem getFs() {
    throw new UnsupportedOperationException("Unable to get filesystem");
  }

  /**
   * If exists and ready to produce, process output. A pending output may take multiple calls to
   * fully process.
   *
   * @param completedInput has all incoming data been sent for consumption
   * @param reachedOutputLimit has the record limit defined by the record-writer been reached
   * @return whether the pending output has been completed, i.e. if the writer can move forward
   */
  default boolean processPendingOutput(boolean completedInput, boolean reachedOutputLimit)
      throws Exception {
    return false;
  }

  default Path getLocation() {
    throw new UnsupportedOperationException("Unable to get location");
  }

  /**
   * Listener that is informed of any output entries that have been returned. Depending on the
   * source, this could be files, a database path, etc.
   */
  interface OutputEntryListener {
    void recordsWritten(
        long recordCount,
        long fileSize,
        String path,
        byte[] metadata,
        Integer partitionNumber,
        byte[] icebergMetadata,
        byte[] schema,
        Collection<IcebergPartitionData> partition,
        Integer operationType,
        String partitionValue,
        long rejectedRecordCount,
        byte[] referencedDataFiles);
  }

  /** Listens to write details: number of bytes written, number of files written */
  interface WriteStatsListener {
    /** Record the act of writing 'byteCount' bytes to an output file */
    void bytesWritten(long byteCount);

    default void updateSystemColumnLowerBounds() {}

    default void updateSystemColumnUpperBounds() {}

    default List<SystemColumnStatistics> getSystemColumnStatistics() {
      return Collections.emptyList();
    }

    // the offset in current incoming batch
    default void setBatchOffset(int offset) {}
  }
}
