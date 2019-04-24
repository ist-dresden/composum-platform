package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.platform.staging.query.Query;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.impl.StagingQueryImpl;
import org.apache.sling.api.resource.ResourceResolver;

public class QueryBuilderImpl implements QueryBuilder {

    private ResourceResolver resourceResolver;

    public QueryBuilderImpl(ResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @Override
    public Query createQuery() {
        return new StagingQueryImpl(resourceResolver);
    }
}
