package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.platform.staging.query.QueryBuilder;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import static org.apache.sling.api.adapter.AdapterFactory.ADAPTABLE_CLASSES;
import static org.apache.sling.api.adapter.AdapterFactory.ADAPTER_CLASSES;

/**
 * Factory to create {@link QueryBuilder} from {@link org.apache.sling.api.resource.ResourceResolver}.
 */
@Component(
        label = "Composum StagingResolver Adapter to QueryBuilder",
        description = "Adapts ResourceFactories to QueryBuilders")
@Properties({
        @Property(name = "service.description", value = "Composum StagingResolver Adapter to QueryBuilder"),
        @Property(name = ADAPTABLE_CLASSES, value = "org.apache.sling.api.resource.ResourceResolver",
                propertyPrivate = true),
        @Property(name = ADAPTER_CLASSES, value = "com.composum.sling.platform.staging.query.QueryBuilder",
                propertyPrivate = true)
})
@Service
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
