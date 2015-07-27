/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cache.infinispan.query;

import javax.transaction.InvalidTransactionException;
import javax.transaction.NotSupportedException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;

import org.hibernate.cache.CacheException;
import org.hibernate.cache.infinispan.impl.BaseTransactionalDataRegion;
import org.hibernate.cache.infinispan.util.Caches;
import org.hibernate.cache.spi.QueryResultsRegion;
import org.hibernate.cache.spi.RegionFactory;
import org.hibernate.engine.spi.SessionImplementor;
import org.infinispan.AdvancedCache;
import org.infinispan.configuration.cache.TransactionConfiguration;
import org.infinispan.context.Flag;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Region for caching query results.
 *
 * @author Chris Bredesen
 * @author Galder Zamarreño
 * @since 3.5
 */
public class QueryResultsRegionImpl extends BaseTransactionalDataRegion implements QueryResultsRegion {
	private static final Log log = LogFactory.getLog( QueryResultsRegionImpl.class );

	private final AdvancedCache evictCache;
	private final AdvancedCache putCache;
	private final AdvancedCache getCache;
	private final ConcurrentMap<Transaction, Map> transactionContext = new ConcurrentHashMap<Transaction, Map>();

	private final boolean putCacheRequiresTransaction;
	public QueryResultsRegionImpl(AdvancedCache cache, String name, TransactionManager transactionManager, RegionFactory factory) {
		super( cache, name, transactionManager, null, factory, null );
		// If Infinispan is using INVALIDATION for query cache, we don't want to propagate changes.
		// We use the Timestamps cache to manage invalidation
		final boolean localOnly = Caches.isInvalidationCache( cache );

		this.evictCache = localOnly ? Caches.localCache( cache ) : cache;

		this.putCache = localOnly ?
				Caches.failSilentWriteCache( cache, Flag.CACHE_MODE_LOCAL ) :
				Caches.failSilentWriteCache( cache );

		this.getCache = Caches.failSilentReadCache( cache );

		TransactionConfiguration transactionConfiguration = putCache.getCacheConfiguration().transaction();
		boolean transactional = transactionConfiguration.transactionMode() != TransactionMode.NON_TRANSACTIONAL;
		this.putCacheRequiresTransaction = transactional && !transactionConfiguration.autoCommit();
		// Since we execute the query update explicitly form transaction synchronization, the putCache does not need
		// to be transactional anymore (it had to be in the past to prevent revealing uncommitted changes).
		if (transactional) {
			log.warn("Use non-transactional query caches for best performance!");
		}
	}

	@Override
	public void evict(Object key) throws CacheException {
		evictCache.remove( key );
	}

	@Override
	public void evictAll() throws CacheException {
		final Transaction tx = suspend();
		try {
			// Invalidate the local region and then go remote
			invalidateRegion();
			Caches.broadcastEvictAll( cache );
		}
		finally {
			resume( tx );
		}
	}

