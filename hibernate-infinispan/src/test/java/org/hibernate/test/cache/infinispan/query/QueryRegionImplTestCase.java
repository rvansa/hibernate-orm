/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.cache.infinispan.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cache.infinispan.InfinispanRegionFactory;
import org.hibernate.cache.infinispan.util.Caches;
import org.hibernate.cache.internal.StandardQueryCache;
import org.hibernate.cache.spi.CacheDataDescription;
import org.hibernate.cache.spi.GeneralDataRegion;
import org.hibernate.cache.spi.QueryResultsRegion;
import org.hibernate.cache.spi.Region;

import org.hibernate.test.cache.infinispan.AbstractGeneralDataRegionTestCase;
import org.hibernate.test.cache.infinispan.util.CacheTestUtil;
import junit.framework.AssertionFailedError;

import org.infinispan.AdvancedCache;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryVisited;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryVisitedEvent;
import org.infinispan.transaction.tm.BatchModeTransactionManager;
import org.infinispan.util.concurrent.IsolationLevel;

import org.jboss.logging.Logger;

import javax.transaction.TransactionManager;

import static org.hibernate.cache.infinispan.util.Caches.withinTx;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests of QueryResultRegionImpl.
 *
 * @author Galder Zamarreño
 * @since 3.5
 */
public class QueryRegionImplTestCase extends AbstractGeneralDataRegionTestCase {
	private static final Logger log = Logger.getLogger( QueryRegionImplTestCase.class );
	protected TransactionManager tm = BatchModeTransactionManager.getInstance();

	@Override
	protected Region createRegion(
			InfinispanRegionFactory regionFactory,
			String regionName,
			Properties properties,
			CacheDataDescription cdd) {
		return regionFactory.buildQueryResultsRegion( regionName, properties );
	}

	@Override
	protected String getStandardRegionName(String regionPrefix) {
		return regionPrefix + "/" + StandardQueryCache.class.getName();
	}

   @Override
   protected void regionPut(final GeneralDataRegion region) throws Exception {
      Caches.withinTx(tm, () -> region.put(null, KEY, VALUE1) );
   }

   @Override
   protected void regionEvict(final GeneralDataRegion region) throws Exception {
      Caches.withinTx(tm, () -> region.evict(KEY) );
   }

   @Override
	protected AdvancedCache getInfinispanCache(InfinispanRegionFactory regionFactory) {
		return regionFactory.getCacheManager().getCache( getStandardRegionName( REGION_PREFIX ) ).getAdvancedCache();
	}

	@Override
	protected StandardServiceRegistryBuilder createStandardServiceRegistryBuilder() {
		return CacheTestUtil.buildCustomQueryCacheStandardServiceRegistryBuilder( REGION_PREFIX, "replicated-query" );
	}

	private interface RegionCallable {
		void call(InfinispanRegionFactory regionFactory, QueryResultsRegion region) throws Exception;
	}

	private void withQueryRegion(RegionCallable callable) throws Exception {
		StandardServiceRegistryBuilder ssrb = createStandardServiceRegistryBuilder();
		StandardServiceRegistry registry = ssrb.build();
		try {
			final Properties properties = CacheTestUtil.toProperties( ssrb.getSettings() );

			InfinispanRegionFactory regionFactory = CacheTestUtil.startRegionFactory(
					registry,
					getCacheTestSupport()
			);

			final QueryResultsRegion region = regionFactory.buildQueryResultsRegion(
					getStandardRegionName( REGION_PREFIX ),
					properties
			);
			callable.call(regionFactory, region);
		}
		finally {
			StandardServiceRegistryBuilder.destroy( registry );
		}
	}

	@Test
	public void testPutDoesNotBlockGet() throws Exception {
		withQueryRegion((regionFactory, region) -> {
			withinTx(tm, () -> region.put(null, KEY, VALUE1));

			assertEquals(VALUE1, withinTx(tm, () -> region.get(null, KEY)));

			final CountDownLatch readerLatch = new CountDownLatch(1);
			final CountDownLatch writerLatch = new CountDownLatch(1);
			final CountDownLatch completionLatch = new CountDownLatch(1);
			final ExceptionHolder holder = new ExceptionHolder();

			Thread reader = new Thread() {
				@Override
				public void run() {
					try {
						assertNotEquals(VALUE2, withinTx(tm, () -> region.get(null, KEY)));
					} catch (AssertionFailedError e) {
						holder.addAssertionFailure(e);
					} catch (Exception e) {
						holder.addException(e);
					} finally {
						readerLatch.countDown();
					}
				}
			};

			Thread writer = new Thread() {
				@Override
				public void run() {
					try {
						withinTx( tm, () -> {
							region.put(null, KEY, VALUE2);
							writerLatch.await();
							return null;
						});
					} catch (Exception e) {
						holder.addException(e);
						rollback();
					} finally {
						completionLatch.countDown();
					}
				}
			};

			reader.setDaemon(true);
			writer.setDaemon(true);

			writer.start();
			assertFalse("Writer is blocking", completionLatch.await(100, TimeUnit.MILLISECONDS));

			// Start the reader
			reader.start();
			assertTrue("Reader finished promptly", readerLatch.await(1000000000, TimeUnit.MILLISECONDS));

			writerLatch.countDown();

			assertTrue("Reader finished promptly", completionLatch.await(100, TimeUnit.MILLISECONDS));

			assertEquals(VALUE2, region.get(null, KEY));
		});
	}

