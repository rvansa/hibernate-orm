package org.hibernate.cache.spi.access;

import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Allows the cache to book-keep data related to current session, such as operations started during
 * first (prepare) phase of the transaction and delay completion of transaction until all updates are completed.
 *
 * While transactional semantics might be fully implemented by the cache provider, Hibernate may require
 * different transactional semantics: In order to prevent inconsistent reads, 2LC should honor the
 * {@link org.hibernate.cfg.AvailableSettings#ISOLATION hibernate.connection.isolation} settings. For example,
 * if this is set to {@link java.sql.Connection#TRANSACTION_REPEATABLE_READ} or higher, 2LC should not expose
 * entities that are modified in any concurrently executing transactions, and force DB load (that could block)
 * instead. Native transactional implementation may provide looser semantics and 2LC implementation has to adapt
 * to these.
 *
 * While this interface should allow support of batching through asynchronous operations, the interface is synchronous
 * because it is meant to block until some operations complete, rather than starting new (async) ones.
 *
 * This object has 1:1 relationship with {@link SharedSessionContractImplementor session}, despite the transaction
 * may span multiple sessions.
 */
public interface StorageAccessContext {
   /**
    * Invoked before the DB commit begins, and after all
    * {@link EntityStorageAccess#insert(StorageAccessContext, Object, Object, Object)},
    * {@link EntityStorageAccess#update(StorageAccessContext, Object, Object, Object, Object)}
    * {@link StorageAccess#remove(StorageAccessContext, Object)}, and
    * {@link StorageAccess#removeAll()}.
    *
    * When this call completes, locks requested through
    * {@link StorageAccess#lockItem(StorageAccessContext, Object, Object)} and
    * {@link StorageAccess#lockRegion()} should be acquired.
    */
   void beforeCompletion();

   /**
    * Invoked after DB successfully commits and after all
    * {@link EntityStorageAccess#afterInsert(StorageAccessContext, Object, Object, Object)},
    * {@link EntityStorageAccess#afterUpdate(StorageAccessContext, Object, Object, Object, Object, SoftLock)},
    * {@link StorageAccess#unlockItem(StorageAccessContext, Object, SoftLock)} and
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
    * @return The associated session.
    */
   SharedSessionContractImplementor session();

   /**
    * Timestamp that would have been returned by {@link RegionFactory#nextTimestamp()} before the last transaction
    * in current session started.
    */
   long transactionStartTimestamp();
}
