/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cache.infinispan.access;

import org.hibernate.cache.CacheException;
import org.hibernate.cache.infinispan.impl.BaseTransactionalDataRegion;
import org.hibernate.cache.infinispan.util.Caches;
import org.hibernate.cache.infinispan.util.FutureUpdate;
import org.hibernate.cache.infinispan.util.InfinispanMessageLogger;
import org.hibernate.cache.infinispan.util.TombstoneUpdate;
import org.hibernate.cache.infinispan.util.Tombstone;
import org.hibernate.cache.spi.access.SoftLock;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.resource.transaction.TransactionCoordinator;
import org.infinispan.AdvancedCache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.context.Flag;

/**
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class TombstoneAccessDelegate implements AccessDelegate {
	private static final InfinispanMessageLogger log = InfinispanMessageLogger.Provider.getLog( TombstoneAccessDelegate.class );

	protected final BaseTransactionalDataRegion region;
	protected final AdvancedCache cache;
	protected final AdvancedCache writeCache;
	protected final AdvancedCache localWriteCache;
	protected final AdvancedCache asyncWriteCache;
	protected final AdvancedCache putFromLoadCacheLocal;
	protected final AdvancedCache putFromLoadCacheAsync;
	protected final boolean requiresTransaction;

	public TombstoneAccessDelegate(BaseTransactionalDataRegion region) {
		this.region = region;
		this.cache = region.getCache();
		this.writeCache = Caches.ignoreReturnValuesCache(cache);
		// Note that correct behaviour of local and async writes depends on LockingInterceptor (see there for details)
		this.localWriteCache = Caches.localCache(writeCache);
		this.asyncWriteCache = Caches.asyncWriteCache(writeCache, Flag.IGNORE_RETURN_VALUES);
		this.putFromLoadCacheLocal = localWriteCache.withFlags( Flag.ZERO_LOCK_ACQUISITION_TIMEOUT, Flag.FAIL_SILENTLY );
		this.putFromLoadCacheAsync = asyncWriteCache.withFlags( Flag.ZERO_LOCK_ACQUISITION_TIMEOUT, Flag.FAIL_SILENTLY );
		Configuration configuration = cache.getCacheConfiguration();
		if (configuration.clustering().cacheMode().isInvalidation()) {
			throw new IllegalArgumentException("For tombstone-based caching, invalidation cache is not allowed.");
		}
		if (configuration.transaction().transactionMode().isTransactional()) {
			throw new IllegalArgumentException("Currently transactional caches are not supported.");
		}
		requiresTransaction = configuration.transaction().transactionMode().isTransactional()
				&& !configuration.transaction().autoCommit();
	}

	@Override
	public Object get(SessionImplementor session, Object key, long txTimestamp) throws CacheException {
		if (txTimestamp < region.getLastRegionInvalidation() ) {
			return null;
		}
		Object value = cache.get(key);
		if (value instanceof Tombstone) {
			return null;
		}
		else if (value instanceof FutureUpdate) {
			return ((FutureUpdate) value).getValue();
		}
		else {
			return value;
		}
	}

	@Override
	public boolean putFromLoad(SessionImplementor session, Object key, Object value, long txTimestamp, Object version) {
		return putFromLoad(session, key, value, txTimestamp, version, false);
	}

	@Override
	public boolean putFromLoad(SessionImplementor session, Object key, Object value, long txTimestamp, Object version, boolean minimalPutOverride) throws CacheException {
		long lastRegionInvalidation = region.getLastRegionInvalidation();
		if (txTimestamp < lastRegionInvalidation) {
			log.tracef("putFromLoad not executed since tx started at %d, before last region invalidation finished = %d", txTimestamp, lastRegionInvalidation);
			return false;
		}
		if (minimalPutOverride) {
			Object prev = cache.get(key);
			if (prev instanceof Tombstone) {
				Tombstone tombstone = (Tombstone) prev;
				long lastTimestamp = tombstone.getLastTimestamp();
				if (txTimestamp <= lastTimestamp) {
					log.tracef("putFromLoad not executed since tx started at %d, before last invalidation finished = %d", txTimestamp, lastTimestamp);
					return false;
				}
			}
			else if (prev != null) {
				log.tracef("putFromLoad not executed since cache contains %s", prev);
				return false;
			}
		}
		// we can't use putForExternalRead since the PFER flag means that entry is not wrapped into context
		// when it is present in the container. TombstoneCallInterceptor will deal with this.
		TombstoneUpdate update = new TombstoneUpdate(session.getTimestamp(), value);
		// If we're the backup owner, async propagation wouldn't change the value soon enough as it goes
		// through primary owner - therefore we'll synchronously update it locally.
		putFromLoadCacheAsync.put(key, update);
		putFromLoadCacheLocal.put(key, update);
		return true;
	}

	@Override
	public boolean insert(SessionImplementor session, Object key, Object value, Object version) throws CacheException {
		write(session, key, value);
		return true;
	}

	@Override
	public boolean update(SessionImplementor session, Object key, Object value, Object currentVersion, Object previousVersion) throws CacheException {
		write(session, key, value);
		return true;
	}

	@Override
	public void remove(SessionImplementor session, Object key) throws CacheException {
		write(session, key, null);
	}

	protected void write(SessionImplementor session, Object key, Object value) {
		TransactionCoordinator tc = session.getTransactionCoordinator();
		FutureUpdateSynchronization sync = new FutureUpdateSynchronization(tc, localWriteCache, asyncWriteCache, requiresTransaction, key, value, region, session.getTimestamp());
		// The update will be invalidating all putFromLoads for the duration of expiration or until removed by the synchronization
		Tombstone tombstone = new Tombstone(sync.getUuid(), region.nextTimestamp() + region.getTombstoneExpiration());
		// The outcome of this operation is actually defined in TombstoneCallInterceptor
		// Metadata in PKVC are cleared and set in the interceptor, too
		writeCache.put(key, tombstone);
		tc.getLocalSynchronizations().registerSynchronization(sync);
	}

	@Override
	public void removeAll() throws CacheException {
		region.beginInvalidation();
		try {
			Caches.broadcastEvictAll(cache);
		}
		finally {
			region.endInvalidation();
		}
	}

	@Override
	public void evict(Object key) throws CacheException {
		writeCache.put(key, new TombstoneUpdate<>(region.nextTimestamp(), null));
	}

	@Override
	public void evictAll() throws CacheException {
		region.beginInvalidation();
		try {
			Caches.broadcastEvictAll(cache);
		}
		finally {
			region.endInvalidation();
		}
	}

	@Override
	public void unlockItem(SessionImplementor session, Object key) throws CacheException {
	}

	@Override
	public boolean afterInsert(SessionImplementor session, Object key, Object value, Object version) {
		return false;
	}

	@Override
	public boolean afterUpdate(SessionImplementor session, Object key, Object value, Object currentVersion, Object previousVersion, SoftLock lock) {
		return false;
	}
}
