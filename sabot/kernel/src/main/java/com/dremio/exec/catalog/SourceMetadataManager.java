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
package com.dremio.exec.catalog;

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_FOLDERS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.SOURCE_METADATA_MANAGER_WAKEUP_FREQUENCY_MS;

import com.dremio.catalog.exception.SourceMalfunctionException;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.common.concurrent.AutoCloseableLock;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.util.Closeable;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetNotFoundException;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.connector.metadata.extensions.SupportsReadSignature;
import com.dremio.connector.metadata.extensions.SupportsReadSignature.MetadataValidity;
import com.dremio.connector.metadata.options.GetDatasetFilterOption;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.exec.catalog.CatalogInternalRPC.UpdateLastRefreshDateRequest;
import com.dremio.exec.catalog.DatasetCatalog.UpdateStatus;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.server.SabotQueryContext;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.exec.store.metadatarefresh.SupportsUnlimitedSplits;
import com.dremio.options.OptionManager;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.SourceState.SourceStatus;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceChangeState;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.SourceInternalData;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.ModifiableSchedulerService;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.users.SystemUser;
import com.dremio.telemetry.api.metrics.CounterWithOutcome;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.opentelemetry.api.trace.Span;
import io.protostuff.ByteString;
import java.sql.Timestamp;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import javax.inject.Provider;

/**
 * Responsible for synchronizing source metadata.
 *
 * <p>The metadata manager is responsible for both AdHoc and Background refresh. AdHoc refresh is
 * used when setting up a new source and during tests. The background refresh will do its best to
 * refresh based on the metadata policy of the underlying source. Rather than holding a lock during
 * running, this manager has no direct knowledge of the r/w lock within the ManagedStoragePlugin.
 * Instead, it gets a set of facades in SafeNamespaceService and the MetadataBridge to do
 * operations. These operations always pass through an attempt to try to grab a read lock on the
 * source plugin for that single operation. This ensures that metadata refresh never holds a lock
 * for an extended period of time.
 *
 * <p>The MetadataBridge and SafeNamespaceService are also snapshot isolated. If the configuration
 * of a source changes after a particular refresh starts, then that refresh will never be able to
 * complete successfully. This is because the snapshot version of those facades will be out of date.
 * If an operation is done while the storage plugin is changing (or has changed),
 * StoragePlguinChanging exception is thrown.
 */
class SourceMetadataManager implements AutoCloseable {

  private static final long MAXIMUM_CACHE_SIZE = 10_000L;
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(SourceMetadataManager.class);
  private static final long SCHEDULER_GRANULARITY_MS = 1 * 1000;
  private static final CounterWithOutcome BACKGROUND_METADATA_REFRESH_COUNTER =
      CounterWithOutcome.of("metadata_refresh");
  private static final String METADATA_REFRESH_TASK_NAME_PREFIX = "metadata-refresh-";

  // Stores the time (in milliseconds, obtained from System.currentTimeMillis()) at which a dataset
  // was locally updated
  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final Cache<NamespaceKey, Long> localUpdateTime =
      CacheBuilder.newBuilder().maximumSize(MAXIMUM_CACHE_SIZE).build();

  // Stores the time (in milliseconds, obtained from System.currentTimeMillis()) at which a dataset
  // metadata validity check was done
  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final Cache<NamespaceKey, DatasetMetadataState> icebergDatasetMetadataState =
      CacheBuilder.newBuilder()
          .maximumSize(MAXIMUM_CACHE_SIZE)
          .expireAfterWrite(PlannerSettings.MAX_METADATA_VALIDITY_CHECK_INTERVAL, TimeUnit.SECONDS)
          .build();

  private final NamespaceKey sourceKey;
  private final LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore;
  private final ManagedStoragePlugin.MetadataBridge bridge;
  private final CatalogServiceMonitor monitor;

  private final Cancellable wakeupTask;
  private final RefreshInfo namesRefresh;
  private final RefreshInfo fullRefresh;
  private final OptionManager optionManager;
  private final Lock runLock = new ReentrantLock();
  private volatile boolean initialized = false;
  private final Provider<MetadataRefreshInfoBroadcaster> broadcasterProvider;
  private ClusterCoordinator clusterCoordinator;
  // Do not use this SabotQueryContext. This is only necessary for passing to MetadataSynchronizer
  // and will be replaced by proper dependency injection in the future.
  private final SabotQueryContext sabotQueryContextDoNotUse;

