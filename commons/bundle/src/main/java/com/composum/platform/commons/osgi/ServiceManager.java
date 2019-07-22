/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.platform.commons.osgi;

import org.apache.commons.lang3.builder.CompareToBuilder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * an abstract implementation to manage a set of ranked OSGi services
 */
public abstract class ServiceManager<ServiceType> {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceManager.class);

    protected BundleContext bundleContext;

    protected List<ManagedReference> references = Collections.synchronizedList(new ArrayList<>());

    protected void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    // value service management

    protected class ManagedReference implements Comparable<ManagedReference> {

        public final ServiceReference<ServiceType> reference;
        public final long serviceId;
        public final int ranking;

        private transient ServiceType service;

        public ManagedReference(ServiceReference<ServiceType> reference) {
            this.reference = reference;
            this.serviceId = (Long) reference.getProperty(Constants.SERVICE_ID);
            final Object property = reference.getProperty(Constants.SERVICE_RANKING);
            this.ranking = property instanceof Integer ? (Integer) property : 0;
        }

        public ServiceType getService() {
            if (service == null) {
                service = bundleContext.getService(reference);
            }
            return service;
        }

        public long getServiceId() {
            return serviceId;
        }

        public int getRanking() {
            return ranking;
        }

        @Override
        public int compareTo(@Nonnull final ManagedReference other) {
            CompareToBuilder builder = new CompareToBuilder();
            builder.append(other.getRanking(), getRanking()); // sort descending
            builder.append(getServiceId(), other.getServiceId());
            return builder.toComparison();
        }

        // Object

        @Override
        @SuppressWarnings("unchecked")
        public boolean equals(Object other) {
            return other instanceof ServiceManager.ManagedReference
                    && ((ManagedReference) other).getServiceId() == getServiceId();
        }

        @Override
        public int hashCode() {
            return reference.hashCode();
        }
    }

    protected void bindReference(@Nonnull final ServiceReference<ServiceType> serviceReference) {
        final ManagedReference reference = new ManagedReference(serviceReference);
        LOG.info("bindReference: {}", reference);
        references.add(reference);
        Collections.sort(references);
    }

    protected void unbindReference(@Nonnull final ServiceReference<ServiceType> serviceReference) {
        final ManagedReference reference = new ManagedReference(serviceReference);
        LOG.info("unbindReference: {}", reference);
        references.remove(reference);
    }
}
