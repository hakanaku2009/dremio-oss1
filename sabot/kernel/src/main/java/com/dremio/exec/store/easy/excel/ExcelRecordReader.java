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
package com.dremio.exec.store.easy.excel;

import static java.util.stream.Collectors.toCollection;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.easy.excel.ExcelParser.State;
import com.dremio.exec.store.easy.excel.xls.XlsInputStream;
import com.dremio.exec.store.easy.excel.xls.XlsRecordProcessor;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.scan.OutputMutator;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.complex.impl.VectorContainerWriter;
import org.apache.poi.ooxml.POIXMLException;

/** {@link RecordReader} implementation for reading a single sheet in an Excel file. */
public class ExcelRecordReader extends AbstractRecordReader
    implements XlsInputStream.BufferManager {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ExcelRecordReader.class);

  private final ExcelFormatPluginConfig pluginConfig;
  private final OperatorContext executionContext;
  private final FileSystem dfs;
  private final Path path;

  private Set<String> columnsToProject;
  private VectorContainerWriter writer;
  private InputStream inputStream;
  private long runningRecordCount;
  private ExcelParser parser;
  private boolean rowSizeLimitEnabledForReader;

  public ExcelRecordReader(
      final OperatorContext executionContext,
      final FileSystem dfs,
      final Path path,
      final ExcelFormatPluginConfig pluginConfig,
      final List<SchemaPath> columns) {
    super(executionContext, columns);
    this.executionContext = executionContext;
    this.dfs = dfs;
    this.path = path;
    this.pluginConfig = pluginConfig;
    this.rowSizeLimitEnabledForReader = rowSizeLimitEnabled;

    // Get the list of columns to project, build a lookup table and pass it to respective parsers
    // for filtering the columns
    if (!isStarQuery() && !isSkipQuery()) {
      this.columnsToProject =
          getColumns().stream()
              .map(sp -> sp.getAsNamePart().getName())
              .collect(toCollection(HashSet::new));
      logger.debug("Number of projected columns: {}", columnsToProject.size());
    }
  }

  @Override
  public String getFilePath() {
    return path != null ? path.toString() : "";
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    // Reason for enabling the union by default is because Excel documents are highly likely to
    // contain
    // mixed types and the sampling code doesn't take enable union by default.
    this.writer = new VectorContainerWriter(output);
    final ArrowBuf managedBuf = executionContext.getManagedBuffer();

    try {
      inputStream = dfs.open(path);
      final int maxCellSize =
          Math.toIntExact(context.getOptions().getOption(ExecConstants.LIMIT_FIELD_SIZE_BYTES));

      if (pluginConfig.xls) {
        // we don't need to close this stream, it will be closed by the parser
        final XlsInputStream xlsStream = new XlsInputStream(this, inputStream);
        parser =
            new XlsRecordProcessor(
                xlsStream,
                pluginConfig,
                writer,
                managedBuf,
                columnsToProject,
                isSkipQuery(),
                maxCellSize);
      } else {
        parser =
            new StAXBasedParser(
                inputStream,
                pluginConfig,
                writer,
                managedBuf,
                columnsToProject,
                isSkipQuery(),
                maxCellSize);
      }
      if (rowSizeLimitEnabled) {
        if (fixedDataLenPerRow <= rowSizeLimit && variableVectorCount == 0) {
          rowSizeLimitEnabledForReader = false;
        }
        if (fixedDataLenPerRow > rowSizeLimit) {
          throw RowSizeLimitExceptionHelper.createRowSizeLimitException(
              rowSizeLimit, RowSizeLimitExceptionType.READ, logger);
        }
        rowSizeLimit -= fixedDataLenPerRow;
      }
    } catch (final SheetNotFoundException e) {
      // This check will move to schema validation in planning after DX-2271
      throw UserException.validationError(e)
          .message(
              "There is no sheet with given name '%s' in Excel document located at '%s'",
              pluginConfig.sheet, path)
          .build(logger);
    } catch (POIXMLException e) {
      // strict OOXML is not fully supported
      if (e.getMessage().contains("Strict OOXML")) {
        throw UserException.dataReadError(e)
            .message(
                "Strict OXMl is not supported. Please save %s in standard (.xlsx) format.", path)
            .build(logger);
      }
      throw UserException.dataReadError(e)
          .message("OOXML file structure broken/invalid - no core document found!")
          .build(logger);
    } catch (Throwable e) {
      throw UserException.dataReadError(e)
          .message("Failure creating parser for entry '%s'", path)
          .build(logger);
    }
  }

  @Override
  public ArrowBuf allocate(int size) {
    return executionContext.getManagedBuffer(size);
  }

  @Override
  public int next() {
    writer.allocate();
    writer.reset();

    int recordCount = 0;
    try {
      while (recordCount < numRowsPerBatch) {
        writer.setPosition(recordCount);

        final ExcelParser.State state = parser.parseNextRecord();
        if (rowSizeLimitEnabledForReader) {
          RowSizeLimitExceptionHelper.checkSizeLimit(
              parser.getAndResetRowSize(), rowSizeLimit, RowSizeLimitExceptionType.READ, logger);
        }

        if (state == State.READ_SUCCESSFUL) {
          recordCount++;
          runningRecordCount++;
        } else {
          break;
        }
      }

      writer.setValueCount(recordCount);
      return recordCount;
    } catch (final Exception e) {
      throw UserException.dataReadError(e)
          .message("Failed to parse records in row number %d", runningRecordCount)
          .build(logger);
    }
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(parser, inputStream);
  }
}
