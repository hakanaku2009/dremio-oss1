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
package com.dremio.dac.model.folder;

import static com.dremio.common.utils.PathUtils.encodeURIComponent;

import com.dremio.dac.model.common.NamespacePath;
import com.dremio.dac.model.common.RootEntity;
import com.dremio.dac.model.job.JobFilters;
import com.dremio.dac.model.namespace.NamespaceTree;
import com.dremio.service.jobs.JobIndexKeys;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.space.proto.ExtendedConfig;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Folder model. */
@JsonIgnoreProperties(
    value = {"fullPathList", "links"},
    allowGetters = true)
public class FolderModel {

  private final String id;
  private final String name;
  private final Boolean physicalDataset;
  private final ExtendedConfig extendedConfig;
  private final String version;
  private final NamespacePath folderPath;
  private final boolean fileSystemFolder;
  private final boolean queryable;
  private final FileFormat fileFormat;
  private final NamespaceTree contents;
  private final List<String> userInputtedLabels;
  private final String tagUsedForConcurrencyControl;
  private final int jobCount;
  private final String storageUri;

  @JsonCreator
  public FolderModel(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("urlPath") String urlPath,
      @JsonProperty("isPhysicalDataset") Boolean isPhysicalDataset,
      @JsonProperty("isFileSystemFolder") boolean isFileSystemFolder,
      @JsonProperty("isQueryable") boolean isQueryable,
      @JsonProperty("extendedConfig") ExtendedConfig extendedConfig,
      @JsonProperty("version") String version,
      @JsonProperty("fileformat") FileFormat fileFormat,
      @JsonProperty("contents") NamespaceTree contents,
      @JsonProperty("tags") List<String> userInputtedLabels,
      @JsonProperty("jobCount") int jobCount,
      @JsonProperty("storageUri") String storageUri,
      @JsonProperty("tag") String tag) {
    this.id = id;
    this.folderPath = FolderModel.parseUrlPath(urlPath);
    this.name = name;
    this.physicalDataset = isPhysicalDataset;
    this.fileSystemFolder = isFileSystemFolder;
    this.queryable = isQueryable;
    this.extendedConfig = extendedConfig;
    this.version = version;
    this.fileFormat = fileFormat;
    this.contents = contents;
    this.userInputtedLabels = userInputtedLabels;
    this.jobCount = jobCount;
    this.storageUri = storageUri;
    this.tagUsedForConcurrencyControl = tag;
  }

  public String getId() {
    return id;
  }

  public NamespaceTree getContents() {
    return contents;
  }

  public boolean isQueryable() {
    return queryable;
  }

  public boolean isFileSystemFolder() {
    return fileSystemFolder;
  }

  public String getUrlPath() {
    return folderPath.toUrlPath();
  }

  public List<String> getFullPathList() {
    return folderPath.toPathList();
  }

  public String getName() {
    return name;
  }

  public Boolean getIsPhysicalDataset() {
    return physicalDataset;
  }

  public ExtendedConfig getExtendedConfig() {
    return extendedConfig;
  }

  public String getVersion() {
    return version;
  }

  public List<String> getTags() {
    return userInputtedLabels;
  }

  public String getStorageUri() {
    return storageUri;
  }

  public String getTag() {
    return tagUsedForConcurrencyControl;
  }

  @JsonIgnore
  public FileFormat getFileFormat() {
    return fileFormat;
  }

  public Map<String, String> getLinks() {
    Map<String, String> links = new HashMap<>();
    links.put("self", folderPath.toUrlPath());

    // always include query url because set file format response doesn't include it.
    links.put("query", folderPath.getQueryUrlPath());

    if (fileSystemFolder) {
      links.put("format", folderPath.toUrlPathWithAction("folder_format"));
      links.put("format_preview", folderPath.toUrlPathWithAction("folder_preview"));
      if (queryable && fileFormat != null && fileFormat.getVersion() != null) {
        final String version = fileFormat.getVersion();
        links.put(
            "delete_format",
            folderPath.toUrlPathWithAction("folder_format")
                + "?version="
                + (version == null ? version : encodeURIComponent(version)));
        // overwrite jobs link since this folder is queryable
        final JobFilters jobFilters =
            new JobFilters()
                .addFilter(JobIndexKeys.ALL_DATASETS, folderPath.toString())
                .addFilter(JobIndexKeys.QUERY_TYPE, JobIndexKeys.UI, JobIndexKeys.EXTERNAL);
        links.put("jobs", jobFilters.toUrl());
      }
    } else {
      if (folderPath.getRoot().getRootType() == RootEntity.RootType.HOME) {
        links.put("upload_start", folderPath.toUrlPathWithAction("upload_start"));
        // for getting format of children
        links.put("file_format", folderPath.toUrlPathWithAction("folder_format"));
        links.put("file_prefix", folderPath.toUrlPathWithAction("file"));
      }
    }
    // add jobs if not already added.
    if (!links.containsKey("jobs")) {
      final JobFilters jobFilters =
          new JobFilters()
              .addContainsFilter(folderPath.toNamespaceKey().toString())
              .addFilter(JobIndexKeys.QUERY_TYPE, "UI", "EXTERNAL");
      links.put("jobs", jobFilters.toUrl());
    }
    return links;
  }

  public int getJobCount() {
    return jobCount;
  }

  private static final Pattern PARSER = Pattern.compile("/([^/]+)/([^/]+)/[^/]+/(.*)");
  private static final Function<String, String> PATH_DECODER =
      input -> URLDecoder.decode(input, StandardCharsets.UTF_8);

  static NamespacePath parseUrlPath(String urlPath) {
    Matcher m = PARSER.matcher(urlPath);
    if (m.matches()) {
      List<String> pathParts =
          Stream.concat(Stream.of(m.group(2)), Stream.of(m.group(3).split("/")))
              .map(PATH_DECODER)
              .collect(ImmutableList.toImmutableList());

      if (m.group(1).equals("source")) {
        return new SourceFolderPath(pathParts);
      } else {
        return new FolderPath(pathParts);
      }
    }
    throw new IllegalArgumentException("Not a valid filePath: " + urlPath);
  }
}
