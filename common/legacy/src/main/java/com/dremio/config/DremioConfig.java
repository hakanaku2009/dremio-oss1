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
package com.dremio.config;

import static com.dremio.io.file.UriSchemes.FILE_SCHEME;
import static com.dremio.io.file.UriSchemes.LEGACY_PDFS_SCHEME;
import static com.dremio.io.file.UriSchemes.SCHEME_SEPARATOR;

import com.dremio.common.config.NestedConfig;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.perf.Timer;
import com.dremio.common.perf.Timer.TimedBlock;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import org.reflections.util.ClasspathHelper;

/**
 * A configuration object that is merged with and validated against the dremio-reference.conf
 * configuration.
 */
@SuppressWarnings("checkstyle:FinalClass")
public class DremioConfig extends NestedConfig {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(DremioConfig.class);

  private static final String REFERENCE_CONFIG = "dremio-reference.conf";
  private static final String DEFAULT_USER_CONFIG = "dremio.conf";

  public static final String LOCAL_WRITE_PATH_STRING = "paths.local";
  public static final String DIST_WRITE_PATH_STRING = "paths.dist";

  public static final String CREDENTIALS_KEYSTORE_PASSWORD = "security.keystore-password";

  public static final String NODE_TAG = "services.node-tag";
  public static final String ENABLE_COORDINATOR_BOOL = "services.coordinator.enabled";
  public static final String ENABLE_MASTER_BOOL = "services.coordinator.master.enabled";
  public static final String ENABLE_EXECUTOR_BOOL = "services.executor.enabled";
  public static final String EMBEDDED_MASTER_ZK_ENABLED_BOOL =
      "services.coordinator.master.embedded-zookeeper.enabled";
  public static final String EMBEDDED_MASTER_ZK_ENABLED_PORT_INT =
      "services.coordinator.master.embedded-zookeeper.port";
  public static final String EMBEDDED_MASTER_ZK_ENABLED_PATH_STRING =
      "services.coordinator.master.embedded-zookeeper.path";
  public static final String EMBEDDED_MASTER_ZK_WATCHDOG_FAILURE_COUNT_THRESHOLD =
      "services.executor.embedded-zookeeper.watchdog.failure_count_threshold";
  public static final String EMBEDDED_MASTER_ZK_WATCHDOG_FAILURE_DURATION_THRESHOLD =
      "services.executor.embedded-zookeeper.watchdog.failure_duration_threshold";
  public static final String WEB_ENABLED_BOOL = "services.coordinator.web.enabled";
  public static final String WEB_AUTH_TYPE = "services.coordinator.web.auth.type";
  public static final String WEB_PORT_INT = "services.coordinator.web.port";
  public static final String WEB_TOKEN_CACHE_SIZE = "services.coordinator.web.tokens.cache.size";
  public static final String SCHEDULER_SERVICE_THREAD_COUNT =
      "services.coordinator.scheduler.threads";
  public static final String SCHEDULER_HALT_ON_ZK_LOST =
      "services.coordinator.scheduler.halt_on_zk_lost";
  // Use new clustered singleton
  public static final String SCHEDULER_LEADERLESS_CLUSTERED_SINGLETON =
      "services.coordinator.scheduler.leaderless.enabled";

  public static final String WEB_TOKEN_CACHE_EXPIRATION =
      "services.coordinator.web.tokens.cache.expiration_minutes";
  public static final String TASK_ON_IDLE_LOAD_SHED = "debug.task.on_idle_load_shed";
  public static final String TASK_RESCHEDULE_ON_UNBLOCK = "debug.task.reschedule_on_unblock";
  public static final String TASK_EAGER_LOAD_SHED = "debug.task.eager_load_shed";
  public static final String KERBEROS_PRINCIPAL = "services.kerberos.principal";
  public static final String KERBEROS_KEYTAB_PATH = "services.kerberos.keytab.file.path";

