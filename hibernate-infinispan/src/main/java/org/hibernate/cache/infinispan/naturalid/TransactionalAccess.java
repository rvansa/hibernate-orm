/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cache.infinispan.naturalid;

import org.hibernate.cache.CacheException;
import org.hibernate.cache.infinispan.access.TransactionalAccessDelegate;
import org.hibernate.cache.spi.NaturalIdRegion;
import org.hibernate.cache.spi.access.NaturalIdRegionAccessStrategy;
import org.hibernate.cache.spi.access.SoftLock;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;

/**
 * @author Strong Liu <stliu@hibernate.org>
 */
class TransactionalAccess implements NaturalIdRegionAccessStrategy {
	private final NaturalIdRegionImpl region;
	private final TransactionalAccessDelegate delegate;

	TransactionalAccess(NaturalIdRegionImpl region) {
		this.region = region;
		this.delegate = new TransactionalAccessDelegate( region, region.getPutFromLoadValidator() );
	}

	@Override
	public boolean insert(SessionImplementor session, Object key, Object value) throws CacheException {
		return delegate.insert( session, key, value, null );
	}

	@Override
	public boolean update(SessionImplementor session, Object key, Object value) throws CacheException {
		return delegate.update( session, key, value, null, null );
	}

	@Override
	public NaturalIdRegion getRegion() {
		return region;
	}

	@Override
	public void evict(Object key) throws CacheException {
		delegate.evict( key );
	}

	@Override
	public void evictAll() throws CacheException {
		delegate.evictAll();
	}

	@Override
	public Object get(SessionImplementor session, Object key, long txTimestamp) throws CacheException {
		return delegate.get( key, txTimestamp );
	}

	@Override
	public boolean putFromLoad(SessionImplementor session, Object key, Object value, long txTimestamp, Object version) throws CacheException {
		return delegate.putFromLoad( session, key, value, txTimestamp, version );
	}

	@Override
	public boolean putFromLoad(SessionImplementor session, Object key, Object value, long txTimestamp, Object version, boolean minimalPutOverride)
			throws CacheException {
		return delegate.putFromLoad( session, key, value, txTimestamp, version, minimalPutOverride );
	}

	@Override
	public void remove(SessionImplementor session, Object key) throws CacheException {
		delegate.remove( session, key );
	}

	@Override
	public void removeAll() throws CacheException {
		delegate.removeAll();
	}

	@Override
	public SoftLock lockItem(SessionImplementor session, Object key, Object version) throws CacheException {
		return null;
	}

	@Override
	public SoftLock lockRegion() throws CacheException {
		return null;
	}

	@Override
	public void unlockItem(SessionImplementor session, Object key, SoftLock lock) throws CacheException {
		delegate.unlockItem(key);
	}

	@Override
	public void unlockRegion(SoftLock lock) throws CacheException {
	}

	@Override
	public boolean afterInsert(SessionImplementor session, Object key, Object value) throws CacheException {
		return delegate.afterInsert(key, value, null);
	}

	@Override
	public boolean afterUpdate(SessionImplementor session, Object key, Object value, SoftLock lock) throws CacheException {
		return delegate.afterUpdate(key, value, null, null, lock);
	}

	@Override
	public Object generateCacheKey(Object[] naturalIdValues, EntityPersister persister, SessionImplementor session) {
		return region.getCacheKeysFactory().createNaturalIdKey(naturalIdValues, persister, session);
	}

	@Override
	public Object[] getNaturalIdValues(Object cacheKey) {
		return region.getCacheKeysFactory().getNaturalIdValues(cacheKey);
	}
}
