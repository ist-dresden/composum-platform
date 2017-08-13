package com.composum.sling.platform.staging.query;

import javax.jcr.Session;

/**
 * Builder for {@link Query}. You can get a QueryBuilder with
 * {@link org.apache.sling.api.resource.ResourceResolver#adaptTo(Class)}.
 */
public interface QueryBuilder {

    /** Creates a new {@link Query}. */
    Query createQuery();

}