  public static final String CACHE_DB_PATH = "services.executor.cache.path.db";
  public static final String CACHE_FS_PATH_LIST = "services.executor.cache.path.fs";
  public static final String CACHE_ENABLED = "services.executor.cache.enabled";
  public static final String CACHE_DB_QUOTA = "services.executor.cache.pctquota.db";
  public static final String CACHE_FS_QUOTA_LIST = "services.executor.cache.pctquota.fs";
  public static final String CACHE_FS_ENSURE_FREE_SPACE_LIST =
      "services.executor.cache.ensurefreespace.fs";
  public static final String JOBS_ENABLED_BOOL = "services.jobs.enabled";
  public static final String NO_OP_CLUSTER_COORDINATOR_ENABLED =
      "debug.noop.cluster" + ".coordinator.enabled";

  /** Config values related to plan caching */
  public static final String PLAN_CACHE_TIMEOUT_MINUTES = "dremio.plan.cache.timeout_minutes";

  public static final String PLAN_CACHE_MAX_ENTRIES = "dremio.plan.cache.max_entries";
  public static final String PLAN_CACHE_MAX_SIZE_BYTES = "dremio.plan.cache.max_bytes";

  /** config values related to partition stats caching */
  public static final String PARTITION_STATS_CACHE_TTL =
      "dremio.partition.stats.cache.timeout_seconds";

  /** Config values related to statistics caching */
  public static final String STATISTICS_CACHE_TIMEOUT_MINUTES =
      "dremio.statistics.cache.timeout_minutes";

  public static final String STATISTICS_CACHE_MAX_ENTRIES = "dremio.statistics.cache.max_entries";

  /** Path where ui config is located */
  public static final String WEB_UI_SERVICE_CONFIG = "services.coordinator.web.ui";

  /** Config values related to plugins */
  public static final String PLUGINS_ROOT_PATH_PROPERTY = "dremio.plugins.path";

  public static final String LEGACY_STORE_VIEWS_ENABLED = "legacy.dremio.store.views.enabled";

  public static final String CLIENT_PORT_INT = "services.coordinator.client-endpoint.port";
  public static final String SERVER_PORT_INT = "services.fabric.port";
  public static final String CONDUIT_PORT_INT = "services.conduit.port";
  public static final String FLIGHT_SERVICE_ENABLED_BOOLEAN = "services.flight.enabled";
  public static final String FLIGHT_SERVICE_PORT_INT = "services.flight.port";
  public static final String FLIGHT_SERVICE_AUTHENTICATION_MODE = "services.flight.auth.mode";
  // Session manager configuration
  public static final String FLIGHT_USE_SESSION_SERVICE = "services.flight.use_session_service";

  public static final String NESSIE_SERVICE_ENABLED_BOOLEAN = "services.nessie.enabled";
  public static final String NESSIE_SERVICE_IN_MEMORY_BOOLEAN = "services.nessie.backend.in-memory";
  public static final String NESSIE_SERVICE_REMOTE_URI = "services.nessie.remote-uri";
  public static final String NESSIE_SERVICE_LAKEHOUSE_CATALOG_URI =
      "services.lakehouse.catalog.nessie-uri";
  public static final String NESSIE_SERVICE_LAKEHOUSE_CATALOG_PORT =
      "services.lakehouse.catalog.nessie-port";

  public static final String AUTOUPGRADE = "services.coordinator.auto-upgrade";

  public static final String REGISTRATION_ADDRESS = "registration.publish-host";
  public static final String DB_PATH_STRING = "paths.db";
  public static final String ACCELERATOR_PATH_STRING = "paths.accelerator";
  public static final String DOWNLOADS_PATH_STRING = "paths.downloads";
  public static final String UPLOADS_PATH_STRING = "paths.uploads";
  public static final String RESULTS_PATH_STRING = "paths.results";
  public static final String SCRATCH_PATH_STRING = "paths.scratch";
  public static final String PROFILE_PATH_STRING = "paths.profile";
  public static final String SPILLING_PATH_STRING = "paths.spilling";
  public static final String METADATA_PATH_STRING = "paths.metadata";
  public static final String GANDIVA_CACHE_PATH_STRING = "paths.gandiva";
  public static final String SYSTEM_ICEBERG_TABLES_PATH_STRING = "paths.system_iceberg_tables";
  public static final String NODE_HISTORY_PATH_STRING = "paths.node_history";

  public static final Set<String> DEPRECATED_PATHS = ImmutableSet.of("paths.copyintoerrors");