  public SourceMetadataManager(
      NamespaceKey sourceName,
      ModifiableSchedulerService modifiableScheduler,
      boolean isVirtualMaster,
      LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore,
      final ManagedStoragePlugin.MetadataBridge bridge,
      final OptionManager options,
      final CatalogServiceMonitor monitor,
      final Provider<MetadataRefreshInfoBroadcaster> broadcasterProvider,
      final ClusterCoordinator clusterCoordinator,
      final SabotQueryContext sabotQueryContext) {
    this.sourceKey = sourceName;
    this.sourceDataStore = sourceDataStore;
    this.bridge = bridge;
    this.monitor = monitor;
    this.optionManager = options;
    this.namesRefresh = new RefreshInfo(() -> bridge.getMetadataPolicy().getNamesRefreshMs());
    this.fullRefresh =
        new RefreshInfo(() -> bridge.getMetadataPolicy().getDatasetDefinitionRefreshAfterMs());
    this.broadcasterProvider = broadcasterProvider;

    if (isVirtualMaster) {
      // we can schedule on all nodes since this is a clustered singleton and will only run on a
      // single node.
      this.wakeupTask =
          modifiableScheduler.schedule(
              Schedule.Builder.everyMillis(
                      options.getOption(SOURCE_METADATA_MANAGER_WAKEUP_FREQUENCY_MS))
                  .asClusteredSingleton(METADATA_REFRESH_TASK_NAME_PREFIX + sourceKey)
                  .build(),
              new WakeupWorker());
    } else {
      wakeupTask = null;
    }
    this.clusterCoordinator = clusterCoordinator;
    this.sabotQueryContextDoNotUse = sabotQueryContext;
  }

  @VisibleForTesting
  public SourceMetadataManager(
      NamespaceKey sourceName,
      ModifiableSchedulerService modifiableScheduler,
      boolean isMaster,
      LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore,
      final ManagedStoragePlugin.MetadataBridge bridge,
      final OptionManager options,
      final CatalogServiceMonitor monitor,
      final Provider<MetadataRefreshInfoBroadcaster> broadcasterProvider) {
    this(
        sourceName,
        modifiableScheduler,
        isMaster,
        sourceDataStore,
        bridge,
        options,
        monitor,
        broadcasterProvider,
        null,
        null);
  }

  DatasetSaver getSaver() {
    return new DatasetSaverImpl(
        bridge.getNamespaceService(),
        key -> localUpdateTime.put(key, System.currentTimeMillis()),
        optionManager);
  }

  public void setMetadataSyncInfo(UpdateLastRefreshDateRequest request) {
    fullRefresh.set(request.getLastFullRefreshDateMs());
    namesRefresh.set(request.getLastNamesRefreshDateMs());
    logger.info(
        "Source '{}' saved last refresh datetime; full refresh: {}; names refresh: {}.",
        request.getPluginName(),
        new Timestamp(fullRefresh.getLastStart()),
        new Timestamp(namesRefresh.getLastStart()));
  }

  boolean refresh(SourceUpdateType updateType, MetadataPolicy policy, boolean throwOnFailure) {
    try {

      if (!runLock.tryLock(30, TimeUnit.SECONDS)) {
        if (throwOnFailure) {
          throw UserException.concurrentModificationError()
              .message("Unable to refresh metadata within expected period.")
              .buildSilently();
        }
        return false;
      }

      try (Closeable c = AutoCloseableLock.ofAlreadyOpen(runLock, true)) {
        return new AdhocRefresh(updateType, policy).run();
      }
    } catch (InterruptedException ex) {
      return false;
    }
  }

  private void verifySourceExistence(NamespaceService namespaceService) {
    if (!namespaceService.exists(sourceKey, NameSpaceContainer.Type.SOURCE)) {
      throw new RuntimeException(String.format("Source %s does not exist", sourceKey.getName()));
    }
  }

  /** Small class to provide better logging info for scheduler start/stop logging. */
  private class WakeupWorker implements Runnable {

    @Override
    public void run() {
      logger.debug("Invoked WakeupWorker {}", sourceKey.getRoot());
      RequestContext.current()
          .with(UserContext.CTX_KEY, UserContext.SYSTEM_USER_CONTEXT)
          .run(() -> wakeup());
    }

    @Override
    public String toString() {
      return "metadata-refresh-wakeup-" + sourceKey.getRoot();
    }
  }

  /**
   * Wakeup the manager and run a refresh if necessary. This should only be called by the scheduler.
   *
   * <p>A refresh is necessary if the refresh was too long ago.
   */
  private void wakeup() {

    monitor.onWakeup();

    // if we've never refreshed, initialize the refresh start times. We do this on wakeup since that
    // will happen if this
    // node gets assigned refresh responsibilities much later than the node initially comes up. It
    // does leave the gap
    // where we may refresh early if we do a refresh and then the task immediately migrates but that
    // is probably okay
    // for now.
    if (!initialized) {
      initializeRefresh();
      // on first wakeup, we'll skip work so we can avoid a bunch of distracting exceptions when a
      // plugin is first starting.
      return;
    }

    updateSourceChangeStateIfNeeded();

    try {
      bridge.refreshState();
    } catch (TimeoutException ex) {
      logger.debug(
          "Source '{}' timed out while refreshing state, skipping refresh.", sourceKey, ex);
      return;
    } catch (Exception ex) {
      logger.debug(
          "Source '{}' refresh failed as we were unable to retrieve refresh it's state.",
          sourceKey,
          ex);
      return;
    }

    if (!runLock.tryLock()) {
      logger.info(
          "Source '{}' delaying refresh since an adhoc refresh is currently active.", sourceKey);
      return;
    }

    try (Closeable c = AutoCloseableLock.ofAlreadyOpen(runLock, true)) {
      if (!(fullRefresh.shouldRun() || namesRefresh.shouldRun())) {
        return;
      }

      final SourceState sourceState = bridge.getState();
      if (sourceState == null || sourceState.getStatus() == SourceStatus.bad) {
        logger.info(
            "Source '{}' skipping metadata refresh since it is currently in a bad state of {}.",
            sourceKey,
            sourceState);
        return;
      }

      final BackgroundRefresh refresh;
      if (fullRefresh.shouldRun()) {
        refresh = new BackgroundRefresh(fullRefresh, true);
      } else {
        refresh = new BackgroundRefresh(namesRefresh, false);
      }
      refresh.run();
    } catch (RuntimeException e) {
      logger.warn(
          "Source '{}' metadata refresh failed to complete due to an exception.", sourceKey, e);
    }
  }

