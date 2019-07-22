package com.composum.sling.platform.staging.query;

import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.jcr.Session;

/**
 * Builder for {@link Query}. You can get a QueryBuilder with
 * {@link org.apache.sling.api.resource.ResourceResolver#adaptTo(Class)}.
 */
public interface QueryBuilder {

    /** Creates a new {@link Query}. */
    @Nonnull
    Query createQuery();

    /** Creates a new {@link Query}. */
    @Nonnull
    static Query makeQuery(ResourceResolver resourceResolver) {
        QueryBuilder builder = resourceResolver.adaptTo(QueryBuilder.class);
        if (builder == null) // can't normally happen
            throw new IllegalStateException("Cannot create query: QueryBuilderAdapterFactory not deployed?");
        return builder.createQuery();
    }

}