  public static final String ZOOKEEPER_QUORUM = "zookeeper";
  public static final String ZK_CLIENT_SESSION_TIMEOUT = "zk.client.session.timeout";
  public static final String ZK_CLIENT_RETRY_UNLIMITED = "zk.client.retry.unlimited";
  public static final String ZK_CONNECTION_HANDLE_ENABLED = "zk.client.connection_handle.enabled";
  public static final String ZK_CLIENT_RETRY_LIMIT = "zk.client.retry.limit";
  public static final String ZK_CLIENT_INITIAL_TIMEOUT_MS = "zk.client.retry.initial_timeout_ms";
  public static final String ZK_SUPERVISOR_INTERVAL_MS = "zk.client.supervisor.interval_ms";
  public static final String ZK_SUPERVISOR_READ_TIMEOUT_MS = "zk.client.supervisor.read_timeout_ms";
  public static final String ZK_SUPERVISOR_MAX_FAILURES = "zk.client.supervisor.max_failures";

  // Provisioning options
  public static final String YARN_ENABLED_BOOL = "provisioning.yarn.enabled";
  public static final String YARN_JVM_OPTIONS = "provisioning.yarn.jvmoptions";
  public static final String YARN_CLASSPATH = "provisioning.yarn.classpath";
  public static final String YARN_APP_CLASSPATH = "provisioning.yarn.app.classpath";
  public static final String YARN_APP_CLASSPATH_PREFIX = "provisioning.yarn.app.classpath-prefix";
  public static final String EC2_EFS_MOUNT_TARGET_IP_ADDRESS =
      "provisioning.ec2.efs.mountTargetIpAddress";
  public static final String MIGRATION_ENABLED = "provisioning.migration.enabled";

  // netty options
  public static final String NETTY_REFLECTIONS_ACCESSIBLE = "io.netty.tryReflectionSetAccessible";

  /** Path where debug options are located */
  public static final String DEBUG_OPTIONS = "debug";

  // to enable remote debugging of the DremioDaemon running in YARN container
  public static final String DEBUG_YARN_ENABLED = "debug.yarnremote.enabled";
  public static final String DEBUG_ENABLED_BOOL = "debug.enabled";
  public static final String DEBUG_PREPOPULATE_BOOL = "debug.prepopulate";
  public static final String DEBUG_AUTOPORT_BOOL = "debug.autoPort";
  public static final String DEBUG_SINGLE_NODE_BOOL = "debug.singleNode";
  public static final String DEBUG_ALLOW_TEST_APIS_BOOL = "debug.allowTestApis";
  public static final String DEBUG_USE_MEMORY_STRORAGE_BOOL = "debug.useMemoryStorage";
  public static final String DEBUG_FORCE_REMOTE_BOOL = "debug.forceRemote";
  public static final String DEBUG_ADD_DEFAULT_USER = "debug.addDefaultUser";
  public static final String DEBUG_ALLOW_NEWER_KVSTORE = "debug.allowNewerKVStore";
  public static final String DEBUG_DISABLE_MASTER_ELECTION_SERVICE_BOOL =
      "debug.master.election.disabled";

  public static final String DEBUG_DIST_ASYNC_ENABLED = "debug.dist.async.enabled";
  public static final String DEBUG_DIST_CACHING_ENABLED = "debug.dist.caching.enabled";
  public static final String DEBUG_DIST_MAX_CACHE_SPACE_PERCENT =
      "debug.dist.max.cache.space.percent";
  public static final String DEBUG_UPLOADS_ASYNC_ENABLED = "debug.uploads.async.enabled";
  public static final String DEBUG_SUPPORT_ASYNC_ENABLED = "debug.support.async.enabled";
  public static final String DEBUG_JOBS_ASYNC_ENABLED = "debug.results.async.enabled";
  public static final String DEBUG_SCRATCH_ASYNC_ENABLED = "debug.scratch.async.enabled";
  public static final String DEBUG_PROFILE_ASYNC_ENABLED = "debug.profile.async.enabled";
  public static final String DEBUG_DOWNLOAD_ASYNC_ENABLED = "debug.download.async.enabled";
  public static final String DEBUG_METADATA_ASYNC_ENABLED = "debug.metadata.async.enabled";
  public static final String DEBUG_LOGS_ASYNC_ENABLED = "debug.logs.async.enabled";
  public static final String DEBUG_GANDIVA_CACHE_ASYNC_ENABLED =
      "debug.gandiva_cache.async.enabled";
  public static final String DEBUG_DIST_S3_FILE_STATUS_CHECK =
      "debug.dist.s3_file_status_check.enabled";
  public static final String DEBUG_SYSTEM_ICEBERG_TABLES_STORAGE_ASYNC_ENABLED =
      "debug.system_iceberg_tables.async.enabled";
  public static final String FABRIC_MEMORY_RESERVATION = "services.fabric.memory.reservation";

