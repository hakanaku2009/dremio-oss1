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
package com.dremio.exec.store.dfs;

import static com.dremio.exec.store.metadatarefresh.MetadataRefreshExecConstants.METADATA_STORAGE_PLUGIN_NAME;
import static com.dremio.exec.store.metadatarefresh.MetadataRefreshUtils.metadataSourceAvailable;
import static com.dremio.io.file.PathFilters.NO_HIDDEN_FILES;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;

import com.dremio.cache.AuthorizationCacheException;
import com.dremio.cache.AuthorizationCacheService;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogUnsupportedOperationException;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.exceptions.InvalidMetadataErrorContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.common.types.SupportsTypeCoercionsAndUpPromotions;
import com.dremio.common.utils.PathUtils;
import com.dremio.common.utils.SqlUtils;
import com.dremio.config.DremioConfig;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetMetadataVerifyResult;
import com.dremio.connector.metadata.DatasetNotFoundException;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.extensions.SupportsMetadataVerify;
import com.dremio.connector.metadata.extensions.SupportsReadSignature;
import com.dremio.connector.metadata.extensions.ValidateMetadataOption;
import com.dremio.connector.metadata.options.InternalMetadataTableOption;
import com.dremio.connector.metadata.options.MetadataVerifyRequest;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.datastore.LegacyProtobufSerializer;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.AlterTableOption;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUser;
import com.dremio.exec.catalog.CreateTableOptions;
import com.dremio.exec.catalog.CurrentSchemaOption;
import com.dremio.exec.catalog.DatasetSplitsPointer;
import com.dremio.exec.catalog.FileConfigOption;
import com.dremio.exec.catalog.MetadataObjectsUtils;
import com.dremio.exec.catalog.MetadataRequestOptions;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.RollbackOption;
import com.dremio.exec.catalog.SortColumnsOption;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.SupportsFsMutablePlugin;
import com.dremio.exec.catalog.SupportsMutatingViews;
import com.dremio.exec.catalog.SupportsSchemaLearning;
import com.dremio.exec.catalog.TableMutationOptions;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.dotfile.DotFile;
import com.dremio.exec.dotfile.DotFileType;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.exception.NoSupportedUpPromotionOrCoercionException;
import com.dremio.exec.hadoop.HadoopCompressionCodecFactory;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.hadoop.HadoopFileSystemConfigurationAdapter;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.refresh.AbstractRefreshPlanBuilder;
import com.dremio.exec.planner.sql.handlers.refresh.FileSystemFullRefreshPlanBuilder;
import com.dremio.exec.planner.sql.handlers.refresh.FileSystemRefreshIncrementalPlanBuilder;
import com.dremio.exec.planner.sql.handlers.refresh.UnlimitedSplitsMetadataProvider;
import com.dremio.exec.planner.sql.parser.SqlGrant;
import com.dremio.exec.planner.sql.parser.SqlRefreshDataset;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.BlockBasedSplitGenerator;
import com.dremio.exec.store.ClassPathFileSystem;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.LocalSyncableFileSystem;
import com.dremio.exec.store.PartitionNotFoundException;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.SchemaEntity;
import com.dremio.exec.store.SchemaEntity.SchemaEntityType;
import com.dremio.exec.store.SplitsPointer;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.exec.store.TimedRunnable;
import com.dremio.exec.store.dfs.SchemaMutability.MutationType;
import com.dremio.exec.store.file.proto.FileProtobuf.FileSystemCachedEntity;
import com.dremio.exec.store.file.proto.FileProtobuf.FileUpdateKey;
import com.dremio.exec.store.iceberg.DremioFileIO;
import com.dremio.exec.store.iceberg.IcebergExecutionDatasetAccessor;
import com.dremio.exec.store.iceberg.IcebergModelCreator;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.store.iceberg.SupportsIcebergMutablePlugin;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.exec.store.iceberg.SupportsInternalIcebergTable;
import com.dremio.exec.store.iceberg.TableSchemaProvider;
import com.dremio.exec.store.iceberg.TableSnapshotProvider;
import com.dremio.exec.store.iceberg.TimeTravelProcessors;
import com.dremio.exec.store.iceberg.hadoop.IcebergHadoopTableIdentifier;
import com.dremio.exec.store.iceberg.hadoop.IcebergHadoopTableOperations;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.store.iceberg.model.IcebergOpCommitter;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.exec.store.iceberg.model.IcebergTableLoader;
import com.dremio.exec.store.metadatarefresh.UnlimitedSplitsFileDatasetHandle;
import com.dremio.exec.store.metadatarefresh.dirlisting.DirListingRecordReader;
import com.dremio.exec.store.metadatarefresh.footerread.FooterReadTableFunction;
import com.dremio.exec.store.parquet.ParquetScanTableFunction;
import com.dremio.exec.store.parquet.ParquetSplitCreator;
import com.dremio.exec.store.parquet.ScanTableFunction;
import com.dremio.exec.util.FSHealthChecker;
import com.dremio.io.CompressionCodecFactory;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.FileSystemUtils;
import com.dremio.io.file.MorePosixFilePermissions;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.exec.store.easy.proto.EasyProtobuf.EasyDatasetSplitXAttr;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf.ParquetDatasetSplitXAttr;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.PartitionChunkMetadata;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.TableInstance;
import com.dremio.service.namespace.capabilities.BooleanCapabilityValue;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.DatasetNamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf.DatasetSplit;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.dataset.proto.UserDefinedSchemaSettings;
import com.dremio.service.namespace.dirlist.proto.DirListInputSplitProto;
import com.dremio.service.namespace.file.FileFormat;
import com.dremio.service.namespace.file.proto.FileConfig;
import com.dremio.service.namespace.file.proto.FileType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.proto.NameSpaceContainer.Type;
import com.dremio.service.users.SystemUser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.schema.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.io.FileIO;

