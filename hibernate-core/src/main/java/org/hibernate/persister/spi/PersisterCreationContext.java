/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.persister.spi;

import org.hibernate.boot.spi.MetadataImplementor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.collection.spi.CollectionPersister;
import org.hibernate.persister.common.spi.DatabaseModel;
import org.hibernate.persister.entity.spi.EntityPersister;
import org.hibernate.persister.internal.PersisterFactoryImpl;

/**
 * "Parameter object" providing access to additional information that may be needed
 * in the creation of the persisters.
 *
 * @author Steve Ebersole
 */
public interface PersisterCreationContext {
	SessionFactoryImplementor getSessionFactory();
	MetadataImplementor getMetadata();
	DatabaseModel getDatabaseModel();

	void registerCollectionPersister(CollectionPersister collectionPersister);

	void registerEntityNameResolvers(EntityPersister entityPersister);
}
