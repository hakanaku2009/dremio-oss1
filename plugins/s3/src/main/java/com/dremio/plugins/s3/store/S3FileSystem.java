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
package com.dremio.plugins.s3.store;

import static com.dremio.common.utils.PathUtils.removeLeadingSlash;
import static com.dremio.exec.ExecConstants.ENABLE_STORE_PARQUET_ASYNC_TIMESTAMP_CHECK;
import static com.dremio.exec.ExecConstants.S3_NATIVE_ASYNC_CLIENT;
import static com.dremio.plugins.Constants.DREMIO_ENABLE_BUCKET_DISCOVERY;
import static com.dremio.plugins.s3.store.S3StoragePlugin.NONE_PROVIDER;
import static org.apache.hadoop.fs.s3a.Constants.ALLOW_REQUESTER_PAYS;
import static org.apache.hadoop.fs.s3a.Constants.AWS_REGION;
import static org.apache.hadoop.fs.s3a.Constants.CENTRAL_ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.SECURE_CONNECTIONS;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.Region;
import com.dremio.common.AutoCloseables;
import com.dremio.common.concurrent.NamedThreadFactory;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.util.Retryer;
import com.dremio.exec.hadoop.DremioHadoopUtils;
import com.dremio.exec.hadoop.MayProvideAsyncStream;
import com.dremio.exec.store.dfs.DremioFileSystemCache;
import com.dremio.exec.store.dfs.FileSystemConf;
import com.dremio.io.AsyncByteReader;
import com.dremio.io.FSOutputStream;
import com.dremio.plugins.util.AwsCredentialProviderUtils;
import com.dremio.plugins.util.CloseableRef;
import com.dremio.plugins.util.CloseableResource;
import com.dremio.plugins.util.ContainerAccessDeniedException;
import com.dremio.plugins.util.ContainerFileSystem;
import com.dremio.plugins.util.ContainerNotFoundException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.s3a.AWSCredentialProviderList;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.hadoop.fs.s3a.DefaultS3ClientFactory;
import org.apache.hadoop.fs.s3a.S3AUtils;
import org.apache.hadoop.fs.s3a.S3ClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3BaseClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

/** FileSystem implementation that treats multiple s3 buckets as a unified namespace */
public class S3FileSystem extends ContainerFileSystem implements MayProvideAsyncStream {
  public static final String S3_PERMISSION_ERROR_MSG = "Access was denied by S3";
  public static final String COMPATIBILITY_MODE = "dremio.s3.compat";
  static final String REGION_OVERRIDE = "dremio.s3.region";

  private static final Logger logger = LoggerFactory.getLogger(S3FileSystem.class);
  private static final String S3_URI_SCHEMA = "s3a://";
  private static final URI S3_URI =
      URI.create("s3a://aws"); // authority doesn't matter here, it is just to avoid exceptions
  private static final String S3_ENDPOINT_END = ".amazonaws.com";
  private static final String S3_CN_ENDPOINT_END = S3_ENDPOINT_END + ".cn";
  private static final ExecutorService threadPool =
      Executors.newCachedThreadPool(new NamedThreadFactory("s3-async-read-"));

