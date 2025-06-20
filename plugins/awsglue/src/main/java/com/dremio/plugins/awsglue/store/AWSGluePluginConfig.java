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
package com.dremio.plugins.awsglue.store;

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.AWSAuthenticationType;
import com.dremio.exec.catalog.conf.AWSRegionSelection;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.DefaultCtasFormatSelection;
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.DoNotDisplay;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.catalog.conf.SourceType;
import io.protostuff.Tag;
import java.util.List;
import javax.inject.Provider;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/** Connection Configuration for AWSGLUE. */
@SourceType(value = "AWSGLUE", label = "AWS Glue Data Catalog", uiConfig = "awsglue-layout.json")
public class AWSGluePluginConfig extends ConnectionConf<AWSGluePluginConfig, AWSGlueStoragePlugin> {

  @Tag(1)
  @DisplayMetadata(label = "AWS Region")
  public AWSRegionSelection regionNameSelection = AWSRegionSelection.US_WEST_2;

  @Tag(2)
  @DisplayMetadata(label = "AWS Access Key")
  public String accessKey = "";

  @Tag(3)
  @Secret
  @DisplayMetadata(label = "AWS Access Secret")
  public SecretRef accessSecret = SecretRef.empty();

  @Tag(4)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Encrypt connection")
  public boolean secure = true;

  @Tag(5)
  @DisplayMetadata(label = "Connection Properties")
  public List<Property> propertyList;

  @Tag(6)
  public AWSAuthenticationType credentialType = AWSAuthenticationType.ACCESS_KEY;

  @Tag(7)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable asynchronous access when possible")
  public boolean enableAsync = true;

  @Tag(8)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable local caching when possible")
  public boolean isCachingEnabled = true;

  @Tag(9)
  @NotMetadataImpacting
  @Min(value = 1, message = "Max percent of total available cache space must be between 1 and 100")
  @Max(
      value = 100,
      message = "Max percent of total available cache space must be between 1 and 100")
  @DisplayMetadata(label = "Max percent of total available cache space to use when possible")
  public int maxCacheSpacePct = 100;

  @Tag(10)
  @DisplayMetadata(label = "IAM Role to Assume")
  public String assumedRoleARN;

  @Tag(11)
  @DisplayMetadata(label = "AWS Profile")
  public String awsProfile;

  @Tag(12)
  @NotMetadataImpacting
  @DoNotDisplay
  @DisplayMetadata(label = "Default CTAS Format")
  public DefaultCtasFormatSelection defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;

  public String getDefaultCtasFormat() {
    return defaultCtasFormat.getDefaultCtasFormat();
  }

  @Override
  public AWSGlueStoragePlugin newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    return new AWSGlueStoragePlugin(this, pluginSabotContext, name, pluginIdProvider);
  }
}
