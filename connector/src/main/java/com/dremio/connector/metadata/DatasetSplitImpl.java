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
package com.dremio.connector.metadata;

import java.util.List;

/** Default implementation. */
public final class DatasetSplitImpl implements DatasetSplit {

  private final List<DatasetSplitAffinity> affinities;
  private final long sizeInBytes;
  private final long recordCount;
  private final BytesOutput extraInfo;

  public DatasetSplitImpl(
      List<DatasetSplitAffinity> affinities,
      long sizeInBytes,
      long recordCount,
      BytesOutput extraInfo) {
    this.affinities = affinities;
    this.sizeInBytes = sizeInBytes;
    this.recordCount = recordCount;
    this.extraInfo = extraInfo;
  }

  @Override
  public List<DatasetSplitAffinity> getAffinities() {
    return affinities;
  }

  @Override
  public long getSizeInBytes() {
    return sizeInBytes;
  }

  @Override
  public long getRecordCount() {
    return recordCount;
  }

  @Override
  public BytesOutput getExtraInfo() {
    return extraInfo;
  }
}