  public static final String SSL_ENABLED = "enabled";
  public static final String SSL_KEY_STORE_TYPE = "keyStoreType";
  public static final String SSL_KEY_STORE_PATH = "keyStore";
  public static final String SSL_KEY_STORE_PASSWORD = "keyStorePassword";
  public static final String SSL_KEY_PASSWORD = "keyPassword";
  public static final String SSL_TRUST_STORE_TYPE = "trustStoreType";
  public static final String SSL_TRUST_STORE_PATH = "trustStore";
  public static final String SSL_TRUST_STORE_PASSWORD = "trustStorePassword";
  public static final String SSL_DISABLE_HOST_VERIFICATION = "disableHostVerification";
  public static final String SSL_AUTO_GENERATED_CERTIFICATE = "auto-certificate.enabled";

  // web SSL configuration
  public static final String WEB_SSL_PREFIX = "services.coordinator.web.ssl.";

  // datastore
  public static final String DATASTORE_TYPE = "services.datastore.type";
  public static final String DATASTORE_CONFIG = "services.datastore.config";

  // liveness
  public static final String LIVENESS_ENABLED = "services.web-admin.enabled";
  public static final String LIVENESS_PORT = "services.web-admin.port";
  public static final String LIVENESS_HOST = "services.web-admin.host";

  // yarn watchdog
  public static final String POLL_TIMEOUT_MS = "provisioning.yarn.watchdog.poll.timeout";
  public static final String POLL_INTERVAL_MS = "provisioning.yarn.watchdog.poll.interval";
  public static final String MISSED_POLLS_BEFORE_KILL =
      "provisioning.yarn.watchdog.missed.polls.before.kill";
  public static final String MAX_KILL_ATTEMPTS = "provisioning.yarn.watchdog.max.kill.attempts";
  public static final String KILL_REATTEMPT_INTERVAL_MS =
      "provisioning.yarn.watchdog.kill.reattempt.interval";
  public static final String YARN_CERTIFICATE_VALIDATION_ENABLED =
      "provisioning.yarn.nodemanager.certificate-validation.enabled";

  public static final String REMOTE_DATASTORE_RPC_TIMEOUT_SECS =
      "debug.remote.datastore.rpc_timeout_seconds";

  // Coordinator Security Permissions
  public static final String PERMISSION_LOCAL_USERS_CREATE_ENABLED =
      "services.coordinator.security.permission.local-users.create.enabled";

  // gRCP connection config
  public static final String DREMIO_TUNING_GRPC_IDLE_TIMEOUT_SECONDS =
      "dremio.tuning.grpc.idle-timeout-seconds";

  public static final String DREMIO_TERMINATION_GRACE_PERIOD_SECONDS =
      "dremio.termination_grace_period_seconds";

  private final Config unresolved;
  private final Config reference;
  private final SabotConfig sabot;
  private final String thisNode;
  private final boolean isMasterlessEnabled;

  /**
   * We maintain both the reference and the unresolved data so any withValue layering can be done
   * against unresolved values.
   *
   * @param unresolved
   * @param reference
   */
  private DremioConfig(SabotConfig sabot, Config unresolved, Config reference, String thisNode) {
    super(inverseMerge(unresolved, reference));

    this.unresolved = unresolved;
    this.reference = reference;
    this.sabot = sabot;
    this.thisNode = thisNode;
    this.isMasterlessEnabled = Boolean.getBoolean("dremio_masterless");
    check();
  }

