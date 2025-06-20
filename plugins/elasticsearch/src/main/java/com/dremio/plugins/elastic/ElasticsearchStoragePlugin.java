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
package com.dremio.plugins.elastic;

import static com.dremio.exec.ExecConstants.ELASTIC_ACTION_RETRIES_VALIDATOR;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.InvalidMetadataErrorContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.elastic.proto.ElasticReaderProto;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.catalog.CurrentSchemaOption;
import com.dremio.exec.catalog.MetadataObjectsUtils;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.conf.EncryptionValidationMode;
import com.dremio.exec.catalog.conf.Host;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.options.OptionManager;
import com.dremio.plugins.elastic.ElasticActions.Health;
import com.dremio.plugins.elastic.ElasticActions.IndexExists;
import com.dremio.plugins.elastic.ElasticActions.Result;
import com.dremio.plugins.elastic.ElasticConnectionPool.ElasticConnection;
import com.dremio.plugins.elastic.ElasticConnectionPool.TLSValidationMode;
import com.dremio.plugins.elastic.mapping.ElasticMappingSet;
import com.dremio.plugins.elastic.mapping.ElasticMappingSet.ClusterMetadata;
import com.dremio.plugins.elastic.mapping.ElasticMappingSet.ElasticIndex;
import com.dremio.plugins.elastic.mapping.ElasticMappingSet.ElasticMapping;
import com.dremio.plugins.elastic.mapping.SchemaMerger;
import com.dremio.plugins.elastic.planning.ElasticRulesFactory;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.BooleanCapabilityValue;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.InvalidProtocolBufferException;
import io.protostuff.ByteStringUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Storage plugin for elasticsearch.
 *
 * <p>If using Shield, we need the following privileges:
 *
 * <p># dremio user dremio_user: cluster: - cluster:monitor/nodes/info - cluster:monitor/state -
 * cluster:monitor/health indices: 'twitter' : - read - indices:monitor/stats -
 * indices:admin/mappings/get - indices:admin/shards/search_shards
 */
public class ElasticsearchStoragePlugin implements StoragePlugin, SupportsListingDatasets {

  private static final Logger logger = LoggerFactory.getLogger(ElasticsearchStoragePlugin.class);

  private final String name;
  private final PluginSabotContext context;
  private final ElasticsearchConf config;
  private final ElasticConnectionPool connectionPool;
  private final boolean isValuesCastEnabled;

  public ElasticsearchStoragePlugin(
      ElasticsearchConf config, PluginSabotContext context, String name) {
    this.config = config;
    this.context = context;
    this.name = name;
    this.isValuesCastEnabled =
        context.getOptionManager().getOption(PlannerSettings.VALUES_CAST_ENABLED);

    final TLSValidationMode tlsMode;
    if (!config.isSslEnabled()) {
      tlsMode = TLSValidationMode.OFF;
    } else {
      // If encryption is enabled, but validation is not set (upgrade?),
      // assume the highest level of security.
      final EncryptionValidationMode encryptionValidationMode =
          Optional.ofNullable(config.getEncryptionValidationMode())
              .orElse(EncryptionValidationMode.CERTIFICATE_AND_HOSTNAME_VALIDATION);
      switch (encryptionValidationMode) {
        case CERTIFICATE_AND_HOSTNAME_VALIDATION:
          tlsMode = TLSValidationMode.STRICT;
          break;
        case CERTIFICATE_ONLY_VALIDATION:
          tlsMode = TLSValidationMode.VERIFY_CA;
          break;
        case NO_VALIDATION:
          tlsMode = TLSValidationMode.UNSECURE;
          break;
        default:
          // Should not happen since enum is in the config class
          throw new AssertionError(
              "Unknown encryption validation mode " + encryptionValidationMode);
      }
    }

    try {
      this.connectionPool =
          new ElasticConnectionPool(
              config.getHostList(),
              tlsMode,
              new ElasticsearchAuthentication(
                  config.getHostList(),
                  config.getAuthenticationType(),
                  config.getUsername(),
                  config.getPassword(),
                  config.getAccessKey(),
                  config.getAccessSecret(),
                  config.getRegionName(),
                  config.getAwsProfile()),
              config.getReadTimeoutMillis(),
              config.isUseWhitelist(),
              context.getOptionManager().getOption(ELASTIC_ACTION_RETRIES_VALIDATOR));
    } catch (IllegalArgumentException e) {
      throw UserException.connectionError(e)
          .message(
              "Failed to authenticate with Elasticsearch cluster %s",
              config.getHostList().stream().map(Host::toCompound).collect(Collectors.joining(",")))
          .build();
    }
  }