  private final Retryer retryer =
      Retryer.newBuilder()
          .retryIfExceptionOfType(SdkClientException.class)
          .retryIfExceptionOfType(software.amazon.awssdk.core.exception.SdkClientException.class)
          .setWaitStrategy(Retryer.WaitStrategy.EXPONENTIAL, 250, 2500)
          .setMaxRetries(10)
          .build();

  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final LoadingCache<String, CloseableRef<S3Client>> syncClientCache =
      CacheBuilder.newBuilder()
          .expireAfterAccess(1, TimeUnit.HOURS)
          .removalListener(
              notification ->
                  AutoCloseables.close(
                      RuntimeException.class, (AutoCloseable) notification.getValue()))
          .build(
              new CacheLoader<String, CloseableRef<S3Client>>() {
                @Override
                public CloseableRef<S3Client> load(String bucket) {
                  final S3Client syncClient =
                      syncConfigClientBuilder(S3Client.builder(), bucket).build();
                  return new CloseableRef<>(syncClient);
                }
              });

  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final LoadingCache<String, CloseableRef<S3AsyncClient>> asyncClientCache =
      CacheBuilder.newBuilder()
          .expireAfterAccess(1, TimeUnit.HOURS)
          .removalListener(
              notification ->
                  AutoCloseables.close(
                      RuntimeException.class, (AutoCloseable) notification.getValue()))
          .build(
              new CacheLoader<String, CloseableRef<S3AsyncClient>>() {
                @Override
                public CloseableRef<S3AsyncClient> load(String bucket) {
                  final S3AsyncClient asyncClient =
                      asyncConfigClientBuilder(S3AsyncClient.builder(), bucket).build();
                  return new CloseableRef<>(asyncClient);
                }
              });