  private void check() {
    final Config resolvedReference = reference.resolve();

    // make sure types are right
    getInnerConfig().checkValid(resolvedReference);

    // make sure we don't have any extra paths. these are typically typos.
    checkForInvalidPaths(resolvedReference);

    // check if dist-store is misconfigured.
    checkForMisconfiguredDistStore();
  }

  private void checkForInvalidPaths(Config resolvedReference) {
    final List<String> invalidPaths = new ArrayList<>();

    for (Entry<String, ConfigValue> entry : getInnerConfig().entrySet()) {
      if (DEPRECATED_PATHS.contains(entry.getKey())) {
        logger.warn(
            "Property [{}] is deprecated. Please remove it from the conf file [dremio.conf].",
            entry.getKey());
        continue;
      }

      if (!resolvedReference.hasPath(entry.getKey())) {
        invalidPaths.add(entry.getKey());
      }
    }

    if (!invalidPaths.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("Failure reading configuration file. The following properties were invalid:\n");
      for (String s : invalidPaths) {
        sb.append("\t");
        sb.append(s);
        sb.append("\n");
      }

      throw new RuntimeException(sb.toString());
    }
  }

  private void checkForMisconfiguredDistStore() {
    // Check for PDFS usage.
    final String distStorePath = getString(DIST_WRITE_PATH_STRING);
    if (distStorePath.startsWith(LEGACY_PDFS_SCHEME + SCHEME_SEPARATOR)) {
      logger.warn("PDFS is not supported as a distributed store for Dremio Software.");
      return;
    }

    // Check for un-configured dist-store's in a multi-node environment.
    if (getBoolean(ENABLE_COORDINATOR_BOOL)
        && getBoolean(ENABLE_MASTER_BOOL)
        && getBoolean(ENABLE_EXECUTOR_BOOL)) {
      return;
    }

    final String localStorePrefix =
        FILE_SCHEME + SCHEME_SEPARATOR + getString(LOCAL_WRITE_PATH_STRING);
    if (distStorePath.startsWith(localStorePrefix)) {
      logger.warn("The distributed store cannot be a local path - update the paths.dist property.");
    }
  }

  private static String determineNode() {

    try (TimedBlock bh = Timer.time("getCanonicalHostName")) {
      return InetAddress.getLocalHost().getCanonicalHostName();
    } catch (UnknownHostException ex) {
      throw new RuntimeException("Failure retrieving hostname from node. Check hosts file.", ex);
    }
  }

  @Override
  public DremioConfig withValue(String path, ConfigValue value) {
    return new DremioConfig(sabot, unresolved.withValue(path, value), reference, thisNode);
  }

  public DremioConfig withSabotValue(String path, ConfigValue value) {
    return new DremioConfig(sabot.withValue(path, value), unresolved, reference, thisNode);
  }

  public DremioConfig withSabotValue(String path, Object value) {
    return withSabotValue(path, ConfigValueFactory.fromAnyRef(value));
  }

  public SabotConfig getSabotConfig() {
    return sabot;
  }

  public boolean isMasterlessEnabled() {
    return isMasterlessEnabled;
  }

  private static Config inverseMerge(Config userConfig, Config fallback) {
    return userConfig.withFallback(fallback).resolve();
  }

  public DremioConfig withValue(String path, Object value) {
    return withValue(path, ConfigValueFactory.fromAnyRef(value));
  }

  public URI getURI(String path) {
    try {
      return new URI(getString(path));
    } catch (URISyntaxException e) {
      throw Throwables.propagate(e);
    }
  }

  public static DremioConfig create() {
    return create(null);
  }

  public static DremioConfig create(final URL userConfigPath) {
    return create(userConfigPath, SabotConfig.create());
  }