  public PluginSabotContext getContext() {
    return context;
  }

  public ElasticsearchConf getConfig() {
    return config;
  }

  ElasticConnection getRandomConnection() {
    return connectionPool.getRandomConnection();
  }

  public ElasticConnection getConnection(Iterable<String> hostsIter) {
    AtomicReference<ElasticConnection> connection = new AtomicReference<>();
    checkConnection(
        () -> {
          connection.set(getConnectionImpl(hostsIter));
          return connection.get();
        });
    return connection.get();
  }

  private ElasticConnection getConnectionImpl(Iterable<String> hostsIter) {
    List<String> hosts = ImmutableList.copyOf(hostsIter);
    Set<String> hostSet = ImmutableSet.copyOf(hosts);
    if (hosts.isEmpty()) {
      return getRandomConnection();
    } else {
      final String localAddress = context.getEndpoint().getAddress();
      // If there is a local elastic, use that.
      if (hostSet.contains(localAddress)) {
        return connectionPool.getConnection(ImmutableList.of(localAddress));
      }
      return connectionPool.getConnection(hosts);
    }
  }

  @Override
  public SourceCapabilities getSourceCapabilities() {
    // Lets the sabot kernel, and in turn the planner, know that this source supports CONTAINS
    // (by default, support for CONTAINS is not enabled)
    return new SourceCapabilities(
        new BooleanCapabilityValue(SourceCapabilities.SUPPORTS_CONTAINS, true));
  }

