/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.cache.spi;

import java.util.Map;
import java.util.Properties;

import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.cache.CacheException;
import org.hibernate.cache.spi.access.AccessType;
import org.hibernate.cache.spi.access.StorageAccessContext;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.service.Service;
import org.hibernate.service.spi.Stoppable;

/**
 * Contract for building second level cache regions.
 * <p/>
 * Implementors should define a constructor in one of two forms:<ul>
 *     <li>MyRegionFactoryImpl({@link java.util.Properties})</li>
 *     <li>MyRegionFactoryImpl()</li>
 * </ul>
 * Use the first when we need to read config properties prior to
 * {@link #start(SessionFactoryOptions, Properties)} being called.
 *
 * @author Steve Ebersole
 */
public interface RegionFactory extends Service, Stoppable {

	// todo (6.0) : can #start be reduced to simply implementing Startable?
	// 		Startable's #start method, however, defines no args.  Are the args here needed
	// 		for any known implementors?  If so, any other way to pass them?  Initiator?

	/**
	 * Lifecycle callback to perform any necessary initialization of the
	 * underlying cache implementation(s).  Called exactly once during the
	 * construction of a {@link org.hibernate.internal.SessionFactoryImpl}.
	 *
	 * @param settings The settings in effect.
	 * @param properties The defined cfg properties
	 *
	 * @throws org.hibernate.cache.CacheException Indicates problems starting the L2 cache impl;
	 * considered as a sign to stop {@link org.hibernate.SessionFactory}
	 * building.
	 *
	 * @deprecated (since 5.2) use the form accepting map instead.
	 */
	@Deprecated
	void start(SessionFactoryOptions settings, Properties properties) throws CacheException;

	/**
	 * Lifecycle callback to perform any necessary initialization of the
	 * underlying cache implementation(s).  Called exactly once during the
	 * construction of a {@link org.hibernate.internal.SessionFactoryImpl}.
	 *
	 * @param settings The settings in effect.
	 * @param configValues The available config values
	 *
	 * @throws org.hibernate.cache.CacheException Indicates problems starting the L2 cache impl;
	 * considered as a sign to stop {@link org.hibernate.SessionFactory}
	 * building.
	 */
	default void start(SessionFactoryOptions settings, Map<String, Object> configValues) throws CacheException {
		final Properties properties = new Properties();
		properties.putAll( configValues );
		start( settings, properties );
	}

	/**
	 * By default should we perform "minimal puts" when using this second
	 * level cache implementation?
	 *
	 * @return True if "minimal puts" should be performed by default; false
	 *         otherwise.
	 */
	boolean isMinimalPutsEnabledByDefault();

	/**
	 * Get the default access type for any "user model" data
	 *
	 * @return This factory's default access type.
	 */
	AccessType getDefaultAccessType();

	/**
	 * Create a named Region instance.
	 *
	 * @param regionName The name of the Region to create.
	 * @param regionNameMapping The user requested cacheable mappings for this Region
	 * @param buildingContext Access to delegates useful in building the Region
	 */
	CacheableRegion buildCacheableRegion(
			String regionName,
			CacheableRegionNameMapping regionNameMapping,
			RegionBuildingContext buildingContext);


	QueryResultsRegion buildQueryResultsRegion(String regionName, RegionBuildingContext buildingContext);

	UpdateTimestampsRegion buildUpdateTimestampsRegion(RegionBuildingContext buildingContext);

	/**
	 * Generate a timestamp.
	 * <p/>
	 * Used by Session to generate its {@link StorageAccessContext#transactionStartTimestamp()} value.
	 * <p/>
	 * This is generally used for cache content locking/unlocking purposes
	 * depending upon the access-strategy being used.
	 */
	@Deprecated
	long nextTimestamp();

	/**
	 * Invoked before first modifying operation on {@link org.hibernate.cache.spi.access.StorageAccess} is invoked.
	 *
	 * Implementations are free to return <code>null</code>.
    */
	StorageAccessContext startStorageAccess();
}
