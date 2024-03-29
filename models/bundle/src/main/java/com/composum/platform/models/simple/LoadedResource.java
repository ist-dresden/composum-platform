package com.composum.platform.models.simple;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * a resource wrapper implementation to support a preloaded resource model
 * (resolved by a service resolver - probably closed already on resource use; no lazy loading possible)
 */
@SuppressWarnings("unchecked")
public class LoadedResource extends ResourceWrapper {

    protected final String name;
    protected final String path;
    protected final String primaryType;
    protected final String resourceType;
    protected final String resourceSuperType;
    protected final LoadedValueMap properties;
    protected final LinkedHashMap<String, Resource> children;

    public class LoadedValueMap extends HashMap<String, Object> implements ValueMap {

        @Nullable
        public <T> T get(@NotNull String key, @NotNull Class<T> type) {
            int lastSlash = key.lastIndexOf('/');
            LoadedResource owner = lastSlash > 0
                    ? (LoadedResource) getChild(key.substring(0, lastSlash))
                    : LoadedResource.this;
            return owner != null ? (T) owner.properties.get(key.substring(lastSlash + 1)) : null;
        }

        @Override
        @NotNull
        public <T> T get(@NotNull String key, @NotNull T defaultValue) {
            T value = (T) get(key, defaultValue.getClass());
            return value != null ? value : defaultValue;
        }

        protected LoadedResource getResource(@NotNull String key) {
            String[] path = StringUtils.split(key, "/");
            LoadedResource resource = LoadedResource.this;
            for (int i = 0; resource != null && i < path.length - 1; i++) {
                resource = (LoadedResource) resource.getChild(path[i]);
            }
            return resource;
        }
    }

    public LoadedResource(@NotNull Resource resource) {
        super(resource);
        ValueMap valueMap = resource.getValueMap();
        name = resource.getName();
        path = resource.getPath();
        primaryType = valueMap.get("jcr:primaryType", "nt:unstructured");
        resourceType = resource.getResourceType();
        resourceSuperType = resource.getResourceSuperType();
        children = new LinkedHashMap<>();
        properties = new LoadedValueMap();
        load(resource);
    }

    protected void load(@NotNull Resource resource) {
        ValueMap valueMap = resource.getValueMap();
        for (String key : valueMap.keySet()) {
            properties.put(key, valueMap.get(key));
        }
        for (Resource child : resource.getChildren()) {
            children.put(child.getName(), new LoadedResource(child));
        }
    }

    @Override
    @NotNull
    public String getName() {
        return name;
    }

    @Override
    @NotNull
    public String getPath() {
        return path;
    }

    @Override
    @NotNull
    public String getResourceType() {
        return resourceType;
    }

    @Override
    public String getResourceSuperType() {
        return resourceSuperType;
    }

    @Override
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        return type.equals(ValueMap.class) ? (AdapterType) properties : super.adaptTo(type);
    }

    @Override
    @NotNull
    public ValueMap getValueMap() {
        return properties;
    }

    @Override
    @NotNull
    public Iterator<Resource> listChildren() {
        return children.values().iterator();
    }

    @Override
    @NotNull
    public Iterable<Resource> getChildren() {
        return children.values();
    }

    @Override
    public Resource getChild(@NotNull String relPath) {
        int firstSlash = relPath.indexOf('/');
        if (firstSlash > 0) {
            Resource child = children.get(relPath.substring(0, firstSlash));
            return child != null ? child.getChild(relPath.substring(firstSlash + 1)) : null;
        } else {
            return children.get(relPath);
        }
    }
}
