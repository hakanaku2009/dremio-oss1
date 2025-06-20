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
package com.dremio.exec.store.sys;

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.work.CacheManagerFilesInfo;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.rocksdb.RocksIterator;

/** Iterator which returns cached files information. */
public class CacheManagerFilesIterator implements Iterator<Object> {
  private final boolean isCachedFileSystem;
  private List<CacheManagerFilesInfo> filesInfoList = new ArrayList<>();
  private int curPos;
  private CacheManagerStatsProvider cacheManagerStatsProvider;
  private RocksIterator fileIterator;

  CacheManagerFilesIterator(PluginSabotContext sabotContext) {
    isCachedFileSystem =
        sabotContext.getFileSystemWrapper().isWrapperFor(CacheManagerStatsProvider.class);

    if (isCachedFileSystem) {
      cacheManagerStatsProvider =
          sabotContext.getFileSystemWrapper().unwrap(CacheManagerStatsProvider.class);
      fileIterator = cacheManagerStatsProvider.getCachedFilesIterator();
      filesInfoList = cacheManagerStatsProvider.getCachedFilesStats(fileIterator);
    }
  }

  @Override
  public boolean hasNext() {
    if (!isCachedFileSystem || fileIterator == null) {
      return false;
    }

    if (curPos == filesInfoList.size()) {
      filesInfoList = cacheManagerStatsProvider.getCachedFilesStats(fileIterator);
      if (filesInfoList.isEmpty()) {
        fileIterator.close();
        return false;
      }
      curPos = 0;
    }
    return true;
  }

  @Override
  public Object next() {
    if (!isCachedFileSystem || fileIterator == null) {
      return null;
    }

    return filesInfoList.get(curPos++);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}