	@Test
	public void testGetDoesNotBlockPut() throws Exception {
		withQueryRegion((regionFactory, region) -> {
			withinTx(tm, () -> region.put( null, KEY, VALUE1 ));
			assertEquals( VALUE1, withinTx(tm, () -> region.get( null, KEY )) );

			final AdvancedCache cache = getInfinispanCache(regionFactory);
			final CountDownLatch blockerLatch = new CountDownLatch( 1 );
			final CountDownLatch writerLatch = new CountDownLatch( 1 );
			final CountDownLatch completionLatch = new CountDownLatch( 1 );
			final ExceptionHolder holder = new ExceptionHolder();

			Thread reader = new Thread() {
				@Override
				public void run() {
					GetBlocker blocker = new GetBlocker( blockerLatch, KEY );
					try {
						cache.addListener( blocker );
						withinTx(tm, () -> region.get( null, KEY ));
					}
					catch (Exception e) {
						holder.addException(e);
					}
					finally {
						cache.removeListener( blocker );
					}
				}
			};

			Thread writer = new Thread() {
				@Override
				public void run() {
					try {
						writerLatch.await();
						withinTx(tm, () -> region.put( null, KEY, VALUE2 ));
					}
					catch (Exception e) {
						holder.addException(e);
					}
					finally {
						completionLatch.countDown();
					}
				}
			};

			reader.setDaemon( true );
			writer.setDaemon( true );

			boolean unblocked = false;
			try {
				reader.start();
				writer.start();

				assertFalse( "Reader is blocking", completionLatch.await( 100, TimeUnit.MILLISECONDS ) );
				// Start the writer
				writerLatch.countDown();
				assertTrue( "Writer finished promptly", completionLatch.await( 100, TimeUnit.MILLISECONDS ) );

				blockerLatch.countDown();
				unblocked = true;

				if ( IsolationLevel.REPEATABLE_READ.equals( cache.getCacheConfiguration().locking().isolationLevel() ) ) {
					assertEquals( VALUE1, withinTx(tm, () -> region.get( null, KEY )) );
				}
				else {
					assertEquals( VALUE2, withinTx(tm, () -> region.get( null, KEY )) );
				}

				holder.checkExceptions();
			}
			finally {
				if ( !unblocked ) {
					blockerLatch.countDown();
				}
			}
		});
	}

	@Test
	@TestForIssue(jiraKey = "HHH-7898")
	public void testPutDuringPut() throws Exception {
		withQueryRegion((regionFactory, region) -> {
			withinTx(tm, () -> region.put(null, KEY, VALUE1));
			assertEquals(VALUE1, withinTx(tm, () -> region.get(null, KEY)));

			AdvancedCache cache = getInfinispanCache(regionFactory);
			CountDownLatch blockerLatch = new CountDownLatch(1);
			CountDownLatch triggerLatch = new CountDownLatch(1);
			ExceptionHolder holder = new ExceptionHolder();

			Thread blocking = new Thread() {
				@Override
				public void run() {
					PutBlocker blocker = null;
					try {
						blocker = new PutBlocker(blockerLatch, triggerLatch, KEY);
						cache.addListener(blocker);
						withinTx(tm, () -> region.put(null, KEY, VALUE2));
					} catch (Exception e) {
						holder.addException(e);
					} finally {
						if (blocker != null) {
							cache.removeListener(blocker);
						}
						if (triggerLatch.getCount() > 0) {
							triggerLatch.countDown();
						}
					}
				}
			};

			Thread blocked = new Thread() {
				@Override
				public void run() {
					try {
						triggerLatch.await();
						// this should silently fail
						withinTx(tm, () -> region.put(null, KEY, VALUE3));
					} catch (Exception e) {
						holder.addException(e);
					}
				}
			};

			blocking.setName("blocking-thread");
			blocking.start();
			blocked.setName("blocked-thread");
			blocked.start();
			blocked.join();
			blockerLatch.countDown();
			blocking.join();

			holder.checkExceptions();

			assertEquals(VALUE2, withinTx(tm, () -> region.get(null, KEY)));
		});
	}

	@Listener
	public class GetBlocker {
		private final CountDownLatch latch;
		private final Object key;

		GetBlocker(CountDownLatch latch,	Object key) {
			this.latch = latch;
			this.key = key;
		}

		@CacheEntryVisited
		public void nodeVisisted(CacheEntryVisitedEvent event) {
			if ( event.isPre() && event.getKey().equals( key ) ) {
				try {
					latch.await();
				}
				catch (InterruptedException e) {
					log.error( "Interrupted waiting for latch", e );
				}
			}
		}
	}

	@Listener
	public class PutBlocker {
		private final CountDownLatch blockLatch, triggerLatch;
		private final Object key;
		private boolean enabled = true;

		PutBlocker(CountDownLatch blockLatch, CountDownLatch triggerLatch, Object key) {
			this.blockLatch = blockLatch;
			this.triggerLatch = triggerLatch;
			this.key = key;
		}

		@CacheEntryModified
		public void nodeVisisted(CacheEntryModifiedEvent event) {
			// we need isPre since lock is acquired in the commit phase
			if ( !event.isPre() && event.getKey().equals( key ) ) {
				try {
					synchronized (this) {
						if (enabled) {
							triggerLatch.countDown();
							enabled = false;
							blockLatch.await();
						}
					}
				}
				catch (InterruptedException e) {
					log.error( "Interrupted waiting for latch", e );
				}
			}
		}
	}

	private class ExceptionHolder {
		private final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
		private final List<AssertionFailedError> assertionFailures = Collections.synchronizedList(new ArrayList<>());

		public void addException(Exception e) {
			exceptions.add(e);
		}

		public void addAssertionFailure(AssertionFailedError e) {
			assertionFailures.add(e);
		}

		public void checkExceptions() throws Exception {
			for (AssertionFailedError a : assertionFailures) {
				throw a;
			}
			for (Exception e : exceptions) {
				throw e;
			}
		}
	}
}
