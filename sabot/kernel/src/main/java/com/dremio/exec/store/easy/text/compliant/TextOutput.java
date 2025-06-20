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
package com.dremio.exec.store.easy.text.compliant;

import com.dremio.common.exceptions.UserException;

/* Base class for producing output record batches while dealing with
 * Text files.
 */
abstract class TextOutput {
  protected final int maxCellLimit;

  protected TextOutput(int maxCellLimit) {
    this.maxCellLimit = maxCellLimit;
  }

  /** Initialise the object */
  public void init() {}

  /**
   * Start processing a new field within a record.
   *
   * @param index index within the record
   */
  public abstract void startField(int index);

  /**
   * End processing a field within a record.
   *
   * @return true if engine should continue processing record. false if rest of record can be
   *     skipped.
   */
  public abstract boolean endField();

  /**
   * Shortcut that lets the output know that we are closing ending a field with no data.
   *
   * @return true if engine should continue processing record. false if rest of record can be
   *     skipped.
   */
  public abstract boolean endEmptyField();

  /**
   * Add the provided data but drop any whitespace.
   *
   * @param data
   */
  public void appendIgnoringWhitespace(byte data) {
    if (TextReader.isWhite(data)) {
      // noop
    } else {
      append(data);
    }
  }

  /**
   * This function appends the byte to the output character data buffer
   *
   * @param data current byte read
   */
  public abstract void append(byte data);

  /**
   * Completes the processing of a given record. Also completes the processing of the last field
   * being read.
   */
  public abstract void finishRecord();

  /** Return the total number of records (across batches) processed */
  public abstract long getRecordCount();

  /** Informs output to setup for new record batch. */
  public abstract void startBatch();

  /**
   * Does any final cleanup that is required for closing a batch. Example might include closing the
   * last field.
   */
  public abstract void finishBatch();

  /** Helper method to check if the current record has any non-empty fields */
  public abstract boolean rowHasData();

  public void close() {
    // no op
  }

  public int getFieldCurrentDataPointer() {
    throw UserException.unsupportedError()
        .message("Unsupported operation \"getCurrentDataPointer\"")
        .buildSilently();
  }

  public void setFieldCurrentDataPointer(int currentDataPointer) {
    throw UserException.unsupportedError()
        .message("Unsupported operation \"setCurrentDataPointer\"")
        .buildSilently();
  }

  abstract int currentFieldIndex();

  abstract int currentBatchIndex();

  abstract boolean hasSelectedColumns();
}
