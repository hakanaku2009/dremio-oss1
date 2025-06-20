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
package com.dremio.exec.exception;

/**
 * Exception used to indicate a source configuration was loaded but no corresponding SourceType was
 * available.
 */
public class MissingSourceTypeException extends RuntimeException {

  private final String sourceType;

  public MissingSourceTypeException(String sourceType, String message) {
    super(message);
    this.sourceType = sourceType;
  }

  public String getSourceType() {
    return sourceType;
  }

  @Override
  public String getMessage() {
    switch (sourceType) {
      case "DB2":
        return "DB2 source type is no longer supported.";

      case "HBASE":
        return "HBase source type is not installed. Please download it from Dremio Hub: https://github.com/dremio-hub.";

      default:
        return super.getMessage();
    }
  }
}