  public static DremioConfig create(final URL userConfigPath, SabotConfig sabotConfig) {
    Config reference = null;

    final ClassLoader[] classLoaders = ClasspathHelper.classLoaders();
    for (ClassLoader classLoader : classLoaders) {
      final URL configUrl = classLoader.getResource(REFERENCE_CONFIG);
      if (configUrl == null) {
        continue;
      }
      Preconditions.checkArgument(
          reference == null, "Attempted to load more than one reference configuration.");
      reference = ConfigFactory.parseResources(classLoader, REFERENCE_CONFIG);
    }

    Preconditions.checkNotNull(reference, "Unable to find the reference configuration.");

    Config userConfig = null;

    if (userConfigPath == null) {

      for (ClassLoader classLoader : classLoaders) {
        final URL configUrl = classLoader.getResource(DEFAULT_USER_CONFIG);
        if (configUrl == null) {
          continue;
        }
        Preconditions.checkArgument(
            userConfig == null, "Attempted to load more than one user configuration.");
        userConfig = ConfigFactory.parseResources(classLoader, DEFAULT_USER_CONFIG);
      }

    } else {

      userConfig =
          ConfigFactory.parseURL(
              userConfigPath, ConfigParseOptions.defaults().setAllowMissing(false));
    }

    final Config effective;

    if (userConfig != null) {

      effective = userConfig;
    } else {
      effective = reference;
    }

    final Config skinned = applySystemProperties(applyLegacySystemProperties(effective), reference);

    return new DremioConfig(sabotConfig, skinned, reference, determineNode());
  }

  private static Config setSystemProperty(Config config, String sysProp, String configProp) {
    String systemProperty = System.getProperty(sysProp);
    if (systemProperty != null) {
      config = config.withValue(configProp, ConfigValueFactory.fromAnyRef(systemProperty));
      logger.info(
          "Applying provided leagcy system property to config: -D{}={}",
          configProp,
          systemProperty);
    }
    return config;
  }

  /** Remove this once all scripts stop referencing these old properties. */
  @Deprecated
  private static Config applyLegacySystemProperties(Config config) {
    // legacy stuff for now.
    config = setSystemProperty(config, "dremd.write", LOCAL_WRITE_PATH_STRING);
    config = setSystemProperty(config, "dremio_autoPort", DEBUG_AUTOPORT_BOOL);
    config = setSystemProperty(config, "dac_prepopulate", DEBUG_PREPOPULATE_BOOL);
    config = setSystemProperty(config, "dremio_allowTestApis", DEBUG_ALLOW_TEST_APIS_BOOL);
    config = setSystemProperty(config, "dremd.localPort", SERVER_PORT_INT);
    config = setSystemProperty(config, "dremd.httpPort", WEB_PORT_INT);

    if ("LOCAL".equalsIgnoreCase(System.getProperty("dremd.mode"))) {
      config = config.withValue(DEBUG_SINGLE_NODE_BOOL, ConfigValueFactory.fromAnyRef(true));
      logger.info("Applying provided leagcy system property to config: -Ddremd.mode=LOCAL");
    }

    return config;
  }

  public String getThisNode() {
    return thisNode;
  }

  private static Config applySystemProperties(Config config, Config reference) {
    for (Entry<String, ConfigValue> entry : reference.entrySet()) {
      String property = System.getProperty(entry.getKey());
      if (property != null && !property.isEmpty()) {
        // hack to deal with array of strings
        if (property.startsWith("[") && property.endsWith("]")) {
          property = property.substring(1, property.length() - 1);
          if (property.trim().isEmpty()) {
            continue;
          }
          String[] strings = property.split(",");
          if (strings != null && strings.length > 0) {
            List<String> listStrings = new ArrayList<>();
            for (String str : strings) {
              listStrings.add(str.trim());
            }
            config = config.withValue(entry.getKey(), ConfigValueFactory.fromAnyRef(listStrings));
          }
        } else {
          config = config.withValue(entry.getKey(), ConfigValueFactory.fromAnyRef(property));
        }
        logger.info(
            "Applying provided system property to config: -D{}={}", entry.getKey(), property);
      }
    }
    return config;
  }

  public static Path getPluginsRootPath() {
    final String pluginsDir = System.getProperty(PLUGINS_ROOT_PATH_PROPERTY);
    if (pluginsDir != null) {
      return Paths.get(pluginsDir);
    }

    logger.debug("The system property {} is not set", PLUGINS_ROOT_PATH_PROPERTY);
    return Optional.ofNullable(System.getenv("DREMIO_HOME"))
        .map(v -> Paths.get(v, "plugins"))
        .orElseGet(
            () -> {
              logger.debug("The environment variable DREMIO_HOME is not set.");
              return Paths.get(".");
            });
  }
}
