/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.clustering.web.hotrod.session;

import java.time.Duration;

import javax.servlet.ServletContext;

import org.wildfly.clustering.Registrar;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.ee.cache.CacheProperties;
import org.wildfly.clustering.ee.cache.tx.TransactionBatch;
import org.wildfly.clustering.ee.hotrod.RemoteCacheManagerProperties;
import org.wildfly.clustering.ee.hotrod.tx.HotRodBatcher;
import org.wildfly.clustering.marshalling.spi.Marshallability;
import org.wildfly.clustering.marshalling.spi.MarshalledValueFactory;
import org.wildfly.clustering.marshalling.spi.MarshalledValueMarshaller;
import org.wildfly.clustering.web.IdentifierFactory;
import org.wildfly.clustering.web.cache.session.CompositeSessionFactory;
import org.wildfly.clustering.web.cache.session.CompositeSessionMetaDataEntry;
import org.wildfly.clustering.web.cache.session.SessionAttributesFactory;
import org.wildfly.clustering.web.cache.session.SessionFactory;
import org.wildfly.clustering.web.cache.session.SessionMetaDataFactory;
import org.wildfly.clustering.web.hotrod.session.coarse.CoarseSessionAttributesFactory;
import org.wildfly.clustering.web.hotrod.session.fine.FineSessionAttributesFactory;
import org.wildfly.clustering.web.session.SessionExpirationListener;
import org.wildfly.clustering.web.session.SessionManager;
import org.wildfly.clustering.web.session.SessionManagerConfiguration;
import org.wildfly.clustering.web.session.SessionManagerFactory;

/**
 * Factory for creating session managers.
 * @author Paul Ferraro
 */
public class HotRodSessionManagerFactory<L, C extends Marshallability> implements SessionManagerFactory<L, TransactionBatch> {

    final Registrar<SessionExpirationListener> expirationRegistrar;
    final Scheduler expirationScheduler;
    final Batcher<TransactionBatch> batcher;
    final Duration transactionTimeout;
    private final SessionFactory<CompositeSessionMetaDataEntry<L>, ?, L> sessionFactory;

    public HotRodSessionManagerFactory(HotRodSessionManagerFactoryConfiguration<C, L> config) {
        CacheProperties properties = new RemoteCacheManagerProperties(config.getCache().getRemoteCacheManager().getConfiguration());
        SessionMetaDataFactory<CompositeSessionMetaDataEntry<L>, L> metaDataFactory = new HotRodSessionMetaDataFactory<>(config.getCache(), properties);
        this.sessionFactory = new CompositeSessionFactory<>(metaDataFactory, this.createSessionAttributesFactory(config, properties), config.getLocalContextFactory());
        ExpiredSessionRemover<CompositeSessionMetaDataEntry<L>, ?, L> remover = new ExpiredSessionRemover<>(this.sessionFactory);
        this.expirationRegistrar = remover;
        this.expirationScheduler = new SessionExpirationScheduler(remover);
        this.batcher = new HotRodBatcher(config.getCache());
        this.transactionTimeout = Duration.ofMillis(config.getCache().getRemoteCacheManager().getConfiguration().transaction().timeout());
    }

    @Override
    public SessionManager<L, TransactionBatch> createSessionManager(SessionManagerConfiguration configuration) {
        HotRodSessionManagerConfiguration config = new HotRodSessionManagerConfiguration() {
            @Override
            public SessionExpirationListener getExpirationListener() {
                return configuration.getExpirationListener();
            }

            @Override
            public Registrar<SessionExpirationListener> getExpirationRegistrar() {
                return HotRodSessionManagerFactory.this.expirationRegistrar;
            }

            @Override
            public Scheduler getExpirationScheduler() {
                return HotRodSessionManagerFactory.this.expirationScheduler;
            }

            @Override
            public IdentifierFactory<String> getIdentifierFactory() {
                return configuration.getIdentifierFactory();
            }

            @Override
            public ServletContext getServletContext() {
                return configuration.getServletContext();
            }

            @Override
            public Batcher<TransactionBatch> getBatcher() {
                return HotRodSessionManagerFactory.this.batcher;
            }

            @Override
            public Duration getStopTimeout() {
                return HotRodSessionManagerFactory.this.transactionTimeout;
            }
        };
        return new HotRodSessionManager<>(this.sessionFactory, config);
    }

    @Override
    public void close() {
        this.expirationScheduler.close();
    }

    private SessionAttributesFactory<?> createSessionAttributesFactory(HotRodSessionManagerFactoryConfiguration<C, L> configuration, CacheProperties properties) {
        MarshalledValueFactory<C> factory = configuration.getMarshalledValueFactory();
        C context = configuration.getMarshallingContext();

        switch (configuration.getAttributePersistenceStrategy()) {
            case FINE: {
                return new FineSessionAttributesFactory<>(configuration.getCache(), configuration.getCache(), new MarshalledValueMarshaller<>(factory, context), configuration.getImmutability(), properties);
            }
            case COARSE: {
                return new CoarseSessionAttributesFactory<>(configuration.getCache(), new MarshalledValueMarshaller<>(factory, context), configuration.getImmutability(), properties);
            }
            default: {
                // Impossible
                throw new IllegalStateException();
            }
        }
    }
}