	@Override
	public Object get(SessionImplementor session, Object key) throws CacheException {
		// If the region is not valid, skip cache store to avoid going remote to retrieve the query.
		// The aim of this is to maintain same logic/semantics as when state transfer was configured.
		// TODO: Once https://issues.jboss.org/browse/ISPN-835 has been resolved, revert to state transfer and remove workaround
		boolean skipCacheStore = false;
		if ( !isValid() ) {
			skipCacheStore = true;
		}

		if ( !checkValid() ) {
			return null;
		}

		// In Infinispan get doesn't acquire any locks, so no need to suspend the tx.
		// In the past, when get operations acquired locks, suspending the tx was a way
		// to avoid holding locks that would prevent updates.
		// Add a zero (or low) timeout option so we don't block
		// waiting for tx's that did a put to commit
		Object result;
		if ( skipCacheStore ) {
			result = getCache.withFlags( Flag.SKIP_CACHE_STORE ).get( key );
		}
		else {
			result = getCache.get( key );
		}
		if (result == null) {
			TransactionManager tm = getTransactionManager();
			Transaction tx;
			try {
				if (tm != null && (tx = tm.getTransaction()) != null) {
					Map map = transactionContext.get(tx);
					if (map != null) {
						result = map.get(key);
					}
            }
			} catch (SystemException e) {
				throw new CacheException("Cannot retrieve current transaction", e);
			}
		}
		return result;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void put(SessionImplementor session, Object key, Object value) throws CacheException {
		if ( checkValid() ) {
			// See HHH-7898: Even with FAIL_SILENTLY flag, failure to write in transaction
			// fails the whole transaction. It is an Infinispan quirk that cannot be fixed
			// ISPN-5356 tracks that. This is because if the transaction continued the
			// value could be committed on backup owners, including the failed operation,
			// and the result would not be consistent.
			TransactionManager tm = getTransactionManager();
			Transaction tx;
			try {
				if (tm != null && (tx = tm.getTransaction()) != null) {
					tx.registerSynchronization(new PostTransactionQueryUpdate(tm, tx, key, value));
					// no need to synchronize as the transaction will be accessed by only one thread
					Map map = transactionContext.get(tx);
					if (map == null) {
						transactionContext.put(tx, map = new HashMap());
					}
					map.put(key, value);
					return;
				}
			}
			catch (SystemException e) {
				throw new CacheException("Failed to retrieve current transaction", e);
			}
			catch (RollbackException e) {
				throw new CacheException("Failed to retrieve current transaction", e);
			}
			// Here we don't want to suspend the tx. If we do:
			// 1) We might be caching query results that reflect uncommitted
			// changes. No tx == no WL on cache node, so other threads
			// can prematurely see those query results
			// 2) No tx == immediate replication. More overhead, plus we
			// spread issue #1 above around the cluster

			// Add a zero (or quite low) timeout option so we don't block.
			// Ignore any TimeoutException. Basically we forego caching the
			// query result in order to avoid blocking.
			// Reads are done with suspended tx, so they should not hold the
			// lock for long.  Not caching the query result is OK, since
			// any subsequent read will just see the old result with its
			// out-of-date timestamp; that result will be discarded and the
			// db query performed again.
			putCache.put( key, value );
		}
	}

	private class PostTransactionQueryUpdate implements Synchronization {
		private final TransactionManager tm;
		private final Transaction tx;
		private final Object key;
		private final Object value;

		public PostTransactionQueryUpdate(TransactionManager tm, Transaction tx, Object key, Object value) {
			this.tm = tm;
			this.tx = tx;
			this.key = key;
			this.value = value;
		}

		@Override
		public void beforeCompletion() {
		}

		@Override
		public void afterCompletion(int status) {
			transactionContext.remove(tx);
			switch (status) {
				case Status.STATUS_COMMITTING:
				case Status.STATUS_COMMITTED:
					try {
						final Transaction committedTx = tm.suspend();
						try {
							if (putCacheRequiresTransaction) {
								tm.begin();
								try {
									putCache.put(key, value);
								}
								catch (Exception e) {
									tm.setRollbackOnly();
									// silently ignoring failures
								}
								finally {
									try {
										if (tm.getStatus() == Status.STATUS_ACTIVE) {
											tm.commit();
										}
										else {
											tm.rollback();
										}
									}
									catch (Exception e) {
										log.error("Failed to roll-back query cache update", e);
									}
								}
							}
							else {
								putCache.put(key, value); // should never throw due to FAIL_SILENTLY flag
							}
						}
						catch (NotSupportedException e) {
							throw new IllegalStateException("Cannot start another transaction!", e);
						}
						finally {
							tm.resume(committedTx);
						}
					}
					catch (SystemException e) {
						throw new IllegalStateException("Cannot suspend current transaction", e);
					}
					catch (InvalidTransactionException e) {
						throw new IllegalStateException("Cannot resume current transaction", e);
					}
					break;
				case Status.STATUS_ROLLING_BACK:
				case Status.STATUS_ROLLEDBACK:
					// noop
					break;
				default:
					throw new IllegalStateException("This should be called in second phase of transaction commit!");
			}
		}
	}
}
