package com.composum.sling.platform.staging.query;

import org.apache.sling.api.resource.ResourceResolver;

import javax.jcr.Session;

public class QueryBuilderImpl implements QueryBuilder {

    private ResourceResolver resourceResolver;

    public QueryBuilderImpl(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @Override
    public Query createQuery() {
        return new Query(resourceResolver);
    }
}