  private void updateSourceChangeStateIfNeeded() {
    try {
      SourceConfig sourceConfig = bridge.getNamespaceService().getSource(sourceKey);
      if (!monitor.isActiveSourceChange()
          && sourceConfig.getSourceChangeState() != SourceChangeState.SOURCE_CHANGE_STATE_NONE) {
        bridge
            .getNamespaceService()
            .addOrUpdateSource(
                sourceKey,
                sourceConfig.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_NONE));
      }
    } catch (NamespaceException ignored) {
      // Exceptions will be ignored.
      logger.warn("Failed to update source change state for source '{}'", sourceKey, ignored);
    }
  }

  /**
   * Populate RefreshInfo objects with data from the kvstore. This is done outside construction
   * since it is only necessary for the current singleton master.
   */
  private void initializeRefresh() {
    SourceInternalData srcData = sourceDataStore.get(sourceKey);
    if (srcData == null) {
      try {
        sourceDataStore.put(sourceKey, new SourceInternalData());
        return;
      } catch (ConcurrentModificationException e) {
        // Refresh data might already be saved in saveRefreshData
        logger.warn("Failed to save refresh data for {}.", sourceKey, e);
        srcData = sourceDataStore.get(sourceKey);
        if (srcData == null) {
          return;
        }
      }
    }

    if (srcData.getLastNameRefreshDateMs() != null) {
      namesRefresh.set(srcData.getLastNameRefreshDateMs());
    }
    if (srcData.getLastFullRefreshDateMs() != null) {
      fullRefresh.set(srcData.getLastFullRefreshDateMs());
    }
    initialized = true;
  }

  /**
   * Closes this source metadata manager so it won't do any more refreshes. This should be done by
   * someone who has a writelock on the parent source.
   */
  @Override
  public void close() throws Exception {
    // avoid future wakeups.
    if (wakeupTask != null) {
      wakeupTask.cancel(false);
    }
  }

  /**
   * Checks if the entry is valid.
   *
   * @param options metadata request options
   * @param config dataset config
   * @return true iff entry is valid
   */
  boolean isStillValid(
      MetadataRequestOptions options, DatasetConfig config, SourceMetadata plugin) {
    return isStillValid(
            options,
            config,
            plugin,
            (pluginForIceberg, datasetConfig) ->
                CompletableFuture.completedFuture(
                    pluginForIceberg.isIcebergMetadataValid(
                        datasetConfig, new NamespaceKey(datasetConfig.getFullPathList()))))
        .toCompletableFuture()
        .join();
  }

  /**
   * Checks if the entry is valid. Executes potentially expensive Iceberg validity checks using the
   * provided async function.
   *
   * @param options metadata request options
   * @param config dataset config
   * @param plugin storage plugin
   * @param asyncIcebergValidityCheck async validity check for Iceberg tables
   * @return a future boolean result
   */
  CompletionStage<Boolean> isStillValid(
      MetadataRequestOptions options,
      DatasetConfig config,
      SourceMetadata plugin,
      BiFunction<SupportsIcebergRootPointer, DatasetConfig, CompletionStage<Boolean>>
          asyncIcebergValidityCheck) {
    final NamespaceKey key = new NamespaceKey(config.getFullPathList());
    final Long updateTime = localUpdateTime.getIfPresent(key);
    final long currentTime = System.currentTimeMillis();
    final long expiryTime = bridge.getMetadataPolicy().getDatasetDefinitionExpireAfterMs();
    Span.current().setAttribute("dremio.namespace.key.schemapath", key.getSchemaPath());

    if (plugin instanceof SupportsIcebergRootPointer && DatasetHelper.isIcebergDataset(config)) {
      SupportsIcebergRootPointer pluginForIceberg = (SupportsIcebergRootPointer) plugin;
      Long lastMetadataValidityCheckTime =
          Optional.ofNullable(icebergDatasetMetadataState.getIfPresent(key))
              .flatMap(DatasetMetadataState::lastRefreshTimeMillis)
              .orElse(0L);
      boolean shouldDoValidityCheck =
          shouldDoIcebergValidityCheck(
              pluginForIceberg, options, lastMetadataValidityCheckTime, currentTime);
      if (shouldDoValidityCheck) {
        return asyncIcebergValidityCheck
            .apply(pluginForIceberg, config)
            .whenComplete(
                (isValid, ex) -> {
                  DatasetMetadataState metadataState =
                      DatasetMetadataState.builder()
                          .setIsExpired(!isValid)
                          .setLastRefreshTimeMillis(currentTime)
                          .build();
                  icebergDatasetMetadataState.put(key, metadataState);
                });
      }
    }

    final boolean isDatasetExpired =
        options.newerThan() < currentTime
            || //  request marks this expired
            ((updateTime == null || updateTime + expiryTime < currentTime)
                && // dataset was locally updated too long ago (or never)
                fullRefresh.getLastStart() + expiryTime
                    < currentTime); // AND dataset was globally updated too long ago

    // bypass checking validity if the option to disable checking validity is set
    if (!options.checkValidity()) {
      // Log a warning message only if expired.
      if (isDatasetExpired) {
        logger.warn(
            "Metadata for dataset '{}' has expired, but validity check has been disabled, likely for the source. Local update time: {}. Global update time: {}. Expiry time: {} min",
            key,
            updateTime != null ? new Timestamp(updateTime) : null,
            new Timestamp(fullRefresh.getLastStart()),
            expiryTime / 60000);
      }
      return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    // check if the entry is expired  or  request marks this dataset as invalid
    if (isDatasetExpired || !options.getSchemaConfig().getDatasetValidityChecker().apply(config)) {
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Dataset {} metadata is not valid. Request marks this expired: {}. Local update time: {}. Global update time: {}. Expiry time: {} min",
            key,
            options.newerThan() < currentTime,
            updateTime != null ? new Timestamp(updateTime) : null,
            new Timestamp(fullRefresh.getLastStart()),
            expiryTime / 60000);
      }
      return CompletableFuture.completedFuture(Boolean.FALSE);
    }

    return CompletableFuture.completedFuture(Boolean.TRUE);
  }

  protected boolean shouldDoIcebergValidityCheck(
      SupportsIcebergRootPointer plugin,
      MetadataRequestOptions options,
      long lastValidityCheckTime,
      long currentTime) {
    return options.checkValidity()
        && !plugin.isMetadataValidityCheckRecentEnough(
            lastValidityCheckTime, currentTime, optionManager);
  }

  DatasetMetadataState getDatasetMetadataState(DatasetConfig config, StoragePlugin plugin) {
    if (plugin instanceof SupportsIcebergRootPointer && DatasetHelper.isIcebergDataset(config)) {
      final NamespaceKey key = new NamespaceKey(config.getFullPathList());
      // If there is no recorded metadata state (from previous validity check) then assume expired
      return Optional.ofNullable(icebergDatasetMetadataState.getIfPresent(key))
          .orElseGet(() -> DatasetMetadataState.builder().setIsExpired(true).build());
    }
    return DatasetMetadataState.builder()
        .setIsExpired(config.getLastModified() == null)
        .setLastRefreshTimeMillis(config.getLastModified())
        .build();
  }

  /** An abstract implementation of refresh logic. */
  private abstract class RefreshRunner {

    private final NamespaceService systemNamespace = bridge.getNamespaceService();

    boolean refreshNames(List<List<String>> folders) {
      logger.info("Name-only update for source '{}'", sourceKey);
      final Set<NamespaceKey> existingDatasets =
          Sets.newHashSet(systemNamespace.getAllDatasets(sourceKey));

      final MetadataSynchronizerStatus metadataSynchronizerStatus =
          new MetadataSynchronizerStatus(false);

      final Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        verifySourceExistence(systemNamespace);

        Optional<SourceMetadata> sourceMetadata = bridge.getMetadata();
        if (sourceMetadata.isEmpty()) {
          throw new SourceMalfunctionException(sourceKey.getName());
        }

        // Refreshing folders to get all metadata for it prior to refreshing datasets; Currently
        // Refreshing datasets doesn't fetch all metadata about the folder
        if (sourceMetadata.get() instanceof SupportsFolderIngestion) {
          handleFolderListing(
              metadataSynchronizerStatus, (SupportsFolderIngestion) sourceMetadata.get());
        }

        if (sourceMetadata.get() instanceof SupportsListingDatasets) {
          handleDatasetListing(
              folders,
              existingDatasets,
              metadataSynchronizerStatus,
              (SupportsListingDatasets) sourceMetadata.get());
        }

        logger.info(
            "Source '{}' refreshed names in {} seconds. Details:\n{}",
            sourceKey,
            stopwatch.elapsed(TimeUnit.SECONDS),
            metadataSynchronizerStatus);
      } catch (Exception ex) {
        logger.warn(
            "Source '{}' shallow probe failed. Dataset listing maybe incomplete", sourceKey, ex);
      }

      return metadataSynchronizerStatus.wasSyncSuccessful();
    }

    private void handleDatasetListing(
        List<List<String>> folders,
        Set<NamespaceKey> existingDatasets,
        MetadataSynchronizerStatus metadataSynchronizerStatus,
        SupportsListingDatasets sourceMetadata)
        throws NamespaceException, ConnectorException {
      final SupportsListingDatasets listingProvider = sourceMetadata;
      final GetDatasetOption[] defaultOptions =
          bridge.getDefaultRetrievalOptions().asGetDatasetOptions(null);
      GetDatasetFilterOption foldersOption = new GetDatasetFilterOption(folders);
      List<GetDatasetOption> optionsList = Lists.newArrayList(defaultOptions);
      optionsList.add(foldersOption);
      GetDatasetOption[] options = optionsList.toArray(new GetDatasetOption[optionsList.size()]);

      logger.debug("Source '{}' names sync started", sourceKey);
      try (DatasetHandleListing listing = listingProvider.listDatasetHandles(options)) {
        final Iterator<? extends DatasetHandle> iterator = listing.iterator();
        while (iterator.hasNext()) {
          final DatasetHandle handle = iterator.next();
          final NamespaceKey datasetKey =
              MetadataObjectsUtils.toNamespaceKey(handle.getDatasetPath());
          if (existingDatasets.remove(datasetKey)) {
            metadataSynchronizerStatus.incrementDatasetShallowUnchanged();
            continue;
          }

          final DatasetConfig newConfig = MetadataObjectsUtils.newShallowConfig(handle);
          try {
            systemNamespace.addOrUpdateDataset(datasetKey, newConfig);
            metadataSynchronizerStatus.setWasSuccessfullyRefreshed();
            metadataSynchronizerStatus.incrementDatasetShallowAdded();
            logger.debug("Dataset '{}' added", datasetKey);
          } catch (ConcurrentModificationException ignored) {
            logger.debug("Dataset '{}' add failed (CME)", datasetKey);
          }
        }
      }
    }

    private void handleFolderListing(
        MetadataSynchronizerStatus metadataSynchronizerStatus,
        SupportsFolderIngestion sourceMetadata)
        throws NamespaceException {
      if (!optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)) {
        return;
      }
      Set<FolderConfig> existingFolders = Sets.newHashSet(systemNamespace.getFolders(sourceKey));
      Set<NamespaceKey> existingFolderPaths =
          existingFolders.stream()
              .map(folder -> new NamespaceKey(folder.getFullPathList()))
              .collect(Collectors.toSet());

      FolderListing folderListing = sourceMetadata.getFolderListing();
      final Iterator<ImmutableCatalogFolder> iterator = folderListing.iterator();
      while (iterator.hasNext()) {
        final ImmutableCatalogFolder folderFromPlugin = iterator.next();
        final NamespaceKey folderKey = new NamespaceKey(folderFromPlugin.fullPath());
        Set<FolderConfig> existingSubFolders =
            Sets.newHashSet(systemNamespace.getFolders(folderKey));
        Set<NamespaceKey> existingSubFolderPaths =
            existingSubFolders.stream()
                .map(folder -> new NamespaceKey(folder.getFullPathList()))
                .collect(Collectors.toSet());
        existingFolderPaths.addAll(existingSubFolderPaths);
        if (existingFolderPaths.contains(folderKey)) {
          metadataSynchronizerStatus.incrementDatasetShallowUnchanged();
          existingFolderPaths.remove(folderKey);
          continue;
        }
        try {
          systemNamespace.addOrUpdateFolder(
              folderKey, CatalogFolderUtils.convertToNS(folderFromPlugin));
        } catch (NamespaceException | ConcurrentModificationException e) {
          logger.warn(
              "There was a NamespaceException while trying to add folder '{}' in NamespaceService cache.",
              folderKey,
              e);
        }
      }
      removeFoldersThatNoLongerFound(existingFolderPaths);
    }

    private void removeFoldersThatNoLongerFound(Set<NamespaceKey> existingFolderPaths) {
      for (NamespaceKey folderKey : existingFolderPaths) {
        try {
          FolderConfig config = systemNamespace.getFolder(folderKey);
          systemNamespace.deleteFolder(folderKey, config.getTag());
        } catch (NamespaceException | ConcurrentModificationException e) {
          logger.warn(
              "There was a NamespaceException while trying to delete folder '{}' in NamespaceService cache.",
              folderKey,
              e);
        }
      }
    }

    boolean refreshFull(MetadataPolicy metadataPolicy) {
      logger.info("Full update for source '{}'", sourceKey);
      final DatasetRetrievalOptions retrievalOptions;
      if (metadataPolicy == null) {
        metadataPolicy = bridge.getMetadataPolicy();
        retrievalOptions = bridge.getDefaultRetrievalOptions(); // based on msp.getMetadataPolicy();
      } else {
        retrievalOptions =
            DatasetRetrievalOptions.fromMetadataPolicy(metadataPolicy).toBuilder()
                .setMaxMetadataLeafColumns(bridge.getMaxMetadataColumns())
                .build();
      }
      retrievalOptions.withFallback(DatasetRetrievalOptions.DEFAULT);

      if (metadataPolicy.getDatasetUpdateMode() == UpdateMode.UNKNOWN) {
        logger.warn("Skipping full metadata refresh because update mode is unknown.");
        return false;
      }

      verifySourceExistence(systemNamespace);

      final Stopwatch stopwatch = Stopwatch.createStarted();
      final MetadataSynchronizer synchronizeRun =
          new MetadataSynchronizer(
              systemNamespace,
              sourceKey,
              bridge,
              metadataPolicy,
              getSaver(),
              retrievalOptions,
              optionManager,
              sabotQueryContextDoNotUse);

      final MetadataSynchronizerStatus metadataSynchronizerStatus = synchronizeRun.go();

      logger.info(
          "Source '{}' refreshed details in {} seconds. Details:\n{}",
          sourceKey,
          stopwatch.elapsed(TimeUnit.SECONDS),
          metadataSynchronizerStatus);

      return metadataSynchronizerStatus.wasSyncSuccessful();
    }

    void saveRefreshData() {
      SourceInternalData srcData = sourceDataStore.get(sourceKey);
      if (srcData == null) {
        srcData = new SourceInternalData();
      }

      srcData
          .setLastFullRefreshDateMs(fullRefresh.getLastStart())
          .setLastNameRefreshDateMs(namesRefresh.getLastStart());
      final UpdateLastRefreshDateRequest refreshRequest =
          UpdateLastRefreshDateRequest.newBuilder()
              .setLastNamesRefreshDateMs(namesRefresh.getLastStart())
              .setLastFullRefreshDateMs(fullRefresh.getLastStart())
              .setPluginName(sourceKey.getName())
              .build();
      try {
        broadcasterProvider.get().communicateChange(refreshRequest);
      } catch (Exception e) {
        logger.warn(
            "Source '{}' unable to communicate last metadata refresh info changes with other coordinators.",
            sourceKey.getName(),
            e);
      }
      try {
        sourceDataStore.put(sourceKey, srcData);
      } catch (ConcurrentModificationException e) {
        // Refresh data might already be saved in initializeRefresh
        logger.warn("Failed to save refresh data for '{}' source", sourceKey, e);
        srcData = sourceDataStore.get(sourceKey);
        if (srcData == null) {
          throw e;
        }
        srcData
            .setLastFullRefreshDateMs(fullRefresh.getLastStart())
            .setLastNameRefreshDateMs(namesRefresh.getLastStart());
        sourceDataStore.put(sourceKey, srcData);
      }
    }
  }

  /** A refresh run based on an adhoc request. */
  private class AdhocRefresh extends RefreshRunner {

    private final SourceUpdateType updateType;
    private final MetadataPolicy policy;

    public AdhocRefresh(SourceUpdateType updateType, MetadataPolicy policy) {
      super();
      this.updateType = updateType;
      this.policy = policy;
    }

    public boolean run() {
      monitor.startAdhocRefresh();

      try {
        monitor.startAdhocRefreshWithLock();
        final boolean didRefresh;
        switch (updateType.getType()) {
          case FULL:
            try (Closeable time = fullRefresh.start()) {
              didRefresh = refreshFull(policy);
            }
            saveRefreshData();
            return didRefresh;
          case NAMES:
            try (Closeable time = namesRefresh.start()) {
              didRefresh = refreshNames(null);
            }
            saveRefreshData();
            return didRefresh;
          case NAMES_IN_FOLDERS:
            // Don't update last refresh time since we probably only refreshed some names
            return refreshNames(updateType.getFolders());
          case NONE:
            return false;
          default:
            throw new IllegalArgumentException("Unknown type: " + updateType);
        }
      } finally {
        monitor.finishAdhocRefresh();
      }
    }
  }

  /**
   * Runnable that refreshes the metadata associated with the source. Could be a full refresh or a
   * shallow depending on the current times.
   */
  private class BackgroundRefresh extends RefreshRunner implements Runnable {

    private final RefreshInfo refreshInfo;
    private final boolean fullRefresh;

    BackgroundRefresh(RefreshInfo refreshInfo, boolean fullRefresh) {
      this.refreshInfo = refreshInfo;
      this.fullRefresh = fullRefresh;
    }

    @Override
    public void run() {
      monitor.startBackgroundRefresh();

      try {
        monitor.startBackgroundRefreshWithLock();

        logger.debug("Source '{}' scheduled refresh started", sourceKey);
        try {
          try (Closeable time = refreshInfo.start()) {

            if (fullRefresh) {
              refreshFull(null);
            } else {
              refreshNames(null);
            }
          }

          // save post timer close.
          saveRefreshData();
          BACKGROUND_METADATA_REFRESH_COUNTER.succeeded();
        } catch (Exception e) {
          // Exception while updating the metadata. Ignore, and try again later
          logger.warn(
              "Source '{}' failed to execute refresh for plugin due to an exception.",
              sourceKey,
              e);

          BACKGROUND_METADATA_REFRESH_COUNTER.errored();
        }

      } finally {
        monitor.finishBackgroundRefresh();
      }
    }
  }

  /**
   * Invoked by ALTER TABLE REFRESH via CatalogService & ManagedStoragePlugin
   *
   * @param datasetKey
   * @param options
   * @return
   * @throws ConnectorException
   * @throws NamespaceException
   * @throws UserException
   */
  UpdateStatus refreshDataset(NamespaceKey datasetKey, DatasetRetrievalOptions options)
      throws ConnectorException, NamespaceException, SourceMalfunctionException {
    options.withFallback(bridge.getDefaultRetrievalOptions());
    final NamespaceService namespace = bridge.getNamespaceService();
    DatasetConfig knownConfig = null;
    Optional<DatasetHandle> handle = Optional.empty();
    EntityPath entityPath;
    try {
      knownConfig = namespace.getDataset(datasetKey);
    } catch (NamespaceNotFoundException ignored) {
      // Try to get the fully resolved name (by referring to the source) of the provided dataset key
      // and check again if there is an entry already
      // in the namespace or if it's really a new one or a shortened version (hive default case)
      Optional<SourceMetadata> sourceMetadata = bridge.getMetadata();
      if (sourceMetadata.isEmpty()) {
        throw new SourceMalfunctionException(sourceKey.getName());
      }
      entityPath = MetadataObjectsUtils.toEntityPath(datasetKey);
      handle =
          sourceMetadata.get().getDatasetHandle((entityPath), options.asGetDatasetOptions(null));

      if (handle.isEmpty()) { // dataset is not in the source either
        throw new DatasetNotFoundException(
            String.format("Dataset [%s] not found.", datasetKey), entityPath);
      }
      try {
        // try to get the  config with the fully resolved name
        knownConfig =
            namespace.getDataset(
                MetadataObjectsUtils.toNamespaceKey(handle.get().getDatasetPath()));
      } catch (NamespaceNotFoundException ignored2) {
      }
    }

    final DatasetConfig currentConfig = knownConfig;
    final boolean exists = currentConfig != null;
    final boolean isExtended = exists && currentConfig.getReadDefinition() != null;
    final boolean isView = exists && currentConfig.getType() == DatasetType.VIRTUAL_DATASET;
    final boolean isIcebergView =
        exists
            && DatasetHelper.isIcebergView(currentConfig)
            && optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED);

    if (isView && !isIcebergView) {
      throw UserException.validationError()
          .message("Only tables can be refreshed. Dataset %s is a view.", datasetKey)
          .buildSilently();
    }

    if (exists) {
      entityPath = new EntityPath(currentConfig.getFullPathList());
    } else {
      entityPath = MetadataObjectsUtils.toEntityPath(datasetKey);
    }

    logger.debug(
        "Dataset '{}' is being synced (exists: {}, isExtended: {})",
        datasetKey,
        exists,
        isExtended);
    Optional<SourceMetadata> sourceMetadata = bridge.getMetadata();
    if (sourceMetadata.isEmpty()) {
      throw new SourceMalfunctionException(sourceKey.getName());
    }

    if (handle.isEmpty()) { // not already retrieved above
      handle =
          sourceMetadata
              .get()
              .getDatasetHandle(entityPath, options.asGetDatasetOptions(currentConfig));
    }

    if (handle.isEmpty()) { // dataset is not in the source
      if (!options.deleteUnavailableDatasets()) {
        logger.debug("Dataset '{}' unavailable, but not deleted", datasetKey);
        return UpdateStatus.UNCHANGED;
      }

      try {
        CatalogUtil.addIcebergMetadataOrphan(currentConfig, bridge.getOrphanage());
        namespace.deleteDataset(datasetKey, currentConfig.getTag());
        logger.trace("Dataset '{}' deleted", datasetKey);
        return UpdateStatus.DELETED;
      } catch (NamespaceException e) {
        logger.debug("Dataset '{}' delete failed", datasetKey, e);
        return UpdateStatus.UNCHANGED;
      }
    }
    final DatasetConfig datasetConfig;
    if (exists) {
      datasetConfig = currentConfig;
    } else {
      datasetConfig = MetadataObjectsUtils.newShallowConfig(handle.get());
    }
    return saveDatasetAndMetadataInNamespace(datasetConfig, handle.get(), options);
  }

  /**
   * Invoked by refreshDataset above and directly by ALTER TABLE SET OPTIONS via
   * ManagedStoragePlugin
   *
   * @param datasetConfig
   * @param options
   * @return
   * @throws ConnectorException
   * @throws NamespaceException
   */
  public UpdateStatus saveDatasetAndMetadataInNamespace(
      DatasetConfig datasetConfig, DatasetHandle datasetHandle, DatasetRetrievalOptions options)
      throws ConnectorException, SourceMalfunctionException {
    options.withFallback(bridge.getDefaultRetrievalOptions());
    final DatasetSaver saver = getSaver();

    final boolean isExtended = datasetConfig.getReadDefinition() != null;
    String user = SystemUser.SYSTEM_USERNAME;
    if (options.datasetRefreshQuery().isPresent()) {
      user = options.datasetRefreshQuery().get().getUser();
    }

    Optional<SourceMetadata> sourceMetadata = bridge.getMetadata();
    if (sourceMetadata.isEmpty()) {
      throw new SourceMalfunctionException(sourceKey.getName());
    }

    boolean supportsIcebergMetadata =
        (sourceMetadata.get() instanceof SupportsUnlimitedSplits)
            && ((SupportsUnlimitedSplits) sourceMetadata.get())
                .allowUnlimitedSplits(datasetHandle, datasetConfig, user);
    final boolean isIcebergMetadata =
        datasetConfig.getPhysicalDataset() != null
            && Boolean.TRUE.equals(datasetConfig.getPhysicalDataset().getIcebergMetadataEnabled());
    final boolean forceUpdateNotRequired = !supportsIcebergMetadata || isIcebergMetadata;

    // Bypass the save if forceUpdate isn't true and read definitions have not been updated.
    if (forceUpdateNotRequired
        && !options.forceUpdate()
        && isExtended
        && sourceMetadata.get() instanceof SupportsReadSignature) {
      final SupportsReadSignature supportsReadSignature =
          (SupportsReadSignature) sourceMetadata.get();
      final DatasetMetadata currentExtended = new DatasetMetadataAdapter(datasetConfig);

      final ByteString readSignature = datasetConfig.getReadDefinition().getReadSignature();
      final MetadataValidity metadataValidity =
          supportsReadSignature.validateMetadata(
              readSignature == null || readSignature.size() == 0
                  ? BytesOutput.NONE
                  : os -> ByteString.writeTo(os, readSignature),
              datasetHandle,
              currentExtended);

      if (metadataValidity == MetadataValidity.VALID) {
        logger.trace("Dataset '{}' metadata is valid, skipping", datasetConfig.getName());
        return UpdateStatus.UNCHANGED;
      }
    }

    String prevMetadataRootPointer = "";
    long prevModified = -1;

    if (datasetConfig.getPhysicalDataset() != null) {
      if (datasetConfig.getPhysicalDataset().getIcebergMetadata() != null) {
        prevMetadataRootPointer =
            datasetConfig.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation();
      }
    }
    if (datasetConfig.getLastModified() != null) {
      prevModified = datasetConfig.getLastModified();
    }

    // DX-100186: once we decide what to do for SQL command ALTER VIEW, we should follow up here.
    saver.save(datasetConfig, datasetHandle, sourceMetadata.get(), false, options);

    if (datasetConfig.getPhysicalDataset() != null) {
      if (datasetConfig.getPhysicalDataset().getIcebergMetadataEnabled() != null
          && datasetConfig.getPhysicalDataset().getIcebergMetadataEnabled()
          && datasetConfig.getPhysicalDataset().getIcebergMetadata() != null) {
        String currMetadataRootPointer =
            datasetConfig.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation();
        if (currMetadataRootPointer == prevMetadataRootPointer
            && !"".equals(currMetadataRootPointer)) {
          return UpdateStatus.UNCHANGED;
        }
      } else {
        if (datasetConfig.getLastModified() != null) {
          long currModified = datasetConfig.getLastModified();
          if (prevModified == currModified && currModified != -1) {
            return UpdateStatus.UNCHANGED;
          }
        }
      }
    }

    logger.trace("Dataset '{}' metadata saved to namespace", datasetConfig.getName());
    return UpdateStatus.CHANGED;
  }

  public long getLastFullRefreshDateMs() {
    return fullRefresh.getLastStart();
  }

  public long getLastNamesRefreshDateMs() {
    return namesRefresh.getLastStart();
  }

  /** Describes info about last refresh. */
  private static class RefreshInfo {
    private final LongSupplier refreshIntervalSupplier;

    private volatile long lastStart = 0;
    private volatile long lastDuration = 0;

    public RefreshInfo(LongSupplier refreshIntervalSupplier) {
      this.refreshIntervalSupplier = refreshIntervalSupplier;
    }

    boolean shouldRun() {
      return lastStart + refreshIntervalSupplier.getAsLong()
          < System.currentTimeMillis() - SCHEDULER_GRANULARITY_MS;
    }

    long getLastStart() {
      return lastStart;
    }

    @SuppressWarnings("unused")
    long getLastDurationMillis() {
      return lastDuration;
    }

    public void set(long lastRefreshMS) {
      lastStart = lastRefreshMS;
    }

    public Closeable start() {
      final long start = System.currentTimeMillis();
      lastStart = start;

      return () -> {
        long end = System.currentTimeMillis();
        if (end <= start) {
          end = start + 1;
        }
        lastDuration = end - start;
      };
    }
  }

  public void deleteServiceSet() throws Exception {
    if (clusterCoordinator == null) {
      throw new IllegalStateException(
          "Unable to delete service set as cluster coordinator instance is null");
    }
    clusterCoordinator.deleteServiceSet(METADATA_REFRESH_TASK_NAME_PREFIX + sourceKey);
  }
}