  /** Get (or create if one doesn't already exist) an async client for accessing a given bucket */
  private CloseableRef<S3AsyncClient> getAsyncClient(String bucket) throws IOException {
    try {
      return asyncClientCache.get(bucket);
    } catch (ExecutionException | SdkClientException e) {
      if (e.getCause() != null && e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException(
          String.format("Unable to create an async S3 client for bucket %s", bucket), e);
    }
  }

  @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
  private final LoadingCache<S3ClientKey, CloseableResource<AmazonS3>> clientCache =
      CacheBuilder.newBuilder()
          .expireAfterAccess(1, TimeUnit.HOURS)
          .maximumSize(20)
          .removalListener(
              notification ->
                  AutoCloseables.close(
                      RuntimeException.class, (AutoCloseable) notification.getValue()))
          .build(
              new CacheLoader<S3ClientKey, CloseableResource<AmazonS3>>() {
                @Override
                public CloseableResource<AmazonS3> load(S3ClientKey clientKey) throws Exception {
                  logger.debug("Opening S3 client connection for {}", clientKey);
                  return createS3V1Client(clientKey.s3Config);
                }
              });

  private S3ClientKey clientKey;
  private final DremioFileSystemCache fsCache = new DremioFileSystemCache();
  private boolean useWhitelistedBuckets;
  private boolean useBucketDiscovery = true;

  public S3FileSystem() {
    super(
        FileSystemConf.CloudFileSystemScheme.S3_FILE_SYSTEM_SCHEME.getScheme(),
        "bucket",
        ELIMINATE_PARENT_DIRECTORY);
  }

  // Work around bug in s3a filesystem where the parent directory is included in list. Similar to
  // HADOOP-12169
  private static final Predicate<CorrectableFileStatus> ELIMINATE_PARENT_DIRECTORY =
      (input -> {
        final FileStatus status = input.getStatus();
        if (!status.isDirectory()) {
          return true;
        }
        return !Path.getPathWithoutSchemeAndAuthority(input.getPathWithoutContainerName())
            .equals(Path.getPathWithoutSchemeAndAuthority(status.getPath()));
      });

  public static CloseableResource<AmazonS3> createS3V1Client(Configuration s3Config)
      throws IOException {
    DefaultS3ClientFactory clientFactory = new DefaultS3ClientFactory();
    clientFactory.setConf(s3Config);
    final AWSCredentialProviderList credentialsProvider =
        S3AUtils.createAWSCredentialProviderSet(S3_URI, s3Config);
    // Use builder pattern for S3Client(AWS SDK1.x) initialization.
    S3ClientFactory.S3ClientCreationParameters parameters =
        new S3ClientFactory.S3ClientCreationParameters()
            .withCredentialSet(credentialsProvider)
            .withPathStyleAccess(isPathStyleAccessEnabled(s3Config))
            .withEndpoint(buildEndpoint(s3Config));
    final AmazonS3 s3Client = clientFactory.createS3Client(S3_URI, parameters);

    final AutoCloseable closeableCredProvider =
        (credentialsProvider instanceof AutoCloseable) ? credentialsProvider : () -> {};
    final Consumer<AmazonS3> closeFunc =
        s3 ->
            AutoCloseables.close(
                RuntimeException.class, () -> s3.shutdown(), closeableCredProvider);
    final CloseableResource<AmazonS3> closeableS3 = new CloseableResource<>(s3Client, closeFunc);
    return closeableS3;
  }

  private static String buildEndpoint(Configuration s3Config) {
    String endpointOverride = s3Config.get(ENDPOINT);
    if (StringUtils.isNotEmpty(endpointOverride)) {
      return endpointOverride;
    }

    String regionStr = s3Config.getTrimmed(REGION_OVERRIDE);
    if (StringUtils.isNotEmpty(regionStr)) {
      return buildRegionEndpoint(CENTRAL_ENDPOINT, regionStr);
    }

    return CENTRAL_ENDPOINT;
  }

  @Override
  protected void setup(Configuration conf) throws IOException {
    clientKey = S3ClientKey.create(conf);
    useWhitelistedBuckets = !conf.get(S3StoragePlugin.WHITELISTED_BUCKETS, "").isEmpty();
    useBucketDiscovery = conf.getBoolean(DREMIO_ENABLE_BUCKET_DISCOVERY, true);
    if (!NONE_PROVIDER.equals(conf.get(Constants.AWS_CREDENTIALS_PROVIDER))
        && !conf.getBoolean(COMPATIBILITY_MODE, false)) {
      verifyCredentials(conf);
    }
  }

  /** Checks if credentials are valid using GetCallerIdentity API call. */
  protected void verifyCredentials(Configuration conf) throws RuntimeException {
    final AwsCredentialsProvider awsCredentialsProvider;
    try {
      awsCredentialsProvider = getAsync2Provider(conf);
    } catch (IOException e) {
      throw new RuntimeException("Credential lookup failed.");
    }
    final StsClientBuilder stsClientBuilder =
        StsClient.builder()
            // Note that AWS SDKv2 client will close the credentials provider if needed when the
            // client is closed
            .credentialsProvider(awsCredentialsProvider)
            .region(getAWSRegionFromConfigurationOrDefault(conf));
    try (StsClient stsClient = stsClientBuilder.build()) {
      retryer.call(
          () -> {
            GetCallerIdentityRequest request = GetCallerIdentityRequest.builder().build();
            stsClient.getCallerIdentity(request);
            return true;
          });
    } catch (Retryer.OperationFailedAfterRetriesException e) {
      throw new RuntimeException("Credential Verification failed.", e);
    }
  }

  @Override
  public RemoteIterator<LocatedFileStatus> listFiles(Path f, boolean recursive)
      throws FileNotFoundException, IOException {
    return super.listFiles(f, recursive);
  }

  @Override
  protected Stream<ContainerCreator> getContainerCreators() throws IOException {
    try (CloseableResource<AmazonS3> s3Ref = getS3V1Client()) {
      final AmazonS3 s3 = s3Ref.getResource();
      Stream<String> buckets =
          getBucketNamesFromConfigurationProperty(S3StoragePlugin.EXTERNAL_BUCKETS);
      if (!NONE_PROVIDER.equals(getConf().get(Constants.AWS_CREDENTIALS_PROVIDER))
          && useBucketDiscovery) {
        if (!useWhitelistedBuckets) {
          // if we have authentication to access S3, add in owner buckets.
          buckets = Stream.concat(buckets, s3.listBuckets().stream().map(Bucket::getName));
        } else {
          // Only add the buckets provided in the configuration.
          buckets =
              Stream.concat(
                  buckets,
                  getBucketNamesFromConfigurationProperty(S3StoragePlugin.WHITELISTED_BUCKETS));
        }
      }
      return buckets
          .distinct() // Remove duplicate bucket names.S3FileSystem.java
          .map(input -> new BucketCreator(getConf(), input));
    } catch (Exception e) {
      logger.error("Error while listing S3 buckets", e);
      throw new IOException(e);
    }
  }

  private Stream<String> getBucketNamesFromConfigurationProperty(
      String bucketConfigurationProperty) {
    String bucketList = getConf().get(bucketConfigurationProperty, "");
    return Arrays.stream(bucketList.split(","))
        .map(String::trim)
        .filter(input -> !Strings.isNullOrEmpty(input));
  }

  @Override
  protected ContainerHolder getUnknownContainer(String containerName) throws IOException {
    // Per docs, if invalid security credentials are used to execute
    // AmazonS3#doesBucketExist method, the client is not able to distinguish
    // between bucket permission errors and invalid credential errors, and the
    // method could return an incorrect result.

    // Coordinator node gets the new bucket information by overall refresh in the containerMap
    // This method is implemented only for the cases when executor is falling behind.

    // In case useBucketDiscovery is turned off, we avoid doing is check as we can't expect
    // to have permissions to carry out listings in or existence checks of buckets.
    if (useBucketDiscovery) {
      boolean containerFound = false;
      try (CloseableResource<AmazonS3> s3Ref = getS3V1Client()) {
        final AmazonS3 s3 = s3Ref.getResource();
        // getBucketLocation ensures that given user account has permissions for the bucket.
        if (s3.doesBucketExistV2(containerName)) {
          // Listing one object to ascertain read permissions on the bucket.
          final ListObjectsV2Request req =
              new ListObjectsV2Request()
                  .withMaxKeys(1)
                  .withRequesterPays(isRequesterPays())
                  .withEncodingType("url")
                  .withBucketName(containerName);
          containerFound =
              s3.listObjectsV2(req)
                  .getBucketName()
                  .equals(containerName); // Exception if this account doesn't have access.
        }
      } catch (AmazonS3Exception e) {
        if (e.getMessage().contains("Access Denied")) {
          // Ignorable because user doesn't have permissions. We'll omit this case.
          throw new ContainerAccessDeniedException("aws-bucket", containerName, e);
        }
        throw new ContainerNotFoundException("Error while looking up bucket " + containerName, e);
      } catch (Exception e) {
        logger.error("Error while looking up bucket " + containerName, e);
      }
      logger.debug("Unknown container '{}' found ? {}", containerName, containerFound);
      if (!containerFound) {
        throw new ContainerNotFoundException("Bucket [" + containerName + "] not found.");
      }
    }
    return new BucketCreator(getConf(), containerName).toContainerHolder();
  }

  private software.amazon.awssdk.regions.Region getAWSBucketRegion(String bucketName)
      throws SdkClientException {
    try (CloseableResource<AmazonS3> s3Ref = getS3V1Client()) {
      final AmazonS3 s3 = s3Ref.getResource();
      final String awsRegionName =
          Region.fromValue(s3.getBucketLocation(bucketName)).toAWSRegion().getName();
      return software.amazon.awssdk.regions.Region.of(awsRegionName);
    } catch (SdkClientException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Error while fetching bucket region", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean supportsAsync() {
    return true;
  }

  @Override
  public AsyncByteReader getAsyncByteReader(Path path, String version, Map<String, String> options)
      throws IOException {
    final String bucket = DremioHadoopUtils.getContainerName(path);
    String pathStr = DremioHadoopUtils.pathWithoutContainer(path).toString();
    // The AWS HTTP client re-encodes a leading slash resulting in invalid keys, so strip them.
    pathStr = (pathStr.startsWith("/")) ? pathStr.substring(1) : pathStr;
    boolean ssecUsed = isSsecUsed();
    String sseCustomerKey = getCustomerSSEKey(ssecUsed);
    // If proxy is enabled.
    // Use SyncClient to do async byte read.
    // Once AWS-SDK-2.x upgrade happened with aws-sdk-2.17+, This can be handled with S3AsyncClient.
    // https://dremio.atlassian.net/browse/DX-49510?focusedCommentId=545929
    if (ApacheHttpConnectionUtil.isProxyEnabled(getConf())
        || "false".equals(options.get(S3_NATIVE_ASYNC_CLIENT.getOptionName()))) {
      return new S3AsyncByteReaderUsingSyncClient(
          getSyncClient(bucket),
          bucket,
          pathStr,
          version,
          isRequesterPays(),
          ssecUsed,
          sseCustomerKey,
          "true".equals(options.get(ENABLE_STORE_PARQUET_ASYNC_TIMESTAMP_CHECK.getOptionName())));
    }
    return new S3AsyncByteReader(
        getAsyncClient(bucket),
        bucket,
        pathStr,
        version,
        isRequesterPays(),
        ssecUsed,
        sseCustomerKey,
        "true".equals(options.get(ENABLE_STORE_PARQUET_ASYNC_TIMESTAMP_CHECK.getOptionName())));
  }

  @Override
  public long getTTL(com.dremio.io.file.FileSystem fileSystem, com.dremio.io.file.Path path) {
    Path hadoopPath = new org.apache.hadoop.fs.Path(path.toString());
    final String bucket = DremioHadoopUtils.getContainerName(hadoopPath);
    final String onlyPath =
        removeLeadingSlash(DremioHadoopUtils.pathWithoutContainer(hadoopPath).toString());
    HeadObjectRequest reqHeadObject =
        HeadObjectRequest.builder().bucket(bucket).key(onlyPath).build();

    final HeadObjectResponse[] respHeadObject = new HeadObjectResponse[1];
    try (FSOutputStream fos = fileSystem.create(fileSystem.canonicalizePath(path), true);
        CloseableRef<S3AsyncClient> asyncClient = getAsyncClient(bucket)) {
      fos.close();

      retryer.call(
          () -> {
            respHeadObject[0] = asyncClient.acquireRef().headObject(reqHeadObject).get();
            return true;
          });
      fileSystem.delete(path, false);
    } catch (Exception ex) {
      logger.info("Failed to get head object for {}", path, ex);
      return -1;
    }

    if (respHeadObject[0] == null) {
      logger.info("Unable to retrieve head object for {}", path.getParent());
      return -1;
    }

    if (respHeadObject[0].expiration() == null) {
      logger.info("No expiration lifecycle rules set for {}", path.getParent());
      return -1;
    }

    String[] parts = respHeadObject[0].expiration().split("\"");
    if (parts.length != 4) {
      logger.error("Unexpected expiration metadata:" + respHeadObject[0].expiration());
      return -1;
    }

    logger.info("TTL based on{}{}", parts[2], parts[3]);
    Instant expireInstant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(parts[1]));

    return Duration.between(respHeadObject[0].lastModified(), expireInstant).toDays();
  }

  private CloseableRef<S3Client> getSyncClient(String bucket) throws IOException {
    try {
      return syncClientCache.get(bucket);
    } catch (ExecutionException | SdkClientException e) {
      final Throwable cause = e.getCause();
      final Throwable toChain;
      if (cause == null) {
        toChain = e;
      } else {
        Throwables.throwIfInstanceOf(cause, UserException.class);
        Throwables.throwIfInstanceOf(cause, IOException.class);

        toChain = cause;
      }

      throw new IOException(
          String.format("Unable to create a sync S3 client for bucket %s", bucket), toChain);
    }
  }

  @Override
  public void close() throws IOException {
    // invalidating cache of clients
    // all clients (including this.s3) will be closed just before being evicted by GC.
    AutoCloseables.close(
        IOException.class,
        super::close,
        fsCache::closeAll,
        () -> invalidateCache(syncClientCache),
        () -> invalidateCache(asyncClientCache),
        () -> invalidateCache(clientCache));
  }

  private static void invalidateCache(Cache<?, ?> cache) {
    cache.invalidateAll();
    cache.cleanUp();
  }

  // AwsCredentialsProvider might also implement SdkAutoCloseable
  // Make sure to close if using directly (or let client close it for you).
  @VisibleForTesting
  protected AwsCredentialsProvider getAsync2Provider(Configuration config) throws IOException {
    return AwsCredentialProviderUtils.getCredentialsProvider(config);
  }

  private class BucketCreator extends ContainerCreator {
    private final Configuration parentConf;
    private final String bucketName;

    BucketCreator(Configuration parentConf, String bucketName) {
      super();
      this.parentConf = parentConf;
      this.bucketName = bucketName;
    }

    @Override
    protected String getName() {
      return bucketName;
    }

    @Override
    protected ContainerHolder toContainerHolder() throws IOException {

      return new ContainerHolder(
          bucketName,
          new FileSystemSupplier() {
            @Override
            public FileSystem create() throws IOException {
              final String targetEndpoint;
              Optional<String> endpoint = getEndpoint(getConf());

              if (endpoint.isPresent() && (isCompatMode() || getConf().get(AWS_REGION) != null)) {
                // if this is compatibility mode and we have an endpoint, just use that.
                targetEndpoint = endpoint.get();
              } else if (!useBucketDiscovery) {
                targetEndpoint = null;
              } else {
                try (CloseableResource<AmazonS3> s3Ref = getS3V1Client()) {
                  final AmazonS3 s3 = s3Ref.getResource();
                  final String bucketRegion = s3.getBucketLocation(bucketName);
                  final String fallbackEndpoint =
                      endpoint.orElseGet(
                          () ->
                              String.format(
                                  "%ss3.%s.amazonaws.com", getHttpScheme(getConf()), bucketRegion));

                  String regionEndpoint;
                  try {
                    regionEndpoint = buildRegionEndpoint(fallbackEndpoint, bucketRegion);
                  } catch (IllegalArgumentException iae) {
                    // try heuristic mapping if not found
                    regionEndpoint = fallbackEndpoint;
                    logger.warn(
                        "Unknown or unmapped region {} for bucket {}. Will use following endpoint: {}",
                        bucketRegion,
                        bucketName,
                        regionEndpoint);
                  }
                  // it could be null because no mapping from Region to aws region or there is no
                  // such region is the map of endpoints
                  // not sure if latter is possible
                  if (regionEndpoint == null) {
                    logger.error(
                        "Could not get AWSRegion for bucket {}. Will use following fs.s3a.endpoint: {} ",
                        bucketName,
                        fallbackEndpoint);
                  }
                  targetEndpoint = (regionEndpoint != null) ? regionEndpoint : fallbackEndpoint;

                } catch (AmazonS3Exception aex) {
                  throw UserException.permissionError(aex)
                      .message(translateForbiddenMessage(bucketName, aex))
                      .build(logger);
                } catch (Exception e) {
                  logger.error("Error while creating container holder", e);
                  throw new RuntimeException(e);
                }
              }

              String location = S3_URI_SCHEMA + bucketName + "/";
              final Configuration bucketConf = new Configuration(parentConf);
              if (targetEndpoint != null) {
                bucketConf.set(ENDPOINT, targetEndpoint);
              }
              return fsCache.get(new Path(location).toUri(), bucketConf, S3ClientKey.UNIQUE_PROPS);
            }
          });
    }
  }

  private static String buildRegionEndpoint(String fallbackEndpoint, String regionString) {
    String regionEndpoint = fallbackEndpoint;
    Region region = Region.fromValue(regionString);
    com.amazonaws.regions.Region awsRegion = region.toAWSRegion();
    if (awsRegion != null) {
      regionEndpoint = awsRegion.getServiceEndpoint("s3");
    }
    return regionEndpoint;
  }

  /** Convert 403 Forbidden HTTP status with user-actionable messages. */
  @VisibleForTesting
  String translateForbiddenMessage(String bucketName, AmazonS3Exception s3Exception) {
    return String.format(
        "S3 request failed on resource %s - %s. (HTTP %d:%s) ",
        bucketName,
        s3Exception.getErrorMessage(),
        s3Exception.getStatusCode(),
        s3Exception.getErrorCode());
  }

  /** Key to identify a connection. */
  public static final class S3ClientKey {

    /**
     * List of properties unique to a connection. This works in conjuction with {@link
     * DefaultS3ClientFactory} implementation.
     */
    private static final List<String> UNIQUE_PROPS =
        ImmutableList.of(
            Constants.ACCESS_KEY,
            Constants.SECRET_KEY,
            Constants.SECURE_CONNECTIONS,
            Constants.ENDPOINT,
            Constants.AWS_CREDENTIALS_PROVIDER,
            Constants.MAXIMUM_CONNECTIONS,
            Constants.MAX_ERROR_RETRIES,
            Constants.ESTABLISH_TIMEOUT,
            Constants.SOCKET_TIMEOUT,
            Constants.SOCKET_SEND_BUFFER,
            Constants.SOCKET_RECV_BUFFER,
            Constants.SIGNING_ALGORITHM,
            Constants.USER_AGENT_PREFIX,
            Constants.PROXY_HOST,
            Constants.PROXY_PORT,
            Constants.PROXY_DOMAIN,
            Constants.PROXY_USERNAME,
            Constants.PROXY_PASSWORD,
            Constants.PROXY_WORKSTATION,
            Constants.PATH_STYLE_ACCESS,
            S3FileSystem.COMPATIBILITY_MODE,
            S3FileSystem.REGION_OVERRIDE,
            Constants.ASSUMED_ROLE_ARN,
            Constants.ASSUMED_ROLE_CREDENTIALS_PROVIDER,
            Constants.ALLOW_REQUESTER_PAYS,
            Constants.AWS_REGION);

    private final Configuration s3Config;

    public static S3ClientKey create(final Configuration fsConf) {
      return new S3ClientKey(fsConf);
    }

    private S3ClientKey(final Configuration s3Config) {
      this.s3Config = s3Config;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      S3ClientKey that = (S3ClientKey) o;

      for (String prop : UNIQUE_PROPS) {
        if (!Objects.equals(s3Config.get(prop), that.s3Config.get(prop))) {
          return false;
        }
      }

      return true;
    }

    @Override
    public int hashCode() {
      int hash = 1;
      for (String prop : UNIQUE_PROPS) {
        Object value = s3Config.get(prop);
        hash = 31 * hash + (value != null ? value.hashCode() : 0);
      }

      return hash;
    }

    @Override
    public String toString() {
      return "[ Access Key="
          + s3Config.get(Constants.ACCESS_KEY)
          + ", Secret Key =*****, isSecure="
          + s3Config.get(SECURE_CONNECTIONS)
          + " ]";
    }
  }

  private <T extends AwsAsyncClientBuilder<T, ?> & S3BaseClientBuilder<T, ?>>
      T asyncConfigClientBuilder(T builder, String bucket) {

    try {
      builder
          .asyncConfiguration(
              b ->
                  b.advancedOption(
                      SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, threadPool))
          .credentialsProvider(getAsync2Provider(getConf()));
    } catch (IOException e) {
      throw UserException.dataReadError(e).build(logger);
    }
    if (!isCompatMode() && useBucketDiscovery) {
      // normal s3/govcloud mode.
      builder.region(getAWSBucketRegion(bucket));
    } else {
      builder.region(getAWSRegionFromConfigurationOrDefault(getConf()));
    }

    Optional<String> endpoint = getEndpoint(getConf());
    endpoint.ifPresent(
        e -> {
          try {
            builder.endpointOverride(new URI(e));
          } catch (URISyntaxException use) {
            throw UserException.sourceInBadState(use).build(logger);
          }
        });

    builder.serviceConfiguration(
        S3Configuration.builder()
            .pathStyleAccessEnabled(isPathStyleAccessEnabled(getConf()))
            .build());

    return builder;
  }

  private <T extends SdkSyncClientBuilder<T, ?> & S3BaseClientBuilder<T, ?>>
      T syncConfigClientBuilder(T builder, String bucket) {
    final Configuration conf = getConf();

    // Note that AWS SDKv2 client will close the credentials provider if needed when the client is
    // closed
    try {
      builder
          .credentialsProvider(getAsync2Provider(conf))
          .httpClientBuilder(ApacheHttpConnectionUtil.initConnectionSettings(conf));
    } catch (IOException e) {
      throw UserException.dataReadError(e).build(logger);
    }
    Optional<String> endpoint = getEndpoint(conf);

    endpoint.ifPresent(
        e -> {
          try {
            builder.endpointOverride(new URI(e));
          } catch (URISyntaxException use) {
            throw UserException.sourceInBadState(use).build(logger);
          }
        });

    if (!isCompatMode() && useBucketDiscovery) {
      // normal s3/govcloud mode.
      builder.region(getAWSBucketRegion(bucket));
    } else {
      builder.region(getAWSRegionFromConfigurationOrDefault(conf));
    }

    builder.serviceConfiguration(
        S3Configuration.builder()
            .pathStyleAccessEnabled(isPathStyleAccessEnabled(getConf()))
            .build());

    return builder;
  }

  static software.amazon.awssdk.regions.Region getAWSRegionFromConfigurationOrDefault(
      Configuration conf) {
    final String regionOverride = conf.getTrimmed(REGION_OVERRIDE);
    if (!Strings.isNullOrEmpty(regionOverride)) {
      // set the region to what the user provided unless they provided an empty string.
      return software.amazon.awssdk.regions.Region.of(regionOverride);
    }

    return getAwsRegionFromEndpoint(conf.get(Constants.ENDPOINT));
  }

  static software.amazon.awssdk.regions.Region getAwsRegionFromEndpoint(String endpoint) {
    // Determine if one of the known AWS regions is contained within the given endpoint, and return
    // that region if so.
    return Optional.ofNullable(endpoint)
        .map(e -> e.toLowerCase(Locale.ROOT)) // lower-case the endpoint for easy detection
        .filter(
            e ->
                e.endsWith(S3_ENDPOINT_END)
                    || e.endsWith(S3_CN_ENDPOINT_END)) // omit any semi-malformed endpoints
        .flatMap(
            e ->
                software.amazon.awssdk.regions.Region.regions().stream()
                    .filter(region -> e.contains(region.id()))
                    .findFirst()) // map the endpoint to the region contained within it, if any
        .orElse(
            software.amazon.awssdk.regions.Region
                .US_EAST_1); // default to US_EAST_1 if no regions are found.
  }

  static Optional<String> getEndpoint(Configuration conf) {
    return Optional.ofNullable(conf.getTrimmed(Constants.ENDPOINT))
        .map(s -> getHttpScheme(conf) + s);
  }

  static Optional<String> getStsEndpoint(Configuration conf) {
    return Optional.ofNullable(conf.getTrimmed(Constants.ASSUMED_ROLE_STS_ENDPOINT))
        .map(
            s -> {
              if (s.startsWith("https://")) {
                return s;
              }

              return "https://" + s;
            });
  }

  private static String getHttpScheme(Configuration conf) {
    return conf.getBoolean(SECURE_CONNECTIONS, true) ? "https://" : "http://";
  }

  private boolean isCompatMode() {
    return getConf().getBoolean(COMPATIBILITY_MODE, false);
  }

  @VisibleForTesting
  static boolean isPathStyleAccessEnabled(Configuration conf) {
    return conf.getBoolean(Constants.PATH_STYLE_ACCESS, false);
  }

  private boolean isSsecUsed() {
    String ssecAlgorithm = getConf().get("fs.s3a.server-side-encryption-algorithm", "");
    return "sse-c".equalsIgnoreCase(ssecAlgorithm);
  }

  private String getCustomerSSEKey(boolean ssecUsed) {
    if (!ssecUsed) {
      return "";
    }
    return getConf().get("fs.s3a.server-side-encryption.key", "");
  }

  @VisibleForTesting
  protected boolean isRequesterPays() {
    return getConf().getBoolean(ALLOW_REQUESTER_PAYS, false);
  }

  @VisibleForTesting
  protected CloseableResource<AmazonS3> getS3V1Client() throws Exception {
    return clientCache.get(clientKey);
  }
}
