package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.platform.staging.query.Query;
import org.apache.sling.api.resource.ResourceResolver;
import org.mockito.Mockito;

import static org.junit.Assert.*;

/** Allows creating a JCR query using the {@link com.composum.sling.platform.staging.query.Query} DSL. */
public class PrintQuerySQL2 {

    public static void main(String[] args) {
        ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        Query query = new StagingQueryImpl(resolver);
        query.path("/preview").condition(query.conditionBuilder().contains("/content"));
        String sql2 = ((StagingQueryImpl) query).buildSQL2(Query.QueryGenerationMode.NORMAL);
        System.out.println(sql2);
    }

}