  @Override
  public DatasetConfig createDatasetConfigFromSchema(
      DatasetConfig oldConfig, BatchSchema newSchema) {
    Preconditions.checkNotNull(oldConfig);
    Preconditions.checkNotNull(newSchema);
    OptionManager optManager = context.getOptionManager();
    NamespaceKey key = new NamespaceKey(oldConfig.getFullPathList());

    // its possible that the mapping has changed. If so, we need to re-sample the data. fail the
    // query and retry.
    // If not, make sure we update the schema using the elastic schema merger rather than a general
    // merge behavior.
    int mappingHash;
    try {
      ElasticReaderProto.ElasticTableXattr oldXattr =
          ElasticReaderProto.ElasticTableXattr.parseFrom(
              oldConfig.getReadDefinition().getExtendedProperty().asReadOnlyByteBuffer());
      mappingHash = oldXattr.getMappingHash();
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }

    ElasticMapping mapping = getMapping(key);
    if (mapping == null) {
      throw UserException.dataReadError()
          .message("Unable to find schema information for %s after observing schema change.", key)
          .build(logger);
    }

    BatchSchema oldSchema = CalciteArrowHelper.fromDataset(oldConfig);
    if (optManager.getOption(ExecConstants.ELASTIC_ENABLE_MAPPING_CHECKSUM)) {
      final int latestMappingHash = mapping.hashCode();
      if (mappingHash != latestMappingHash) {
        final UserException.Builder builder =
            UserException.invalidMetadataError()
                .setAdditionalExceptionContext(
                    new InvalidMetadataErrorContext(
                        Collections.singletonList(key.getPathComponents())))
                .addContext("new mapping", mapping.toString());

        final List<Pair<Field, Field>> differentFields = findDiff(oldSchema, newSchema);
        for (Pair<Field, Field> pair : differentFields) {
          if (pair.left == null) {
            builder.addContext("new Field", pair.right.toString());
          } else {
            builder.addContext("different Field", pair.toString());
          }
        }
        throw builder.build(logger);
      }
    }

    SchemaMerger merger =
        new SchemaMerger(new NamespaceKey(oldConfig.getFullPathList()).toString());

    // Since the newlyObserved schema could be partial due to projections, we need to merge it with
    // the original.
    DatasetConfig newConfig =
        DATASET_CONFIG_SERIALIZER.deserialize(DATASET_CONFIG_SERIALIZER.serialize(oldConfig));

    BatchSchema preMergedSchema = oldSchema.merge(newSchema);
    final boolean forceDoublePrecision = config.isForceDoublePrecision();
    SchemaMerger.MergeResult result = merger.merge(mapping, preMergedSchema, forceDoublePrecision);

    try {
      // update the annotations.
      ElasticReaderProto.ElasticTableXattr xattr =
          ElasticReaderProto.ElasticTableXattr.parseFrom(
              newConfig.getReadDefinition().getExtendedProperty().asReadOnlyByteBuffer());
      newConfig
          .getReadDefinition()
          .setExtendedProperty(
              ByteStringUtil.wrap(
                  xattr.toBuilder()
                      .clearAnnotation()
                      .addAllAnnotation(result.getAnnotations())
                      .build()
                      .toByteArray()));
      newConfig.setRecordSchema(ByteStringUtil.wrap(result.getSchema().serialize()));
      return newConfig;
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Pair<Field, Field>> findDiff(BatchSchema a, BatchSchema other) {
    List<Pair<Field, Field>> differentFields = new ArrayList<>();
    Map<String, Field> fieldMap =
        FluentIterable.from(a.getFields())
            .uniqueIndex(
                new Function<Field, String>() {
                  @Override
                  public String apply(Field field) {
                    return field.getName();
                  }
                });

    for (Field field : other) {
      Field oldField = fieldMap.get(field.getName());
      if (!Objects.equals(oldField, field)) {
        differentFields.add(Pair.of(oldField, field));
      }
    }
    return differentFields;
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(
      EntityPath datasetPath, GetDatasetOption... options) {
    if (datasetPath.size() != 3) {
      return Optional.empty();
    }

    final ElasticConnection connection = this.connectionPool.getRandomConnection();
    try {
      final String schema = datasetPath.getComponents().get(1);
      final String type = datasetPath.getComponents().get(2);
      final ClusterMetadata clusterMetadata =
          connection.execute(
              new ElasticActions.GetClusterMetadata().setIndex(schema),
              connectionPool.getMinVersionInCluster().getMajor());
      final List<ElasticIndex> indices = clusterMetadata.getIndices();
      if (indices.isEmpty()) {
        return Optional.empty();
      }

      final ElasticIndex firstIndex = indices.get(0);
      if (firstIndex.getName().equals(schema)) {
        // not an alias.
        final ElasticIndex filteredIndex = firstIndex.filterToType(type);
        if (filteredIndex == null) {
          // no type for this path.
          return Optional.empty();
        }

        Preconditions.checkArgument(
            indices.size() == 1, "More than one Index returned for alias %s.", schema);
        logger.debug(
            "Found mapping: {} for {}:{}",
            filteredIndex.getMergedMapping(isValuesCastEnabled),
            schema,
            type);

        return Optional.of(
            new ElasticDatasetHandle(
                datasetPath,
                connection,
                context,
                config,
                filteredIndex.getMergedMapping(isValuesCastEnabled),
                ImmutableList.<String>of(),
                false));
      } else {

        final ElasticMappingSet ems = new ElasticMappingSet(indices).filterToType(type);
        if (ems.isEmpty()) {
          return Optional.empty();
        }
        final ElasticMapping mapping = ems.getMergedMapping(isValuesCastEnabled);
        final List<String> indicesList =
            indices.stream().map(ElasticIndex::getName).collect(Collectors.toList());

        logger.debug("Found mapping: {} for {}:{}", mapping, schema, type);
        return Optional.of(
            new ElasticDatasetHandle(
                datasetPath, connection, context, config, mapping, indicesList, true));
      }
    } catch (Exception ex) {
      logger.info("Failure while attempting to retrieve dataset {}", datasetPath, ex);
      return Optional.empty();
    }
  }

  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
    final ElasticConnection connection = this.connectionPool.getRandomConnection();
    final ClusterMetadata clusterMetadata =
        connection.execute(
            new ElasticActions.GetClusterMetadata(),
            connectionPool.getMinVersionInCluster().getMajor());
    final ImmutableList.Builder<DatasetHandle> builder = ImmutableList.builder();

    final ArrayListMultimap<ElasticAliasMappingName, ElasticIndex> aliases =
        ArrayListMultimap.create();
    final boolean includeHiddenSchemas = config.isShowHiddenIndices();

    logger.debug("Listing indexes for dataset handles.");
    for (ElasticIndex index : clusterMetadata.getIndices()) {
      for (ElasticMapping mapping : index.getMappings()) {
        if (includeHiddenSchemas || !index.getName().startsWith(".")) {
          final EntityPath key =
              new EntityPath(ImmutableList.of(name, index.getName(), mapping.getName()));
          builder.add(
              new ElasticDatasetHandle(
                  key, connection, context, config, mapping, ImmutableList.<String>of(), false));
        }
        for (String alias : index.getAliases()) {
          aliases.put(
              new ElasticAliasMappingName(alias, mapping.getName()),
              new ElasticIndex(index.getName(), mapping));
        }
      }
    }

    logger.debug("Listing aliases for dataset handles.");
    for (ElasticAliasMappingName alias : aliases.keySet()) {
      final List<ElasticIndex> indices = aliases.get(alias);
      final List<String> indicesList =
          indices.stream().map(ElasticIndex::getName).collect(Collectors.toList());
      final ElasticMappingSet mappingSet = new ElasticMappingSet(indices);
      final ElasticMapping mapping = mappingSet.getMergedMapping(isValuesCastEnabled);
      final EntityPath key =
          new EntityPath(ImmutableList.of(name, alias.getAlias(), mapping.getName()));
      builder.add(
          new ElasticDatasetHandle(key, connection, context, config, mapping, indicesList, true));
    }

    final List<DatasetHandle> datasets = builder.build();
    if (datasets.isEmpty()) {
      logger.debug("No indices/types available. Please make sure to populate the cluster");
    }
    return datasets::iterator;
  }

  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle, PartitionChunkListing chunkListing, GetMetadataOption... options)
      throws ConnectorException {
    final BatchSchema oldSchema = CurrentSchemaOption.getSchema(options);

    ElasticDatasetMetadata datasetMetadata =
        new ElasticDatasetMetadata(
            oldSchema,
            datasetHandle.unwrap(ElasticDatasetHandle.class),
            chunkListing.unwrap(ElasticPartitionChunkListing.class));

    datasetMetadata.build();

    return datasetMetadata;
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options) {
    return new ElasticPartitionChunkListing(datasetHandle.unwrap(ElasticDatasetHandle.class));
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    final NamespaceKey key = MetadataObjectsUtils.toNamespaceKey(containerPath);

    if (key.size() != 2) {
      return false;
    }

    String schema = key.getPathComponents().get(1);

    try {
      IndexExists exists = new IndexExists();
      exists.addIndex(schema);
      return getRandomConnection().executeAndHandleResponseCode(exists, false, "").success();
    } catch (Exception e) {
      logger.warn("Failure while evaluating if index or alias '{}' exists.", key, e);
      return false;
    }
  }

  public ElasticMapping getMapping(NamespaceKey datasetPath) {
    if (datasetPath.size() != 3) {
      return null;
    }

    final ElasticConnection connection = this.connectionPool.getRandomConnection();
    try {
      final String schema = datasetPath.getPathComponents().get(1);
      final String type = datasetPath.getPathComponents().get(2);
      ClusterMetadata clusterMetadata =
          connection.execute(
              new ElasticActions.GetClusterMetadata()
                  .setIndex(datasetPath.getPathComponents().get(1)),
              connectionPool.getMinVersionInCluster().getMajor());
      List<ElasticIndex> indices = clusterMetadata.getIndices();
      if (indices.isEmpty()) {
        return null;
      }

      final ElasticIndex firstIndex = indices.get(0);
      if (firstIndex.getName().equals(schema)) {
        // not an alias.
        ElasticIndex index = firstIndex.filterToType(type);
        if (index == null) {
          // no type for this path.
          return null;
        }
        Preconditions.checkArgument(
            indices.size() == 1, "More than one Index returned for alias %s.", schema);
        return index.getMappings().get(0);
      } else {

        ElasticMappingSet ems = new ElasticMappingSet(indices).filterToType(type);
        if (ems.isEmpty()) {
          return null;
        }
        return ems.getMergedMapping(isValuesCastEnabled);
      }
    } catch (Exception ex) {
      logger.info("Failure while attempting to retrieve dataset {}", datasetPath, ex);
      return null;
    }
  }

  private Result checkConnection(Supplier<ElasticConnection> connection) {
    try {
      return executeHealthAction(connection);
    } catch (UserException originalEx) {
      if (originalEx.getErrorType() == ErrorType.PERMISSION
          && context.getOptionManager().getOption(CatalogOptions.RETRY_CONNECTION_ON_FAILURE)) {
        try {
          connectionPool.connect();
          return executeHealthAction(connection);
        } catch (Exception ex) {
          throw originalEx;
        }
      }
      throw originalEx;
    }
  }

  private Result executeHealthAction(Supplier<ElasticConnection> connection) {
    return connection
        .get()
        .executeAndHandleResponseCode(
            new Health(),
            true,
            "Cannot get cluster health information.  Please make sure that the user has [cluster:monitor/health] privilege.");
  }

  @Override
  public SourceState getState() {
    try {
      final Result result = checkConnection(connectionPool::getRandomConnection);
      if (result.success()) {
        String clusterHealth = result.getAsJsonObject().get("status").getAsString();
        switch (clusterHealth) {
          case "green":
            return SourceState.goodState(
                String.format("Elastic version %s.", connectionPool.getMinVersionInCluster()));
          case "yellow":
            return SourceState.warnState(
                "Elastic cluster health is yellow: more nodes are needed for replicas.",
                "Elastic cluster health is yellow.");
          case "red":
            return SourceState.badState(
                "Elastic cluster health is red. Check for failed cluster nodes and crashing processes due to heavy load.",
                "Elastic cluster health is red.");
          default:
            return SourceState.badState(
                String.format(
                    "Elastic cluster health is unknown health state of %s.", clusterHealth),
                String.format(
                    "Elastic cluster health is unknown health state of %s.", clusterHealth));
        }
      } else {
        return SourceState.badState(
            "Failure getting Elastic health. " + result.getErrorMessage(),
            "Failure getting Elastic health. " + result.getErrorMessage());
      }
    } catch (Exception e) {
      return SourceState.badState(
          "Failure getting Elastic health. " + e.getMessage(),
          "Failure getting Elastic health. " + e.getMessage());
    }
  }

  private static class ElasticAliasMappingName {
    private final String alias;
    private final String mapping;

    public ElasticAliasMappingName(String alias, String mapping) {
      super();
      this.alias = alias;
      this.mapping = mapping;
    }

    public String getAlias() {
      return alias;
    }

    public String getMapping() {
      return mapping;
    }

    @Override
    public boolean equals(final Object other) {
      if (!(other instanceof ElasticAliasMappingName)) {
        return false;
      }
      ElasticAliasMappingName castOther = (ElasticAliasMappingName) other;
      return Objects.equals(alias, castOther.alias) && Objects.equals(mapping, castOther.mapping);
    }

    @Override
    public int hashCode() {
      return Objects.hash(alias, mapping);
    }
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    return true;
  }

  @Override
  public Class<? extends StoragePluginRulesFactory> getRulesFactoryClass() {
    return context
        .getConfig()
        .getClass(
            "dremio.plugins.elastic.rulesfactory",
            StoragePluginRulesFactory.class,
            ElasticRulesFactory.class);
  }

  @Override
  public void start() throws IOException {
    connectionPool.connect();
  }

  @Override
  public void close() throws Exception {
    logger.debug("Closing elasticsearch storage plugin");
    AutoCloseables.close(connectionPool);
  }
}