/** Storage plugin for file system */
public class FileSystemPlugin<C extends FileSystemConf<C, ?>>
    implements StoragePlugin,
        SupportsFsMutablePlugin,
        SupportsMutatingViews,
        SupportsReadSignature,
        AuthorizationCacheService,
        SupportsInternalIcebergTable,
        SupportsIcebergRootPointer,
        SupportsIcebergMutablePlugin,
        SupportsTypeCoercionsAndUpPromotions,
        SupportsMetadataVerify,
        SupportsSchemaLearning {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(FileSystemPlugin.class);

  private static final CompressionCodecFactory codecFactory = HadoopCompressionCodecFactory.DEFAULT;
  private static final BooleanCapabilityValue REQUIRES_HARD_AFFINITY =
      new BooleanCapabilityValue(SourceCapabilities.REQUIRES_HARD_AFFINITY, true);

  private static final int PERMISSION_CHECK_TASK_BATCH_SIZE = 10;

  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final LoadingCache<String, org.apache.hadoop.fs.FileSystem> hadoopFS =
      CacheBuilder.newBuilder()
          .softValues()
          .removalListener(
              new RemovalListener<String, org.apache.hadoop.fs.FileSystem>() {
                @Override
                public void onRemoval(
                    RemovalNotification<String, org.apache.hadoop.fs.FileSystem> notification) {
                  try {
                    if (notification.getValue() != null) {
                      notification.getValue().close();
                    }
                  } catch (IOException e) {
                    // Ignore
                    logger.debug("Could not close filesystem", e);
                  }
                }
              })
          .build(
              new CacheLoader<String, org.apache.hadoop.fs.FileSystem>() {
                @Override
                public org.apache.hadoop.fs.FileSystem load(String user) throws Exception {
                  // If the request proxy user is same as process user name or same as system user,
                  // return the process UGI.
                  final UserGroupInformation loginUser = UserGroupInformation.getLoginUser();
                  final UserGroupInformation ugi;
                  if (user.equals(loginUser.getUserName()) || SYSTEM_USERNAME.equals(user)) {
                    ugi = loginUser;
                  } else {
                    ugi = UserGroupInformation.createProxyUser(user, loginUser);
                  }

                  final PrivilegedExceptionAction<org.apache.hadoop.fs.FileSystem> fsFactory =
                      () -> {
                        // Do not use FileSystem#newInstance(Configuration) as it adds filesystem
                        // into the Hadoop cache :(
                        // Mimic instead Hadoop FileSystem#createFileSystem() method
                        final URI uri = org.apache.hadoop.fs.FileSystem.getDefaultUri(fsConf);
                        final Class<? extends org.apache.hadoop.fs.FileSystem> fsClass =
                            org.apache.hadoop.fs.FileSystem.getFileSystemClass(
                                uri.getScheme(), fsConf);
                        final org.apache.hadoop.fs.FileSystem fs =
                            ReflectionUtils.newInstance(fsClass, fsConf);
                        fs.initialize(uri, fsConf);
                        return fs;
                      };

                  try {
                    return ugi.doAs(fsFactory);
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                }
                ;
              });

  private final String name;
  private final LogicalPlanPersistence lpPersistance;
  private final C config;
  private final PluginSabotContext context;
  private final Path basePath;

  private final Provider<StoragePluginId> idProvider;
  private FileSystem systemUserFS;
  private Configuration fsConf;
  private FormatPluginOptionExtractor optionExtractor;
  protected FormatCreator formatCreator;
  private ArrayList<FormatMatcher> matchers;
  private ArrayList<FormatMatcher> layeredMatchers;
  private List<FormatMatcher> dropFileMatchers;
  protected FSHealthChecker fsHealthChecker;

  public FileSystemPlugin(
      final C config,
      final PluginSabotContext pluginSabotContext,
      final String name,
      Provider<StoragePluginId> idProvider) {
    this.name = name;
    this.config = config;
    this.idProvider = idProvider;
    this.context = pluginSabotContext;
    this.fsConf = FileSystemConfigurationUtils.getNewFsConf(pluginSabotContext.getOptionManager());
    this.lpPersistance = pluginSabotContext.getLpPersistence();
    this.basePath = config.getPath();
  }

  public C getConfig() {
    return config;
  }

  private boolean getIcebergSupportFlag() {
    Preconditions.checkState(systemUserFS != null, "Unexpected state");
    return !systemUserFS.isPdfs();
  }

  public boolean supportsIcebergTables() {
    return getIcebergSupportFlag();
  }

  @Override
  public boolean supportsColocatedReads() {
    return true;
  }

  @Override
  public BatchSchema mergeSchemas(DatasetConfig oldConfig, BatchSchema newSchema) {
    try {
      return CalciteArrowHelper.fromDataset(oldConfig).mergeWithUpPromotion(newSchema, this);
    } catch (NoSupportedUpPromotionOrCoercionException e) {
      if (basePath != null) {
        e.addFilePath(basePath.toString());
      }
      if (oldConfig != null) {
        e.addDatasetPath(oldConfig.getFullPathList());
      }
      throw UserException.unsupportedError(e).message(e.getMessage()).build(logger);
    }
  }

  @Override
  public boolean allowUnlimitedSplits(
      DatasetHandle handle, DatasetConfig datasetConfig, String user) {

    if (!metadataSourceAvailable(context.getCatalogService())) {
      return false;
    }

    PhysicalDataset physicalDataset = datasetConfig.getPhysicalDataset();
    Optional<FileType> fileType = Optional.empty();
    if (physicalDataset != null) {
      if (physicalDataset.getFormatSettings() != null) {
        fileType = Optional.ofNullable(physicalDataset.getFormatSettings().getType());
      }
    }
    boolean isParquetTable =
        fileType
            .map(type -> type == FileType.PARQUET)
            .orElseGet(() -> isParquetTable(handle.getDatasetPath().getComponents(), user));
    if (!isParquetTable) {
      logger.debug(
          "Not using unlimited splits for {} since dataset is not a parquet dataset",
          handle.getDatasetPath().toString());
    }
    return isParquetTable;
  }

  private boolean isParquetTable(List<String> tablePathComponents, String user) {
    try {

      Optional<FileSelection> optionalFileSelection =
          generateFileSelectionForPathComponents(new NamespaceKey(tablePathComponents), user);
      if (!optionalFileSelection.isPresent()) {
        return false;
      }
      final FileSelection fileSelection = optionalFileSelection.get();

      FileSystem fs = createFS(SupportsFsCreation.builder().userName(user));
      Optional<FileFormat> layeredFormat = findLayeredFormatMatch(fs, fileSelection);
      if (layeredFormat.isPresent()) { // ICEBERG or DELTALAKE
        return false;
      }

      // Determining the table format using just one file.
      // This is okay because filesystem tables do not have mixed filetypes
      Optional<FileAttributes> firstFile = fileSelection.getFirstFileIteratively(fs);
      if (!firstFile.isPresent()) { // selection has no files
        return false;
      }

      Optional<FileFormat> fileFormatMatch = findFileFormatMatch(fs, firstFile.get());
      return fileFormatMatch
          .filter(fileFormat -> fileFormat.getFileType() == FileType.PARQUET)
          .isPresent();

    } catch (Exception e) {
      throw UserException.ioExceptionError(e)
          .message("Unknown format type for table '%s'", String.join(".", tablePathComponents))
          .buildSilently();
    }
  }

  @Override
  public void runRefreshQuery(String refreshQuery, String user) throws Exception {
    context
        .getJobsRunner()
        .get()
        .runQueryAsJob(refreshQuery, user, QUERY_TYPE_METADATA_REFRESH, null);
  }

  @Override
  public SourceCapabilities getSourceCapabilities() {
    return systemUserFS.isPdfs()
        ? new SourceCapabilities(REQUIRES_HARD_AFFINITY)
        : SourceCapabilities.NONE;
  }

  protected Configuration getFsConf() {
    return fsConf;
  }

  @Override
  public Configuration getFsConfCopy() {
    return new Configuration(fsConf);
  }

  @Override
  public StoragePluginId getId() {
    return idProvider.get();
  }

  @Override
  public BlockBasedSplitGenerator.SplitCreator createSplitCreator(
      OperatorContext context, byte[] extendedBytes, boolean isInternalIcebergTable) {
    return new ParquetSplitCreator(context, true);
  }

  @Override
  public ScanTableFunction createScanTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig) {
    return new ParquetScanTableFunction(fec, context, props, functionConfig);
  }

  @Override
  public AbstractRefreshPlanBuilder createRefreshDatasetPlanBuilder(
      SqlHandlerConfig config,
      SqlRefreshDataset sqlRefreshDataset,
      UnlimitedSplitsMetadataProvider metadataProvider,
      boolean isFullRefresh) {
    if (isFullRefresh) {
      return new FileSystemFullRefreshPlanBuilder(config, sqlRefreshDataset, metadataProvider);
    } else {
      return new FileSystemRefreshIncrementalPlanBuilder(
          config, sqlRefreshDataset, metadataProvider);
    }
  }

  /**
   * Create a new {@link FileSystem} for a given user.
   *
   * @param b - The SupportsFsCreation builder.
   * @return
   */
  @Override
  public FileSystem createFS(Builder b) throws IOException {
    return context
        .getFileSystemWrapper()
        .wrap(
            newFileSystem(b.userName(), b.operatorContext()),
            name,
            config,
            b.operatorContext(),
            isAsyncEnabledForQuery(b.operatorContext()) && getConfig().isAsyncEnabled(),
            b.isForMetadataRefresh());
  }

  protected FileSystem newFileSystem(String userName, OperatorContext operatorContext)
      throws IOException {
    // Create underlying filesystem
    if (Strings.isNullOrEmpty(userName)) {
      throw new IllegalArgumentException("Invalid value for user name");
    }

    final org.apache.hadoop.fs.FileSystem fs;
    try {
      fs = hadoopFS.get(getFSUser(userName));
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      Throwables.propagateIfPossible(cause, IOException.class);
      throw new RuntimeException(cause != null ? cause : e);
    }

    return HadoopFileSystem.get(
        fs,
        (operatorContext == null) ? null : operatorContext.getStats(),
        isAsyncEnabledForQuery(operatorContext) && getConfig().isAsyncEnabled());
  }

  public String getFSUser(String userName) {
    if (!config.isImpersonationEnabled()) {
      return SystemUser.SYSTEM_USERNAME;
    }

    return userName;
  }

  @Override
  public void clear(String userOrGroup) throws AuthorizationCacheException {
    try {
      hadoopFS.invalidate(userOrGroup);
    } catch (Exception e) {
      logger.debug(e.getMessage());
      throw new AuthorizationCacheException(
          String.format(
              "Encountered an issue invalidating authorization cached for '%s'", userOrGroup));
    }
  }

  @Override
  public void clear() throws AuthorizationCacheException {
    try {
      hadoopFS.invalidateAll();
    } catch (Exception e) {
      logger.debug(e.getMessage());
      throw new AuthorizationCacheException(
          "Encountered an issue invalidating all authorizations cached");
    }
  }

  public Iterable<String> getSubPartitions(
      List<String> table,
      List<String> partitionColumns,
      List<String> partitionValues,
      SchemaConfig schemaConfig)
      throws PartitionNotFoundException {
    List<FileAttributes> fileAttributesList;
    try {
      Path fullPath = PathUtils.toFSPath(resolveTableNameToValidPath(table));
      try (DirectoryStream<FileAttributes> stream =
          createFS(SupportsFsCreation.builder().userName(schemaConfig.getUserName()))
              .list(fullPath, NO_HIDDEN_FILES)) {
        // Need to copy the content of the stream before closing
        fileAttributesList = Lists.newArrayList(stream);
      }
    } catch (IOException e) {
      throw new PartitionNotFoundException("Error finding partitions for table " + table, e);
    }
    return Iterables.transform(fileAttributesList, f -> f.getPath().toURI().toString());
  }

  @Override
  public Class<? extends StoragePluginRulesFactory> getRulesFactoryClass() {
    return context
        .getConfig()
        .getClass(
            "dremio.plugins.dfs.rulesfactory",
            StoragePluginRulesFactory.class,
            FileSystemRulesFactory.class);
  }

  @Override
  public SourceState getState() {
    if (systemUserFS == null) {
      return SourceState.NOT_AVAILABLE;
    }
    if (systemUserFS.isPdfs()
        || ClassPathFileSystem.SCHEME.equals(systemUserFS.getUri().getScheme())) {
      return SourceState.GOOD;
    }
    try {
      fsHealthChecker.healthCheck(config.getPath(), ImmutableSet.of(AccessMode.READ));
    } catch (AccessControlException ace) {
      logger.debug("Falling back to listing of source to check health", ace);
      try {
        systemUserFS.list(config.getPath());
      } catch (Exception e) {
        return SourceState.badState("", e);
      }
    } catch (Exception e) {
      return SourceState.badState("", e);
    }
    return SourceState.GOOD;
  }

  protected void healthCheck(Path path, final Set<AccessMode> mode) throws IOException {
    systemUserFS.access(path, mode);
  }

  @Override
  public Optional<ViewTable> getView(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    if (!Boolean.getBoolean(DremioConfig.LEGACY_STORE_VIEWS_ENABLED)) {
      return Optional.empty();
    }

    try {
      DotFile f = null;
      try {
        Path viewPath = getViewPath(tableSchemaPath);
        FileSystem fileSystem =
            createFS(SupportsFsCreation.builder().userName(schemaConfig.getUserName()));
        FileAttributes fileAttributes = fileSystem.getFileAttributes(viewPath);
        f = DotFile.create(fileSystem, fileAttributes);
      } catch (FileNotFoundException e) {
        return Optional.empty();
      } catch (IOException e) {
        logger.warn(
            "Failure while trying to list view tables in workspace [{}]", tableSchemaPath, e);
      }

      if (f != null && f.getType() == DotFileType.VIEW) {
        try {
          return Optional.of(
              new ViewTable(
                  new NamespaceKey(tableSchemaPath),
                  f.getView(lpPersistance),
                  CatalogUser.from(f.getOwner()),
                  null));
        } catch (AccessControlException e) {
          if (!schemaConfig.getIgnoreAuthErrors()) {
            logger.debug(e.getMessage());
            throw UserException.permissionError(e)
                .message(
                    "Not authorized to read view [%s] in schema %s",
                    tableSchemaPath.get(tableSchemaPath.size() - 1),
                    tableSchemaPath.subList(0, tableSchemaPath.size() - 1))
                .build(logger);
          }
        } catch (IOException e) {
          logger.warn(
              "Failure while trying to load {}.view.meta file in workspace [{}]",
              tableSchemaPath.get(tableSchemaPath.size() - 1),
              tableSchemaPath.subList(0, tableSchemaPath.size() - 1),
              e);
        }
      }
    } catch (UnsupportedOperationException e) {
      logger.debug("The filesystem for this workspace does not support this operation.", e);
    }

    return Optional.empty();
  }

  /**
   * Helper method which resolves the table name to actual file/folder on the filesystem. If the
   * resolved path refers to an entity not under the base of the source then a permission error is
   * thrown.
   *
   * <p>Ex. For given source named "dfs" which has base path of "/base" tableSchemaPath [dfs, tmp,
   * a] -> "/base/tmp/a" [dfs, "/tmp/b"] -> "/base/tmp/b" [dfs, "value", tbl] -> "/base/value/tbl"
   *
   * @param tableSchemaPath
   * @return
   */
  @Override
  public List<String> resolveTableNameToValidPath(List<String> tableSchemaPath) {
    List<String> fullPath = new ArrayList<>();
    fullPath.addAll(PathUtils.toPathComponents(basePath));
    for (String pathComponent :
        tableSchemaPath.subList(1 /* need to skip the source name */, tableSchemaPath.size())) {
      fullPath.add(PathUtils.removeQuotes(pathComponent));
    }
    PathUtils.verifyNoDirectoryTraversal(
        fullPath,
        () ->
            UserException.permissionError()
                .message("Not allowed to perform directory traversal")
                .addContext("Path", fullPath.toString())
                .buildSilently());
    PathUtils.verifyNoAccessOutsideBase(basePath, PathUtils.toFSPath(fullPath));
    return fullPath;
  }

  /**
   * Resolve given table path relative to source resolve it to a valid path in filesystem. If the
   * resolved path refers to an entity not under the base of the source then a permission error is
   * thrown.
   *
   * @param tablePath
   * @return
   */
  public Path resolveTablePathToValidPath(String tablePath) {
    String relativePathClean = PathUtils.removeLeadingSlash(tablePath);
    Path combined = basePath.resolve(relativePathClean);
    PathUtils.verifyNoDirectoryTraversal(
        ImmutableList.of(tablePath),
        () ->
            UserException.permissionError()
                .message("Not allowed to perform directory traversal")
                .addContext("Path", tablePath.toString())
                .buildSilently());
    PathUtils.verifyNoAccessOutsideBase(basePath, combined);
    return combined;
  }

  FileDatasetHandle getDatasetWithOptions(
      NamespaceKey datasetPath,
      TableInstance instance,
      boolean ignoreAuthErrors,
      String user,
      int maxLeafColumns)
      throws Exception {
    final FormatPluginConfig fconfig = optionExtractor.createConfigForTable(instance);
    final DatasetRetrievalOptions options =
        DatasetRetrievalOptions.DEFAULT.toBuilder()
            .setIgnoreAuthzErrors(ignoreAuthErrors)
            .setMaxMetadataLeafColumns(maxLeafColumns)
            .build();

    return getDatasetWithFormat(
        datasetPath,
        new PreviousDatasetInfo(null, null, null, null, null, true),
        fconfig,
        options,
        user);
  }

  protected FileDatasetHandle getDatasetWithFormat(
      NamespaceKey datasetPath,
      PreviousDatasetInfo oldConfig,
      FormatPluginConfig formatPluginConfig,
      DatasetRetrievalOptions retrievalOptions,
      String user)
      throws Exception {
    try {
      Optional<FileSelection> selection = generateFileSelectionForPathComponents(datasetPath, user);

      if (!selection.isPresent()) {
        return null;
      }

      FileSelection fileSelection = selection.get();
      FileSystem fs = createFS(SupportsFsCreation.builder().userName(user));

      FileDatasetHandle datasetAccessor = null;

      if (formatPluginConfig != null) {
        FormatPlugin formatPlugin = formatCreator.getFormatPluginByConfig(formatPluginConfig);
        if (formatPlugin == null) {
          formatPlugin = formatCreator.newFormatPlugin(formatPluginConfig);
        }
        FileSelectionProcessor fileSelectionProcessor =
            formatPlugin.getFileSelectionProcessor(fs, fileSelection);

        FileSelection normalizedFileSelection =
            fileSelectionProcessor.normalizeForPlugin(fileSelection);
        final FileUpdateKey updateKey = fileSelectionProcessor.generateUpdateKey();
        if (fileSelection.isExpanded() && fileSelection.isEmpty()) {
          return null;
        }

        DatasetType type =
            fs.isDirectory(Path.of(normalizedFileSelection.getSelectionRoot()))
                ? DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER
                : DatasetType.PHYSICAL_DATASET_SOURCE_FILE;
        datasetAccessor =
            formatPlugin.getDatasetAccessor(
                type,
                oldConfig,
                fs,
                normalizedFileSelection,
                this,
                datasetPath,
                updateKey,
                retrievalOptions.maxMetadataLeafColumns(),
                retrievalOptions.getTimeTravelRequest());
      }

      if (datasetAccessor == null && retrievalOptions.autoPromote()) {
        boolean formatFound = false;
        for (final FormatMatcher matcher : matchers) {
          try {
            final FileSelectionProcessor fileSelectionProcessor =
                matcher.getFormatPlugin().getFileSelectionProcessor(fs, fileSelection);
            if (matcher.matches(fs, fileSelection, codecFactory)) {
              formatFound = true;
              final DatasetType type =
                  fs.isDirectory(Path.of(fileSelection.getSelectionRoot()))
                      ? DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER
                      : DatasetType.PHYSICAL_DATASET_SOURCE_FILE;

              final FileSelection normalizedFileSelection =
                  fileSelectionProcessor.normalizeForPlugin(fileSelection);
              final FileUpdateKey updateKey = fileSelectionProcessor.generateUpdateKey();

              datasetAccessor =
                  matcher
                      .getFormatPlugin()
                      .getDatasetAccessor(
                          type,
                          oldConfig,
                          fs,
                          normalizedFileSelection,
                          this,
                          datasetPath,
                          updateKey,
                          retrievalOptions.maxMetadataLeafColumns(),
                          retrievalOptions.getTimeTravelRequest());
              if (datasetAccessor != null) {
                break;
              }
            }
          } catch (IOException e) {
            logger.debug("File read failed.", e);
          }
        }

        if (!formatFound) {
          String errorMessage =
              String.format(
                  "The file format for '%s' could not be identified. In order for automatic format detection to succeed, "
                      + "files must include a file extension. Alternatively, manual promotion can be used to explicitly specify the format.",
                  datasetPath.getSchemaPath());
          throw UserException.unsupportedError().message(errorMessage).buildSilently();
        }
      }

      return datasetAccessor;

    } catch (AccessControlException e) {
      if (!retrievalOptions.ignoreAuthzErrors()) {
        logger.debug(e.getMessage());
        throw UserException.permissionError(e)
            .message("Not authorized to read table %s at path ", datasetPath)
            .build(logger);
      }
    } catch (IOException e) {
      logger.debug("Failed to create table {}", datasetPath, e);
      Throwables.throwIfInstanceOf(e, InterruptedIOException.class);
    }
    return null;
  }

  // Try to match against a layered format. If these is no match, returns null.
  public Optional<FileFormat> findLayeredFormatMatch(
      FileSystem fs,
      FileSelection
          /** unexpanded */
          fileSelection)
      throws IOException {
    Preconditions.checkNotNull(fileSelection, "fileSelection");
    Preconditions.checkArgument(
        fileSelection.isNotExpanded(), "Expected NOT_EXPANDED file selection");
    for (final FormatMatcher matcher : matchers) {
      if (!matcher.getFormatPlugin().isLayered()) {
        continue;
      }
      if (matcher.matches(fs, fileSelection, codecFactory)) {
        return Optional.of(PhysicalDatasetUtils.toFileFormat(matcher.getFormatPlugin()));
      }
    }
    return Optional.empty();
  }

  public Optional<FileFormat> findFileFormatMatch(FileSystem fs, FileAttributes attributes)
      throws IOException {
    for (final FormatMatcher matcher : matchers) {
      if (matcher.matches(fs, attributes, codecFactory)) {
        return Optional.of(PhysicalDatasetUtils.toFileFormat(matcher.getFormatPlugin()));
      }
    }
    return Optional.empty();
  }

  @Override
  public void start() throws IOException {
    List<Property> properties = getProperties();
    if (properties != null) {
      for (Property prop : properties) {
        fsConf.set(prop.name, prop.value);
      }
    }

    if (!Strings.isNullOrEmpty(getConnection())) {
      org.apache.hadoop.fs.FileSystem.setDefaultUri(fsConf, getConnection());
    }

    Map<String, String> map =
        ImmutableMap.of(
            "fs.classpath.impl", ClassPathFileSystem.class.getName(),
            "fs.dremio-local.impl", LocalSyncableFileSystem.class.getName());
    for (Entry<String, String> prop : map.entrySet()) {
      fsConf.set(prop.getKey(), prop.getValue());
    }

    this.optionExtractor = new FormatPluginOptionExtractor(context.getClasspathScan());
    this.matchers = Lists.newArrayList();
    this.layeredMatchers = Lists.newArrayList();
    this.formatCreator = new FormatCreator(context, context.getClasspathScan(), this);

    matchers.addAll(formatCreator.getFormatMatchers());
    layeredMatchers.addAll(formatCreator.getLayeredFormatMatchers());

    // NOTE: Add fallback format matcher if given in the configuration. Make sure fileMatchers is an
    // order-preserving list.
    this.systemUserFS = createFS(SupportsFsCreation.builder().userName(SYSTEM_USERNAME));
    dropFileMatchers = matchers.subList(0, matchers.size());
    this.fsHealthChecker =
        FSHealthChecker.getInstance(config.getPath(), getConnection(), getFsConf())
            .orElse((p, m) -> healthCheck(p, m));

    createIfNecessary();
  }

  protected List<Property> getProperties() {
    return config.getProperties();
  }

  /**
   * Create the supporting directory for this plugin if it doesn't yet exist.
   *
   * @throws IOException
   */
  private void createIfNecessary() throws IOException {
    if (!config.createIfMissing()) {
      return;
    }

    try {
      // no need to exists here as FileSystemWrapper does an exists check and this is a noop if
      // already existing.
      systemUserFS.mkdirs(config.getPath());
    } catch (IOException ex) {
      try {
        if (systemUserFS.exists(config.getPath())) {
          // race creation, ignore.
          return;
        }

      } catch (IOException existsFailure) {
        // we're doing the check above to detect a race condition. if we fail, ignore the failure
        // and just fall through to throwing the originally caught exception.
        ex.addSuppressed(existsFailure);
      }

      throw new IOException(
          String.format("Failure to create directory %s.", config.getPath().toString()), ex);
    }
  }

  public FormatPlugin getFormatPlugin(String name) {
    return formatCreator.getFormatPluginByName(name);
  }

  @Override
  public FormatPlugin getFormatPlugin(FormatPluginConfig formatConfig) {
    FormatPlugin plugin = formatCreator.getFormatPluginByConfig(formatConfig);
    if (plugin == null) {
      plugin = formatCreator.newFormatPlugin(formatConfig);
    }
    return plugin;
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    if (config.isImpersonationEnabled()) {
      if (datasetConfig.getReadDefinition() != null) { // allow accessing partial datasets
        FileSystem userFs;
        try {
          userFs = createFS(SupportsFsCreation.builder().userName(user));
        } catch (IOException ioe) {
          throw new RuntimeException("Failed to check access permission", ioe);
        }
        final List<TimedRunnable<Boolean>> permissionCheckTasks = Lists.newArrayList();

        permissionCheckTasks.addAll(getUpdateKeyPermissionTasks(datasetConfig, userFs));
        permissionCheckTasks.addAll(getSplitPermissionTasks(datasetConfig, userFs, user));

        try {
          Stopwatch stopwatch = Stopwatch.createStarted();
          final List<Boolean> accessPermissions =
              TimedRunnable.run(
                  "check access permission for " + key, logger, permissionCheckTasks, 16);
          stopwatch.stop();
          logger.debug(
              "Checking access permission for {} took {} ms",
              key,
              stopwatch.elapsed(TimeUnit.MILLISECONDS));
          for (Boolean permission : accessPermissions) {
            if (!permission) {
              return false;
            }
          }
        } catch (FileNotFoundException fnfe) {
          throw UserException.invalidMetadataError(fnfe)
              .addContext(fnfe.getMessage())
              .setAdditionalExceptionContext(
                  new InvalidMetadataErrorContext(ImmutableList.of(key.getPathComponents())))
              .buildSilently();
        } catch (IOException ioe) {
          throw new RuntimeException("Failed to check access permission", ioe);
        }
      }
    }
    return true;
  }

  // Check if all sub directories can be listed/read
  private Collection<FsPermissionTask> getUpdateKeyPermissionTasks(
      DatasetConfig datasetConfig, FileSystem userFs) {
    FileUpdateKey fileUpdateKey;
    try {
      fileUpdateKey =
          LegacyProtobufSerializer.parseFrom(
              FileUpdateKey.parser(),
              datasetConfig.getReadDefinition().getReadSignature().toByteArray());
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException("Cannot read file update key", e);
    }
    if (fileUpdateKey.getCachedEntitiesList().isEmpty()) {
      return Collections.emptyList();
    }
    final List<FsPermissionTask> fsPermissionTasks = Lists.newArrayList();
    final Set<AccessMode> access;
    final List<Path> batch = Lists.newArrayList();

    // DX-7850 : remove once solution for maprfs is found
    if (userFs.isMapRfs()) {
      access = EnumSet.of(AccessMode.READ);
    } else {
      access = EnumSet.of(AccessMode.READ, AccessMode.EXECUTE);
    }

    for (FileSystemCachedEntity cachedEntity : fileUpdateKey.getCachedEntitiesList()) {
      batch.add(Path.of(cachedEntity.getPath()));
      if (batch.size() == PERMISSION_CHECK_TASK_BATCH_SIZE) {
        // make a copy of batch
        fsPermissionTasks.add(new FsPermissionTask(userFs, Lists.newArrayList(batch), access));
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      fsPermissionTasks.add(new FsPermissionTask(userFs, batch, access));
    }
    return fsPermissionTasks;
  }

  // Check if all splits are accessible
  private Collection<FsPermissionTask> getSplitPermissionTasks(
      DatasetConfig datasetConfig, FileSystem userFs, String user) {
    final SplitsPointer splitsPointer =
        DatasetSplitsPointer.of(context.getNamespaceService(user), datasetConfig);
    final boolean isParquet =
        DatasetHelper.hasParquetDataFiles(datasetConfig.getPhysicalDataset().getFormatSettings());
    Boolean isIcebergMetaData =
        datasetConfig.getPhysicalDataset() != null
            ? datasetConfig.getPhysicalDataset().getIcebergMetadataEnabled()
            : Boolean.FALSE;
    logger.debug(
        "Checking iceberg enabled or not for this physical dataset with value: {} . ",
        isIcebergMetaData);
    final List<FsPermissionTask> fsPermissionTasks = Lists.newArrayList();
    final List<Path> batch = Lists.newArrayList();

    for (PartitionChunkMetadata partitionChunkMetadata : splitsPointer.getPartitionChunks()) {
      for (DatasetSplit split : partitionChunkMetadata.getDatasetSplits()) {
        final Path filePath;
        try {
          if (isParquet && !Boolean.TRUE.equals(isIcebergMetaData)) {
            filePath =
                Path.of(
                    LegacyProtobufSerializer.parseFrom(
                            ParquetDatasetSplitXAttr.parser(),
                            split.getSplitExtendedProperty().toByteArray())
                        .getPath());
          } else {
            filePath =
                Path.of(
                    LegacyProtobufSerializer.parseFrom(
                            EasyDatasetSplitXAttr.parser(),
                            split.getSplitExtendedProperty().toByteArray())
                        .getPath());
          }
        } catch (InvalidProtocolBufferException e) {
          throw new RuntimeException("Could not deserialize split info", e);
        }

        batch.add(filePath);
        if (batch.size() == PERMISSION_CHECK_TASK_BATCH_SIZE) {
          // make a copy of batch
          fsPermissionTasks.add(
              new FsPermissionTask(userFs, new ArrayList<>(batch), EnumSet.of(AccessMode.READ)));
          batch.clear();
        }
      }
    }

    if (!batch.isEmpty()) {
      fsPermissionTasks.add(new FsPermissionTask(userFs, batch, EnumSet.of(AccessMode.READ)));
    }

    return fsPermissionTasks;
  }

  @Nonnull
  @Override
  public Optional<DatasetMetadataVerifyResult> verifyMetadata(
      DatasetHandle datasetHandle, MetadataVerifyRequest metadataVerifyRequest) {
    if (datasetHandle instanceof MetadataVerifyHandle) {
      return datasetHandle.unwrap(MetadataVerifyHandle.class).verifyMetadata(metadataVerifyRequest);
    }
    return Optional.empty();
  }

  private class FsPermissionTask extends TimedRunnable<Boolean> {
    private final FileSystem userFs;
    private final List<Path> cachedEntityPaths;
    private final Set<AccessMode> permission;

    FsPermissionTask(FileSystem userFs, List<Path> cachedEntityPaths, Set<AccessMode> permission) {
      this.userFs = userFs;
      this.cachedEntityPaths = cachedEntityPaths;
      this.permission = permission;
    }

    @Override
    protected IOException convertToIOException(Exception e) {
      if (e instanceof IOException) {
        return (IOException) e;
      }
      return new IOException(e);
    }

    @Override
    protected Boolean runInner() throws Exception {
      for (Path cachedEntityPath : cachedEntityPaths) {
        try {
          userFs.access(cachedEntityPath, permission);
        } catch (AccessControlException ace) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Returns a shared {@link FileSystemWrapper} for the system user.
   *
   * @return
   */
  public FileSystem getSystemUserFS() {
    return systemUserFS;
  }

  @Override
  public boolean isMetadataValidityCheckRecentEnough(
      Long lastMetadataValidityCheckTime, Long currentTime, OptionManager optionManager) {
    return true;
  }

  public String getName() {
    return name;
  }

  protected boolean fileExists(String username, List<String> filePath) throws IOException {
    return createFS(SupportsFsCreation.builder().userName(username))
        .isFile(PathUtils.toFSPath(resolveTableNameToValidPath(filePath)));
  }

  protected boolean isAsyncEnabledForQuery(OperatorContext context) {
    return true;
  }

  @Override
  public MetadataValidity validateMetadata(
      BytesOutput signature,
      DatasetHandle datasetHandle,
      DatasetMetadata metadata,
      ValidateMetadataOption... options)
      throws DatasetNotFoundException {
    // Delegate the staleness check for metadata to the DataAccessors
    boolean metadataStale =
        ((FileDatasetHandle) datasetHandle)
            .metadataValid(signature, datasetHandle, metadata, systemUserFS);
    return metadataStale ? MetadataValidity.VALID : MetadataValidity.INVALID;
  }

  public PluginSabotContext getContext() {
    return context;
  }

  @Override
  public void close() {
    // Empty cache
    hadoopFS.invalidateAll();
    hadoopFS.cleanUp();
  }

  @Override
  public boolean createOrUpdateView(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      View view,
      ViewOptions viewOptions,
      NamespaceAttribute... attributes)
      throws IOException, CatalogUnsupportedOperationException {
    if (!Boolean.getBoolean(DremioConfig.LEGACY_STORE_VIEWS_ENABLED)) {
      throw UserException.parseError()
          .message("Unable to create or update view. Filesystem views are not supported.")
          .build(logger);
    } else if (!getMutability()
        .hasMutationCapability(MutationType.VIEW, schemaConfig.isSystemUser())) {
      throw UserException.parseError()
          .message(
              "Unable to create or update view. Schema [%s] is immutable for this user.",
              tableSchemaPath.getParent())
          .build(logger);
    }

    Path viewPath = getViewPath(tableSchemaPath.getPathComponents());
    FileSystem fs = createFS(SupportsFsCreation.builder().userName(schemaConfig.getUserName()));
    boolean replaced = fs.exists(viewPath);
    final Set<PosixFilePermission> viewPerms =
        MorePosixFilePermissions.fromOctalMode(
            schemaConfig.getOption(ExecConstants.NEW_VIEW_DEFAULT_PERMS_KEY).getStringVal());
    try (OutputStream stream = FileSystemUtils.create(fs, viewPath, viewPerms)) {
      lpPersistance.getMapper().writeValue(stream, view);
    }
    return replaced;
  }

  @Override
  public void dropView(
      NamespaceKey tableSchemaPath, ViewOptions viewOptions, SchemaConfig schemaConfig)
      throws IOException, CatalogEntityNotFoundException {
    if (!Boolean.getBoolean(DremioConfig.LEGACY_STORE_VIEWS_ENABLED)) {
      throw UserException.parseError()
          .message("Unable to drop view. Filesystem views are not supported.")
          .build(logger);
    } else if (!getMutability()
        .hasMutationCapability(MutationType.VIEW, schemaConfig.isSystemUser())) {
      throw UserException.parseError()
          .message("Unable to drop view. Schema [%s] is immutable for this user.", this.name)
          .build(logger);
    }

    createFS(SupportsFsCreation.builder().userName(schemaConfig.getUserName()))
        .delete(getViewPath(tableSchemaPath.getPathComponents()), false);
  }

  private Path getViewPath(List<String> tableSchemaPath) {
    List<String> fullPath = resolveTableNameToValidPath(tableSchemaPath);
    String parentPath = PathUtils.toFSPathString(fullPath.subList(0, fullPath.size() - 1));
    return DotFileType.VIEW.getPath(parentPath, tableSchemaPath.get(tableSchemaPath.size() - 1));
  }

  public FormatPluginOptionExtractor getOptionExtractor() {
    return optionExtractor;
  }

  @Override
  public List<Function> getFunctions(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    return optionExtractor.getFunctions(tableSchemaPath, this, schemaConfig);
  }

  private FormatMatcher findMatcher(FileSystem fs, FileSelection file) {
    FormatMatcher matcher = null;
    try {
      for (FormatMatcher m : dropFileMatchers) {

        if (m.matches(fs, file, codecFactory)) {
          return m;
        }
      }
    } catch (IOException e) {
      logger.debug("Failed to find format matcher for file: {}", file, e);
    }
    return matcher;
  }

  private boolean isHomogeneous(FileSystem fs, FileSelection fileSelection) throws IOException {
    FormatMatcher matcher = null;
    FileSelection noDir = fileSelection.minusDirectories();

    if (noDir == null || noDir.getFileAttributesList() == null) {
      return true;
    }

    for (FileAttributes attributes : noDir.getFileAttributesList()) {
      FileSelection subSelection = FileSelection.create(attributes);
      if (matcher == null) {
        matcher = findMatcher(fs, subSelection);
        if (matcher == null) {
          return false;
        }
      }

      if (!matcher.matches(fs, subSelection, codecFactory)) {
        return false;
      }
    }
    return true;
  }

  /**
   * We check if the table contains homogeneous file formats that Dremio can read. Once the checks
   * are performed we rename the file to start with an "_". After the rename we issue a recursive
   * delete of the directory.
   */
  @Override
  public void dropTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions) {
    if (!getMutability().hasMutationCapability(MutationType.TABLE, schemaConfig.isSystemUser())) {
      throw UserException.parseError()
          .message("Unable to drop table. Schema [%s] is immutable for this user.", this.name)
          .build(logger);
    }

    FileSystem fs;
    try {
      fs = createFS(SupportsFsCreation.builder().userName(schemaConfig.getUserName()));
    } catch (IOException e) {
      throw UserException.ioExceptionError(e)
          .message("Failed to access filesystem: " + e.getMessage())
          .build(logger);
    }

    List<String> fullPath = resolveTableNameToValidPath(tableSchemaPath.getPathComponents());
    FileSelection fileSelection;
    try {
      // ICEBERG or DELTALAKE
      boolean isLayeredTable = tableMutationOptions != null && tableMutationOptions.isLayered();
      // The fileSelection is used in the isHomogenous() check below to prevent accidental deletion
      // of folders with files of mixed format
      fileSelection =
          isLayeredTable
              ? FileSelection.createNotExpanded(fs, fullPath)
              : FileSelection.create(
                  tableSchemaPath.getName(),
                  fs,
                  fullPath,
                  FileDatasetHandle.getMaxFilesLimit(context.getOptionManager()));
    } catch (IOException e) {
      throw new RuntimeException(
          String.format(
              "Unable to drop table [%s]. %s",
              SqlUtils.quotedCompound(tableSchemaPath.getPathComponents()), e.getMessage()));
    }

    if (fileSelection == null) {
      throw UserException.validationError()
          .message(
              String.format(
                  "Table [%s] not found",
                  SqlUtils.quotedCompound(tableSchemaPath.getPathComponents())))
          .build(logger);
    }

    try {
      if (tableMutationOptions != null
          && !tableMutationOptions.isLayered()
          && !isHomogeneous(fs, fileSelection)) {
        if (!context.getNamespaceService(schemaConfig.getUserName()).exists(tableSchemaPath)) {
          throw UserException.validationError()
              .message(
                  String.format(
                      "Unable to drop table. The specified table [%s] could not be found",
                      SqlUtils.quotedCompound(tableSchemaPath.getPathComponents())))
              .build(logger);
        }
        throw UserException.validationError()
            .message(
                "Table contains different file formats. \n"
                    + "Drop Table is only supported for directories that contain homogeneous file formats consumable by Dremio")
            .build(logger);
      }

      final Path fsPath = PathUtils.toFSPath(fullPath);

      if (!fs.delete(fsPath, true)) {
        throw UserException.ioExceptionError()
            .message(
                "Failed to drop table: %s",
                PathUtils.constructFullPath(tableSchemaPath.getPathComponents()))
            .build(logger);
      }
      if (tableMutationOptions != null && tableMutationOptions.shouldDeleteCatalogEntry()) {
        deleteIcebergTableRootPointer(schemaConfig.getUserName(), fsPath);
      }
    } catch (AccessControlException e) {
      throw UserException.permissionError(e).message("Unauthorized to drop table").build(logger);
    } catch (IOException e) {
      throw UserException.dataWriteError(e)
          .message("Failed to drop table: " + e.getMessage())
          .build(logger);
    }
  }

  @Override
  public void alterTable(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      AlterTableOption alterTableOption,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions) {
    IcebergModel icebergModel = getIcebergModel();
    icebergModel.alterTable(
        icebergModel.getTableIdentifier(
            validateAndGetPath(tableSchemaPath, schemaConfig.getUserName()).toString()),
        alterTableOption);
  }

  @Override
  public void truncateTable(
      NamespaceKey key, SchemaConfig schemaConfig, TableMutationOptions tableMutationOptions) {
    IcebergModel icebergModel = getIcebergModel();
    icebergModel.truncateTable(
        icebergModel.getTableIdentifier(
            validateAndGetPath(key, schemaConfig.getUserName()).toString()));
  }

  @Override
  public void rollbackTable(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      RollbackOption rollbackOption,
      TableMutationOptions tableMutationOptions) {
    IcebergModel icebergModel = getIcebergModel();
    icebergModel.rollbackTable(
        icebergModel.getTableIdentifier(
            validateAndGetPath(tableSchemaPath, schemaConfig.getUserName()).toString()),
        rollbackOption);
  }

  public void deleteIcebergTableRootPointer(String userName, Path icebergTablePath) {

    try {
      FileSystem fs = createFS(SupportsFsCreation.builder().userName(userName));
      FileIO fileIO = createIcebergFileIO(fs, null, null, null, null);
      IcebergModel icebergModel = getIcebergModel(fileIO, null);
      icebergModel.deleteTableRootPointer(
          icebergModel.getTableIdentifier(String.valueOf(icebergTablePath)));

    } catch (IOException e) {
      throw UserException.ioExceptionError(e)
          .message("Failed to access filesystem: " + e.getMessage())
          .build(logger);
    } catch (Exception e) {
      String message =
          String.format(
              "There was an error while cleaning up respective root pointer entry residing at %s.",
              basePath);
      logger.error(message, e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void addColumns(
      NamespaceKey key,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      List<Field> columnsToAdd,
      TableMutationOptions tableMutationOptions) {
    AddColumn columnOperations =
        new AddColumn(
            key,
            context,
            datasetConfig,
            schemaConfig,
            getIcebergModel(),
            validateAndGetPath(key, schemaConfig.getUserName()),
            this);
    columnOperations.performOperation(columnsToAdd);
  }

  @Override
  public void dropColumn(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      String columnToDrop,
      TableMutationOptions tableMutationOptions) {
    DropColumn columnOperations =
        new DropColumn(
            table,
            context,
            datasetConfig,
            schemaConfig,
            getIcebergModel(),
            validateAndGetPath(table, schemaConfig.getUserName()),
            this);
    columnOperations.performOperation(columnToDrop);
  }

  @Override
  public void changeColumn(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      String columnToChange,
      Field fieldFromSql,
      TableMutationOptions tableMutationOptions) {
    ChangeColumn columnOperations =
        new ChangeColumn(
            table,
            context,
            datasetConfig,
            schemaConfig,
            getIcebergModel(),
            validateAndGetPath(table, schemaConfig.getUserName()),
            this);
    columnOperations.performOperation(columnToChange, fieldFromSql);
  }

  @Override
  public void addPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      List<Field> columns,
      ResolvedVersionContext versionContext) {
    AddPrimaryKey op =
        new AddPrimaryKey(
            table,
            context,
            datasetConfig,
            schemaConfig,
            getIcebergModel(),
            validateAndGetPath(table, schemaConfig.getUserName()),
            this);
    op.performOperation(columns);
  }

  @Override
  public void dropPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      ResolvedVersionContext versionContext) {
    DropPrimaryKey op =
        new DropPrimaryKey(
            table,
            context,
            datasetConfig,
            schemaConfig,
            getIcebergModel(),
            validateAndGetPath(table, schemaConfig.getUserName()),
            this);
    op.performOperation();
  }

  @Override
  public List<String> getPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      ResolvedVersionContext versionContext,
      boolean saveInKvStore) {
    if (!IcebergUtils.isPrimaryKeySupported(datasetConfig)) {
      return null;
    }
    if (datasetConfig.getPhysicalDataset().getPrimaryKey() != null) {
      return datasetConfig.getPhysicalDataset().getPrimaryKey().getColumnList();
    }

    // Cache the PK in the KV store.
    try {
      if (saveInKvStore) { // If we are not going to save in KV store, no point of validating this
        // Verify if the user has permission to write to the KV store.
        MetadataRequestOptions options = MetadataRequestOptions.of(schemaConfig);

        Catalog catalog = context.getCatalogService().getCatalog(options);
        catalog.validatePrivilege(table, SqlGrant.Privilege.ALTER);
      }
    } catch (UserException | java.security.AccessControlException ex) {
      if (ex instanceof java.security.AccessControlException
          || ((UserException) ex).getErrorType()
              == UserBitShared.DremioPBError.ErrorType.PERMISSION) {
        return null; // The user does not have permission.
      }
      throw ex;
    }

    final String userName = schemaConfig.getUserName();
    final IcebergModel icebergModel;
    final Path path;
    if (DatasetHelper.isInternalIcebergTable(datasetConfig)) {
      final FileSystemPlugin<?> metaStoragePlugin =
          context.getCatalogService().getSource(METADATA_STORAGE_PLUGIN_NAME);
      icebergModel = metaStoragePlugin.getIcebergModel();
      String metadataTableName =
          datasetConfig.getPhysicalDataset().getIcebergMetadata().getTableUuid();
      path = metaStoragePlugin.resolveTablePathToValidPath(metadataTableName);
    } else if (DatasetHelper.isIcebergDataset(datasetConfig)) {
      // Native iceberg table
      icebergModel = getIcebergModel();
      path = getPath(table, userName);
      if (path == null) {
        // Will be null in case of an incremental refresh as we don't store tables for them.
        return null;
      }
    } else {
      return null;
    }
    List<String> primaryKey =
        IcebergUtils.getPrimaryKey(
            icebergModel, path.toString(), datasetConfig.getPhysicalDataset().getIcebergMetadata());

    // This can happen if the table already had PK in the metadata, and we just promoted this table.
    // The key will not be in the KV store for that.
    // Even if PK is empty, we need to save to KV, so next time we know PK is unset and don't check
    // from metadata again.
    if (saveInKvStore) {
      DatasetNamespaceService userNamespaceService = context.getNamespaceService(userName);
      PrimaryKeyOperations.updatePrimaryKeyInNamespaceService(
          table, datasetConfig, userNamespaceService, primaryKey);
    }

    return primaryKey;
  }

  @Override
  public boolean toggleSchemaLearning(
      NamespaceKey table, SchemaConfig schemaConfig, boolean enableSchemaLearning) {

    if (!context.getOptionManager().getOption(ExecConstants.ENABLE_INTERNAL_SCHEMA)) {
      throw UserException.unsupportedError()
          .message(
              "Modifications to internal schema's are not allowed. Contact dremio support to enable option user managed schema's ")
          .buildSilently();
    }

    DatasetConfig datasetConfig;
    try {
      datasetConfig = context.getNamespaceService(schemaConfig.getUserName()).getDataset(table);
    } catch (NamespaceException e) {
      throw UserException.dataReadError(e)
          .message(String.format("Dataset %s not found", table.toString()))
          .buildSilently();
    }
    boolean isParquetTableWithUnlimitedSplits = DatasetHelper.isInternalIcebergTable(datasetConfig);
    boolean isJSONTable = DatasetHelper.isJsonDataset(datasetConfig);
    if (!isParquetTableWithUnlimitedSplits && !isJSONTable) {
      return false;
    }
    UserDefinedSchemaSettings internalSchemaSettings =
        datasetConfig.getPhysicalDataset().getInternalSchemaSettings();
    if (internalSchemaSettings == null) {
      internalSchemaSettings = new UserDefinedSchemaSettings();
    }
    datasetConfig
        .getPhysicalDataset()
        .setInternalSchemaSettings(
            internalSchemaSettings.setSchemaLearningEnabled(enableSchemaLearning));
    try {
      context
          .getNamespaceService(schemaConfig.getUserName())
          .addOrUpdateDataset(table, datasetConfig);
    } catch (NamespaceException e) {
      throw new RuntimeException(e);
    }
    return true;
  }

  @Override
  public void alterSortOrder(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      SchemaConfig schemaConfig,
      List<String> sortOrderColumns,
      TableMutationOptions tableMutationOptions) {
    if (!context.getOptionManager().getOption(ExecConstants.ENABLE_INTERNAL_SCHEMA)) {
      throw UserException.unsupportedError()
          .message(
              "Modifications to internal schema's are not allowed. Contact dremio support to enable option user managed schema's")
          .buildSilently();
    }

    if (DatasetHelper.isInternalIcebergTable(datasetConfig)) {
      throw new RuntimeException(
          "Sort Order alterations are not supported on Internal Iceberg Tables");
    }

    IcebergModel icebergModel = getIcebergModel();
    icebergModel.replaceSortOrder(
        icebergModel.getTableIdentifier(
            validateAndGetPath(table, schemaConfig.getUserName()).toString()),
        sortOrderColumns);
  }

  @Override
  public void updateTableProperties(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      SchemaConfig schemaConfig,
      Map<String, String> tableProperties,
      TableMutationOptions tableMutationOptions,
      boolean isRemove) {
    if (!context.getOptionManager().getOption(ExecConstants.ENABLE_INTERNAL_SCHEMA)) {
      throw UserException.unsupportedError()
          .message(
              "Modifications to internal schema's are not allowed. Contact dremio support to enable option user managed schema's")
          .buildSilently();
    }

    if (DatasetHelper.isInternalIcebergTable(datasetConfig)) {
      throw new RuntimeException(
          "Update Table Properties are not supported on Internal Iceberg Tables");
    }

    IcebergModel icebergModel = getIcebergModel();
    if (isRemove) {
      List<String> propertyNameList = new ArrayList<>(tableProperties.keySet());
      icebergModel.removeTableProperties(
          icebergModel.getTableIdentifier(
              validateAndGetPath(table, schemaConfig.getUserName()).toString()),
          propertyNameList);
    } else {
      icebergModel.updateTableProperties(
          icebergModel.getTableIdentifier(
              validateAndGetPath(table, schemaConfig.getUserName()).toString()),
          tableProperties);
    }
  }

  protected void validate(NamespaceKey table, String userName) {
    if (!getMutability()
        .hasMutationCapability(MutationType.TABLE, userName.equals(SystemUser.SYSTEM_USERNAME))) {
      throw UserException.parseError()
          .message("Unable to modify table. Schema [%s] is immutable for this user.", this.name)
          .buildSilently();
    }
  }

  protected Path getPath(NamespaceKey table, String userName) {
    FileSystem fs;
    try {
      fs = createFS(SupportsFsCreation.builder().userName(userName));
    } catch (IOException e) {
      throw UserException.ioExceptionError(e)
          .message("Failed to access filesystem: " + e.getMessage())
          .buildSilently();
    }

    final String tableName = getTableName(table);

    Path path = resolveTablePathToValidPath(tableName);

    try {
      if (!fs.exists(path)) {
        throw UserException.validationError()
            .message("Unable to find table, path [%s] doesn't exist.", path)
            .buildSilently();
      }
    } catch (IOException e) {
      throw UserException.ioExceptionError(e)
          .message("Failed to check if directory [%s] exists: %s", path, e.getMessage())
          .buildSilently();
    }
    return path;
  }

  protected Path validateAndGetPath(NamespaceKey table, String userName) {
    validate(table, userName);
    return getPath(table, userName);
  }

  /** Fetches a single item from the filesystem plugin */
  public SchemaEntity get(List<String> path, String userName) {
    try {
      final FileAttributes fileAttributes =
          createFS(SupportsFsCreation.builder().userName(userName))
              .getFileAttributes(PathUtils.toFSPath(resolveTableNameToValidPath(path)));

      final Set<List<String>> tableNames = Sets.newHashSet();
      final NamespaceService ns = context.getNamespaceService(userName);
      final NamespaceKey folderNSKey = new NamespaceKey(path);

      if (ns.exists(folderNSKey)) {
        for (NameSpaceContainer entity : ns.list(folderNSKey, null, Integer.MAX_VALUE)) {
          if (entity.getType() == Type.DATASET) {
            tableNames.add(resolveTableNameToValidPath(entity.getDataset().getFullPathList()));
          }
        }
      }

      List<String> p = PathUtils.toPathComponents(fileAttributes.getPath());
      return getSchemaEntity(fileAttributes, tableNames, p);
    } catch (IOException | NamespaceException e) {
      throw new RuntimeException(e);
    }
  }

  public List<SchemaEntity> list(List<String> folderPath, String userName) {
    try {
      final List<FileAttributes> files =
          Lists.newArrayList(
              createFS(SupportsFsCreation.builder().userName(userName))
                  .list(PathUtils.toFSPath(resolveTableNameToValidPath(folderPath))));
      final Set<List<String>> tableNames = Sets.newHashSet();
      final NamespaceService ns = context.getNamespaceService(userName);
      final NamespaceKey folderNSKey = new NamespaceKey(folderPath);
      if (ns.exists(folderNSKey, Type.DATASET)) {
        // if the folder is a dataset, then there is nothing to list
        return ImmutableList.of();
      }
      if (ns.exists(folderNSKey)) {
        for (NameSpaceContainer entity : ns.list(folderNSKey, null, Integer.MAX_VALUE)) {
          if (entity.getType() == Type.DATASET) {
            tableNames.add(resolveTableNameToValidPath(entity.getDataset().getFullPathList()));
          }
        }
      }

      Iterable<SchemaEntity> itr =
          Iterables.transform(
              files,
              new com.google.common.base.Function<FileAttributes, SchemaEntity>() {
                @Nullable
                @Override
                public SchemaEntity apply(@Nullable FileAttributes input) {
                  List<String> p = PathUtils.toPathComponents(input.getPath());
                  return getSchemaEntity(input, tableNames, p);
                }
              });
      return ImmutableList.<SchemaEntity>builder().addAll(itr).build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (NamespaceException e) {
      throw new RuntimeException(e);
    }
  }

  private SchemaEntity getSchemaEntity(
      FileAttributes fileAttributes, Set<List<String>> tableNames, List<String> p) {
    SchemaEntityType entityType;

    if (fileAttributes.isDirectory()) {
      if (tableNames.contains(p)) {
        entityType = SchemaEntityType.FOLDER_TABLE;
      } else {
        entityType = SchemaEntityType.FOLDER;
      }
    } else {
      if (tableNames.contains(p)) {
        entityType = SchemaEntityType.FILE_TABLE;
      } else {
        entityType = SchemaEntityType.FILE;
      }
    }

    return new SchemaEntity(
        PathUtils.getQuotedFileName(fileAttributes.getPath()),
        entityType,
        fileAttributes.owner().getName());
  }

  public SchemaMutability getMutability() {
    return config.getSchemaMutability();
  }

  public FormatPluginConfig createConfigForTable(
      String tableName, Map<String, Object> storageOptions) {
    return optionExtractor.createConfigForTable(tableName, storageOptions);
  }

  public static String getTableName(final NamespaceKey key) {
    final String tableName;
    if (key.size() == 2) {
      tableName = key.getLeaf();
    } else {
      List<String> subString = key.getPathComponents().subList(1, key.size());
      tableName = Joiner.on('/').join(subString);
    }
    return tableName;
  }

  @Override
  public void createEmptyTable(
      NamespaceKey tableSchemaPath,
      final SchemaConfig config,
      BatchSchema batchSchema,
      final WriterOptions writerOptions) {
    if (!getMutability().hasMutationCapability(MutationType.TABLE, config.isSystemUser())) {
      throw UserException.parseError()
          .message(
              "Unable to create table. Schema [%s] is immutable for this user.",
              tableSchemaPath.getParent())
          .buildSilently();
    }

    final String tableName = getTableName(tableSchemaPath);
    // check that there is no directory at the described path.
    Path path = resolveTablePathToValidPath(tableName);

    try {
      if (systemUserFS.exists(path)) {
        throw UserException.validationError()
            .message("Folder already exists at path: %s.", tableSchemaPath)
            .buildSilently();
      }
    } catch (IOException e) {
      throw UserException.validationError(e)
          .message("Failure to check if table already exists at path %s.", tableSchemaPath)
          .buildSilently();
    }

    IcebergModel icebergModel = getIcebergModel();
    PartitionSpec partitionSpec =
        Optional.ofNullable(
                writerOptions
                    .getTableFormatOptions()
                    .getIcebergSpecificOptions()
                    .getIcebergTableProps())
            .map(props -> props.getDeserializedPartitionSpec())
            .orElse(null);
    Map<String, String> tableProperties =
        Optional.of(
                writerOptions
                    .getTableFormatOptions()
                    .getIcebergSpecificOptions()
                    .getIcebergTableProps())
            .map(props -> props.getTableProperties())
            .orElse(Collections.emptyMap());
    IcebergOpCommitter icebergOpCommitter =
        icebergModel.getCreateTableCommitter(
            tableName,
            icebergModel.getTableIdentifier(path.toString()),
            batchSchema,
            writerOptions.getPartitionColumns(),
            null,
            partitionSpec,
            writerOptions.getDeserializedSortOrder(),
            tableProperties,
            path.toString());
    icebergOpCommitter.commit();
  }

  @Override
  public CreateTableEntry createNewTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig config,
      IcebergTableProps icebergTableProps,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions,
      CreateTableOptions createTableOptions) {
    if (!getMutability().hasMutationCapability(MutationType.TABLE, config.isSystemUser())) {
      throw UserException.parseError()
          .message(
              "Unable to create table. Schema [%s] is immutable for this user.",
              tableSchemaPath.getParent())
          .build(logger);
    }

    final String tableName = getTableName(tableSchemaPath);

    final FormatPlugin formatPlugin;
    if (storageOptions == null || storageOptions.isEmpty() || !storageOptions.containsKey("type")) {
      final String storage = config.getOptions().getOption(ExecConstants.OUTPUT_FORMAT_VALIDATOR);
      formatPlugin = getFormatPlugin(storage);
      if (formatPlugin == null) {
        throw new UnsupportedOperationException(
            String.format("Unsupported format '%s' in '%s'", storage, tableSchemaPath));
      }
    } else {
      final FormatPluginConfig formatConfig = createConfigForTable(tableName, storageOptions);
      formatPlugin = getFormatPlugin(formatConfig);
    }

    final String userName =
        this.config.isImpersonationEnabled() ? config.getUserName() : SystemUser.SYSTEM_USERNAME;

    // check that there is no directory at the described path.
    Path path = resolveTablePathToValidPath(tableName);
    try {
      if (icebergTableProps == null
          || icebergTableProps.getIcebergOpType() == IcebergCommandType.CREATE) {
        // Skip the file existence check for the following conditions:
        //
        // 1)
        // Do not check for existence if explicitly requested.
        //
        // 2)
        // Do not check for existence of jobsResults table for Pdfs. The path includes a Unique UUID
        // for storing jobResults, it is not necessary to check for existence for Pdfs. Pdfs's
        // working set comprises of all executors(engines) and it verifies existence on all engines,
        // irrespective of whether an engine is the engine on which query is being run or for even
        // queries that are run only on the coordinator. The creates issues where the elastics
        // engines that are not in use are being stopped/started. To avoid such cases, the check for
        // existence of JobResults directory is not done.
        boolean skipExistenceCheck =
            createTableOptions.skipFileExistenceCheck()
                || (createTableOptions.isJobsResultsTable() && systemUserFS.isPdfs());
        if (!skipExistenceCheck) {
          if (systemUserFS.exists(path)) {
            throw UserException.validationError()
                .message("Folder already exists at path: %s.", tableSchemaPath)
                .build(logger);
          }
        }
      } else if (icebergTableProps.getIcebergOpType() == IcebergCommandType.INSERT) {
        if (!systemUserFS.exists(path)) {
          throw UserException.validationError()
              .message("Table folder does not exists at path: %s.", tableSchemaPath)
              .build(logger);
        }
      }
    } catch (IOException e) {
      throw UserException.validationError(e)
          .message("Failure to check if table already exists at path %s.", tableSchemaPath)
          .build(logger);
    }

    if (icebergTableProps != null) {
      icebergTableProps = new IcebergTableProps(icebergTableProps);
      icebergTableProps.setTableLocation(path.toString());
      icebergTableProps.setTableName(tableName);
      Preconditions.checkState(
          icebergTableProps.getUuid() != null && !icebergTableProps.getUuid().isEmpty(),
          "Unexpected state. UUID must be set");
      path = path.resolve(icebergTableProps.getUuid());
      return new CreateParquetTableEntry(
          userName, null, this, path.toString(), icebergTableProps, writerOptions, tableSchemaPath);
    }

    return new EasyFileSystemCreateTableEntry(
        userName,
        this,
        formatPlugin,
        path.toString(),
        icebergTableProps,
        writerOptions,
        tableSchemaPath);
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(
      EntityPath datasetPath, GetDatasetOption... options) throws ConnectorException {
    return internalGetDatasetHandle(datasetPath, options);
  }

  private Optional<DatasetHandle> internalGetDatasetHandle(
      EntityPath datasetPath, GetDatasetOption... options) throws ConnectorException {
    BatchSchema currentSchema = CurrentSchemaOption.getSchema(options);
    FileConfig fileConfig = FileConfigOption.getFileConfig(options);
    List<String> sortColumns = SortColumnsOption.getSortColumns(options);
    List<Field> droppedColumns = CurrentSchemaOption.getDroppedColumns(options);
    List<Field> updatedColumns = CurrentSchemaOption.getUpdatedColumns(options);
    boolean isSchemaLearningEnabled = CurrentSchemaOption.isSchemaLearningEnabled(options);

    FormatPluginConfig formatPluginConfig = null;
    if (fileConfig != null) {
      formatPluginConfig =
          PhysicalDatasetUtils.toFormatPlugin(fileConfig, Collections.<String>emptyList());
    }

    InternalMetadataTableOption internalMetadataTableOption =
        InternalMetadataTableOption.getInternalMetadataTableOption(options);
    if (internalMetadataTableOption != null) {
      TimeTravelOption.TimeTravelRequest timeTravelRequest =
          Optional.ofNullable(TimeTravelOption.getTimeTravelOption(options))
              .map(TimeTravelOption::getTimeTravelRequest)
              .orElse(null);
      return getDatasetHandleForInternalMetadataTable(
          datasetPath, formatPluginConfig, timeTravelRequest, internalMetadataTableOption);
    }

    Optional<DatasetHandle> handle = Optional.empty();

    try {
      handle =
          getDatasetHandleForNewRefresh(
              MetadataObjectsUtils.toNamespaceKey(datasetPath),
              fileConfig,
              DatasetRetrievalOptions.of(options));
    } catch (AccessControlException e) {
      if (!DatasetRetrievalOptions.of(options).ignoreAuthzErrors()) {
        logger.debug(e.getMessage());
        throw UserException.permissionError(e)
            .message("Not authorized to read table %s at path ", datasetPath)
            .build(logger);
      }
    } catch (IOException e) {
      logger.debug("Failed to create table {}", datasetPath, e);
    }

    if (handle.isPresent()) {
      // handle is UnlimitedSplitsDatasetHandle, dataset is parquet
      if (DatasetRetrievalOptions.of(options).autoPromote()) {
        // autoPromote will allow this handle to work, regardless whether dataset is/is-not promoted
        return handle;
      } else if (fileConfig != null) {
        // dataset has already been promoted
        return handle;
      } else {
        // dataset not promoted, handle cannot be used without incorrectly triggering auto-promote
        return Optional.empty();
      }
    }

    final PreviousDatasetInfo pdi =
        new PreviousDatasetInfo(
            fileConfig,
            currentSchema,
            sortColumns,
            droppedColumns,
            updatedColumns,
            isSchemaLearningEnabled);
    try {
      return Optional.ofNullable(
          getDatasetWithFormat(
              MetadataObjectsUtils.toNamespaceKey(datasetPath),
              pdi,
              formatPluginConfig,
              DatasetRetrievalOptions.of(options),
              SystemUser.SYSTEM_USERNAME));
    } catch (Exception e) {
      Throwables.propagateIfPossible(e, ConnectorException.class);
      throw new ConnectorException(e);
    }
  }

  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle, PartitionChunkListing chunkListing, GetMetadataOption... options)
      throws ConnectorException {
    return datasetHandle.unwrap(FileDatasetHandle.class).getDatasetMetadata(options);
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options) throws ConnectorException {
    return datasetHandle.unwrap(FileDatasetHandle.class).listPartitionChunks(options);
  }

  @Override
  public BytesOutput provideSignature(DatasetHandle datasetHandle, DatasetMetadata metadata)
      throws ConnectorException {
    return datasetHandle.unwrap(FileDatasetHandle.class).provideSignature(metadata);
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    final List<String> folderPath = containerPath.getComponents();
    try {
      return systemUserFS.isDirectory(PathUtils.toFSPath(resolveTableNameToValidPath(folderPath)));
    } catch (IOException e) {
      logger.debug("Failure reading path.", e);
      return false;
    }
  }

  public IcebergModel getIcebergModel() {
    return getIcebergModel(createIcebergFileIO(getSystemUserFS(), null, null, null, null), null);
  }

  @Override
  public IcebergModel getIcebergModel(
      IcebergTableProps tableProps,
      String userName,
      OperatorContext context,
      FileIO fileIO,
      String userId) {
    return getIcebergModel(fileIO, context);
  }

  public String getRootLocation() {
    return basePath.toString();
  }

  private IcebergModel getIcebergModel(FileIO fileIO, OperatorContext operatorContext) {
    return IcebergModelCreator.createIcebergModel(
        getFsConfCopy(), context, fileIO, operatorContext, this);
  }

  @Override
  public boolean isIcebergMetadataValid(DatasetConfig config, NamespaceKey key) {
    if (config.getPhysicalDataset().getIcebergMetadata() == null
        || config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation() == null
        || config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation().isEmpty()) {
      return false;
    }
    Path existingRootPointer =
        Path.of(config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation());
    final String rootFolder = existingRootPointer.getParent().getParent().toString();
    IcebergModel icebergModel = getIcebergModel();
    IcebergTableLoader icebergTableLoader =
        icebergModel.getIcebergTableLoader(icebergModel.getTableIdentifier(rootFolder));
    final String latestRootPointer =
        Path.getContainerSpecificRelativePath(Path.of(icebergTableLoader.getRootPointer()));

    if (!latestRootPointer.equals(existingRootPointer.toString())) {
      logger.debug(
          "Iceberg Dataset {} metadata is not valid. Existing root pointer in catalog: {}. Latest Iceberg table root pointer: {}.",
          key,
          existingRootPointer,
          latestRootPointer);
      return false;
    }
    return true;
  }

  @Override
  public boolean isSupportUserDefinedSchema(DatasetConfig dataset) {
    return DatasetHelper.isInternalIcebergTable(dataset) || DatasetHelper.isJsonDataset(dataset);
  }

  @Override
  public TableOperations createIcebergTableOperations(
      FileIO fileIO,
      IcebergTableIdentifier tableIdentifier,
      @Nullable String queryUserName,
      @Nullable String queryUserId) {
    return new IcebergHadoopTableOperations(
        new org.apache.hadoop.fs.Path(
            ((IcebergHadoopTableIdentifier) tableIdentifier).getTableFolder()),
        getFsConfCopy(),
        fileIO);
  }

  @Override
  public FileIO createIcebergFileIO(
      FileSystem fs,
      OperatorContext context,
      List<String> dataset,
      String datasourcePluginUID,
      Long fileLength) {
    return new DremioFileIO(
        fs,
        context,
        dataset,
        datasourcePluginUID,
        fileLength,
        new HadoopFileSystemConfigurationAdapter(fsConf));
  }

  @Override
  public FooterReadTableFunction getFooterReaderTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig) {
    return new FooterReadTableFunction(fec, context, props, functionConfig);
  }

  public Optional<DatasetHandle> getDatasetHandleForNewRefresh(
      NamespaceKey datasetPath, FileConfig fileConfig, DatasetRetrievalOptions retrievalOptions)
      throws IOException {
    if (!metadataSourceAvailable(context.getCatalogService())) {
      return Optional.empty();
    }

    Optional<FileSelection> selection =
        generateFileSelectionForPathComponents(datasetPath, SystemUser.SYSTEM_USERNAME);
    if (!selection.isPresent()) {
      return Optional.empty();
    }

    boolean parquetDataset;
    if ((fileConfig != null) && (fileConfig.getType() != FileType.UNKNOWN)) {
      parquetDataset = fileConfig.getType() == FileType.PARQUET;
    } else {
      parquetDataset =
          isParquetTable(
              MetadataObjectsUtils.toEntityPath(datasetPath).getComponents(),
              SystemUser.SYSTEM_USERNAME);
    }
    return parquetDataset
        ? Optional.of(
            new UnlimitedSplitsFileDatasetHandle(
                DatasetType.PHYSICAL_DATASET_SOURCE_FOLDER, datasetPath))
        : Optional.empty();
  }

  public Optional<FileSelection> generateFileSelectionForPathComponents(
      NamespaceKey datasetPath, String user) throws IOException {
    if (datasetPath.size() <= 1) {
      return Optional.empty(); // not a valid table schema path
    }

    final List<String> fullPath = resolveTableNameToValidPath(datasetPath.getPathComponents());
    List<String> parentSchemaPath = new ArrayList<>(fullPath.subList(0, fullPath.size() - 1));
    FileSystem fs = createFS(SupportsFsCreation.builder().userName(user));
    FileSelection fileSelection = FileSelection.createNotExpanded(fs, fullPath);
    String tableName = datasetPath.getName();

    if (fileSelection == null) {
      fileSelection =
          FileSelection.createWithFullSchemaNotExpanded(
              fs, PathUtils.toFSPathString(parentSchemaPath), tableName);
      if (fileSelection == null) {
        return Optional.empty(); // no table found
      } else {
        // table name is a full schema path (tableau use case), parse it and append it to
        // schemapath.
        final List<String> tableNamePathComponents = PathUtils.parseFullPath(tableName);
        tableName = tableNamePathComponents.remove(tableNamePathComponents.size() - 1);
      }
    }
    return Optional.of(fileSelection);
  }

  @Override
  public String getDefaultCtasFormat() {
    return config.getDefaultCtasFormat();
  }

  public boolean isPartitionInferenceEnabled() {
    return config.isPartitionInferenceEnabled();
  }

  @Override
  public DirListingRecordReader createDirListRecordReader(
      OperatorContext context,
      FileSystem fs,
      DirListInputSplitProto.DirListInputSplit dirListInputSplit,
      boolean isRecursive,
      BatchSchema tableSchema,
      List<PartitionProtobuf.PartitionValue> partitionValues) {

    return new DirListingRecordReader(
        context,
        fs,
        dirListInputSplit,
        isRecursive,
        tableSchema,
        partitionValues,
        true,
        isPartitionInferenceEnabled(),
        getFsConfCopy());
  }

  private Optional<DatasetHandle> getDatasetHandleForInternalMetadataTable(
      EntityPath datasetPath,
      FormatPluginConfig formatPluginConfig,
      TimeTravelOption.TimeTravelRequest timeTravelRequest,
      InternalMetadataTableOption internalMetadataTableOption)
      throws ConnectorException {

    final Supplier<Table> tableSupplier =
        Suppliers.memoize(
            () -> {
              final FileSystemPlugin<?> metaStoragePlugin =
                  context.getCatalogService().getSource(METADATA_STORAGE_PLUGIN_NAME);
              final IcebergModel icebergModel = metaStoragePlugin.getIcebergModel();
              final Path path =
                  metaStoragePlugin.resolveTablePathToValidPath(
                      internalMetadataTableOption.getInternalMetadataTableName());
              final IcebergTableIdentifier tableIdentifier =
                  icebergModel.getTableIdentifier(path.toString());
              final Table icebergTable = icebergModel.getIcebergTable(tableIdentifier);
              return icebergTable;
            });
    final TableSnapshotProvider tableSnapshotProvider =
        TimeTravelProcessors.getTableSnapshotProvider(
            datasetPath.getComponents(), timeTravelRequest);
    final TableSchemaProvider tableSchemaProvider =
        TimeTravelProcessors.getTableSchemaProvider(timeTravelRequest);
    FileSystem fs;
    try {
      fs = createFS(SupportsFsCreation.builder().userName(SystemUser.SYSTEM_USERNAME));
    } catch (Exception e) {
      Throwables.propagateIfPossible(e, ConnectorException.class);
      throw new ConnectorException(e);
    }
    FormatPlugin formatPlugin = formatCreator.getFormatPluginByConfig(formatPluginConfig);
    if (formatPlugin == null) {
      formatPlugin = formatCreator.newFormatPlugin(formatPluginConfig);
    }
    return Optional.of(
        new IcebergExecutionDatasetAccessor(
            datasetPath,
            tableSupplier,
            formatPlugin,
            fs,
            tableSnapshotProvider,
            tableSchemaProvider));
  }

  public String getConnection() {
    return config.getConnection();
  }

  @VisibleForTesting
  protected LoadingCache<String, org.apache.hadoop.fs.FileSystem> getHadoopFS() {
    return hadoopFS;
  }
}
