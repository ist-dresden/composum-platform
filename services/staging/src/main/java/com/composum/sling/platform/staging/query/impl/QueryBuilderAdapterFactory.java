package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.platform.staging.query.QueryBuilder;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import static org.apache.sling.api.adapter.AdapterFactory.ADAPTABLE_CLASSES;
import static org.apache.sling.api.adapter.AdapterFactory.ADAPTER_CLASSES;

/**
 * Factory to create {@link QueryBuilder} from {@link org.apache.sling.api.resource.ResourceResolver}.
 * <p>
 * In tests you might need to register this:
 * <code>
 * context.registerAdapter(ResourceResolver.class, QueryBuilder.class,
 * (Function) (resolver) ->
 * new QueryBuilderAdapterFactory().getAdapter(resolver, QueryBuilder.class));
 * </code>
 */
@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum StagingResolver Adapter to QueryBuilder",
                Constants.SERVICE_RANKING + ":Integer=1500",
                AdapterFactory.ADAPTABLE_CLASSES + "=org.apache.sling.api.resource.ResourceResolver",
                AdapterFactory.ADAPTER_CLASSES + "=com.composum.sling.platform.staging.query.QueryBuilder"
        },
        service = AdapterFactory.class,
        immediate = true
)
public class QueryBuilderAdapterFactory implements AdapterFactory {

    @CheckForNull
    @Override
    public <AdapterType> AdapterType getAdapter(@Nonnull Object adaptable, @Nonnull Class<AdapterType> type) {
        if (type.equals(QueryBuilder.class) && adaptable instanceof ResourceResolver) {
            return type.cast(new QueryBuilderImpl((ResourceResolver) adaptable));
        }
        return null;
    }
}
