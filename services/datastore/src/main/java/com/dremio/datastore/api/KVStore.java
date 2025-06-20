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
package com.dremio.datastore.api;

import com.dremio.context.TenantContext;
import com.dremio.datastore.KVAdmin;
import com.dremio.datastore.RemoteDataStoreProtobuf.PutOptionInfo;
import com.dremio.datastore.RemoteDataStoreProtobuf.PutOptionType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A key value store abstraction.
 *
 * @param <K> the Key type.
 * @param <V> the value type.
 */
public interface KVStore<K, V> {

  /** Generic base interface for KV Store options. */
  interface KVStoreOption {}

  /** Options for GET operations. */
  interface GetOption extends KVStoreOption {}

  /** Options for PUT operations. */
  interface PutOption extends KVStoreOption {

    /**
     * Specialized VersionOption instance for indicating that a put operation is for creating a
     * value.
     *
     * <p>If a CREATE option is specified but a key already exists in the table, an exception should
     * be raised.
     */
    PutOption CREATE =
        new PutOption() {
          @Override
          public PutOptionInfo getPutOptionInfo() {
            return PutOptionInfo.newBuilder().setType(PutOptionType.CREATE).build();
          }
        };

    // TODO (DX-22212): The KVStore API should not depend on classes from the remote datastore
    // RPC definition.
    PutOptionInfo getPutOptionInfo();
  }

  /** Options for DELETE operations. */
  interface DeleteOption extends KVStoreOption {
    /**
     * This option is a hint to the <code>KVStore</code> implementation that the caller knows that
     * the deleted key does not represent a metadata object. Therefore, implementations can optimize
     * the delete operation.
     */
    DeleteOption NO_META = new DeleteOption() {};
  }

  /** Options for CONTAINS operations. */
  interface ContainsOption extends KVStoreOption {}

  /** Options for FIND operations. */
  interface FindOption extends KVStoreOption {}

  /** Options for INCREMENT operations. */
  interface IncrementOption extends KVStoreOption {
    /**
     * Specialized IncrementOption instance for indicating that a bulkIncrement operation has to be
     * unordered.
     */
    IncrementOption USE_UNORDERED_WRITES = new IncrementOption() {};

    // indicates if the bulkIncrement ops has to be order or not.
    default boolean orderedWrites() {
      return false;
    }
    ;
  }

  /**
   * Return the document associated with the key, or {@code null} if no such entry exists.
   *
   * @param key the key to use to look for the value.
   * @param options extra options for GET operation.
   * @return the a Document with the value and tag associated with the key, or {@code null} if no
   *     such entry exists.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   */
  Document<K, V> get(K key, GetOption... options);

  /**
   * Get the Documents for each of the keys provided.
   *
   * @param keys a list of keys which their values are to be retrieved.
   * @param options extra options for GET operations.
   * @return an Iterable of Documents associated with the list of keys provided. Iterable entry is
   *     {@code null} if no such value exists.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   */
  Iterable<Document<K, V>> get(List<K> keys, GetOption... options);

  /**
   * Saves a document to the KV Store with the corresponding key. If the store already contains a
   * value associated with the key, the old value is discarded and replaced by the new value.
   *
   * @param key the key to save the value.
   * @param value the value to save.
   * @param options extra options for PUT operation.
   * @return the document that is updated or created, with the latest version tag.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   * @throws java.util.ConcurrentModificationException when VersionOption is passed in as a
   *     PutOption and that the version tag provided by VersionOption is outdated. The provided
   *     document is not saved nor updated.
   */
  Document<K, V> put(K key, V value, PutOption... options);

  /**
   * Removes a document with the provided key value.
   *
   * @param key the key of the document to be removed from the KV Store.
   * @param options extra options for DELETE operation.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   * @throws java.util.ConcurrentModificationException when VersionOption is passed in as a
   *     DeleteOption and that the version tag provided by VersionOption is outdated. The document
   *     associated with the provided key is not deleted.
   */
  void delete(K key, DeleteOption... options);

  /**
   * Checks if the KV Store contains a document corresponding to the provided key.
   *
   * @param key the key of the document to search for.
   * @param options extra options for CONTAINS operation.
   * @return true if KV Store contains a document with the provided key value.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   */
  boolean contains(K key, ContainsOption... options);

  /**
   * Retrieves all documents from the KV Store.
   *
   * @return all documents from the KV Store. Returns {@code null} if no documents are found.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   */
  Iterable<Document<K, V>> find(FindOption... options);

  /**
   * @param find ImmutableFindByRange object indicating the beginning and the end of the range to
   *     search for.
   * @param options extra options for FIND operation.
   * @return an Iterable of documents found in the KV Store that satisfies the search range. Returns
   *     {@code null} if no documents are found.
   * @throws com.dremio.datastore.DatastoreException when one or more runtime failures are
   *     encountered.
   */
  Iterable<Document<K, V>> find(FindByRange<K> find, FindOption... options);

  /**
   * @param consumer the consumer that must be applied for each matching tuple
   * @param executor the execution context which executes the consumer
   * @param documentToTenantConverter provides tenant context by taking tenantId of the document and
   *     value of the document as inputs. Note, 'K' is the key of the document which is a compound
   *     key where one of the key is tenantId
   */
  default void applyForAllTenants(
      BiConsumer<K, V> consumer,
      ExecutorService executor,
      BiFunction<String, V, TenantContext> documentToTenantConverter,
      FindOption... options) {
    throw new UnsupportedOperationException("Only applicable for MultiTenantKVstore");
  }

  void bulkIncrement(Map<K, List<IncrementCounter>> keysToIncrement, IncrementOption option);

  /**
   * The method's implementation may not guarantee atomicity/transactionality. If the underlying
   * store supports bulk deletes they are used, otherwise the method iterates and does point
   * deletes.
   */
  void bulkDelete(List<K> keysToDelete, DeleteOption... deleteOptions);

  /**
   * Get the name of the KV Store.
   *
   * @return the name of the KV Store.
   */
  String getName();

  /**
   * Deprecated method from the previous KVStore API. This is added to maintain compatibility with
   * RocksDB.
   *
   * @return KVAdmin of this KVStore.
   */
  @Deprecated
  KVAdmin getAdmin();

  /**
   * Get the KVFormatter for this KVStore.
   *
   * @return the KVFormatter for this KVStore.
   */
  default KVFormatter<K, V> getKVFormatter() {
    throw new UnsupportedOperationException("KVFormatter is not supported for this KVStore");
  }
}
