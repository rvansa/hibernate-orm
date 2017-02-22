package org.hibernate.cache.spi.access;

import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Allows the cache to book-keep data related to current transaction, such as operations started during
 * first (prepar) phase and delay completion of transaction until all updates are completes.
 *
 * This is necessary to allow batch updates if Hibernate is configured to use JDBC-only transactions,
 * and therefore information cannot be retrieved from the JTA transaction assigned to current thread.
 *
 * While transactional semantics might be fully implemented by the cache provider, Hibernate may require
 * different transactional semantics: In order to prevent inconsistent reads, 2LC should not expose
 * entities that are modified in any concurrently executing transactions, and force DB load instead.
 * Native transactional implementation may provide looser semantics and 2LC implementation has to adapt
 * to these.
 *
 * While this interface should allow support of batching through asynchronous operations, the interface is
 * synchronous as JTA invocations - it intentionally looks similar to {@link javax.transaction.Synchronization}.
 *
 * Instance of CacheTransaction should not be linked to a particular session, because a transaction can
 * span multiple sessions.
 */
public interface CacheSynchronization {
   /**
    * Invoked before the DB commit begins, and after all
    * {@link EntityStorageAccess#insert(SharedSessionContractImplementor, Object, Object, Object)},
    * {@link EntityStorageAccess#update(SharedSessionContractImplementor, Object, Object, Object, Object)}
    * {@link StorageAccess#remove(SharedSessionContractImplementor, Object)}, and
    * {@link StorageAccess#removeAll()}.
    *
    * When this call completes, locks requested through
    * {@link StorageAccess#lockItem(SharedSessionContractImplementor, Object, Object)} and
    * {@link StorageAccess#lockRegion()} should be acquired.
    */
   void beforeCompletion();

   /**
    * Invoked after DB successfully commits and after all
    * {@link EntityStorageAccess#afterInsert(SharedSessionContractImplementor, Object, Object, Object)},
    * {@link EntityStorageAccess#afterUpdate(SharedSessionContractImplementor, Object, Object, Object, Object, SoftLock)},
    * {@link StorageAccess#unlockItem(SharedSessionContractImplementor, Object, SoftLock)} and
    * {@link StorageAccess#unlockRegion(SoftLock)} related to the current transaction were invoked.
    *
    * When this call completes, all synchronous operations that {@link StorageAccess} executes must be completed
    * It is up to the implementation which operations have to be completed synchronously: e.g. some implementation
    * may guarantee that after the transaction completes, all further reads for the inserted entry will hit cache.
    * Other implementation may waive such guarantee to reduce the duration of transaction.
    *
    * This is the last chance to cleanup any locks acquired during the transaction (e.g. when the transaction fails).
    */
   void afterCompletion(boolean successful);

   /**
    * TODO: is this necessary?
    *
    * Value provided to {@link org.hibernate.cache.spi.RegionFactory#startTransaction(long)}.
    */
   long transactionStartTimestamp();
}
