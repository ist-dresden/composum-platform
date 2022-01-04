package com.composum.sling.platform.staging.impl;

import org.apache.sling.api.resource.ResourceMetadata;
import org.jetbrains.annotations.NotNull;

import javax.annotation.CheckForNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper for {@link ResourceMetadata} overriding the {@link ResourceMetadata#RESOLUTION_PATH}
 * and {@link ResourceMetadata#RESOLUTION_PATH_INFO} with specified values.
 */
public class StagingResourceMetadata extends ResourceMetadata {

    @NotNull
    private final ResourceMetadata frozenMetaData;
    private final String calculatedResolutionPath;
    private final String calculatedResolutionPathInfo;

    StagingResourceMetadata(final @NotNull ResourceMetadata frozenMetaData,
                            final String calculatedResolutionPath,
                            final String calculatedResolutionPathInfo) {
        this.frozenMetaData = frozenMetaData;
        this.calculatedResolutionPath = calculatedResolutionPath;
        this.calculatedResolutionPathInfo = calculatedResolutionPathInfo;
    }

    @Override
    @NotNull
    public Set<Map.Entry<String, Object>> entrySet() {
        final Set<Map.Entry<String, Object>> entries = frozenMetaData.entrySet();
        for (Map.Entry<String, Object> entry : entries) {
            if (entry.getKey().equals(RESOLUTION_PATH)) {
                entry.setValue(calculatedResolutionPath);
            } else if (entry.getKey().equals(RESOLUTION_PATH_INFO)) {
                entry.setValue(calculatedResolutionPathInfo);
            }
        }
        return entries;
    }

    @Override
    @NotNull
    public Set<String> keySet() {
        return frozenMetaData.keySet();
    }

    @Override
    @NotNull
    public Collection<Object> values() {
        final Collection<Object> results = new ArrayList<>();
        final Set<Map.Entry<String, Object>> entries = frozenMetaData.entrySet();
        for (Map.Entry<String, Object> entry : entries) {
            if (entry.getKey().equals(RESOLUTION_PATH)) {
                results.add(calculatedResolutionPath);
            } else if (entry.getKey().equals(RESOLUTION_PATH_INFO)) {
                results.add(calculatedResolutionPathInfo);
            } else {
                results.add(entry.getValue());
            }
        }
        return results;
    }

    @Override
    @CheckForNull
    public Object get(Object key) {
        if (key.equals(RESOLUTION_PATH)) {
            return calculatedResolutionPath;
        } else if (key.equals(RESOLUTION_PATH_INFO)) {
            return calculatedResolutionPathInfo;
        } else {
            return super.get(key);
        }
    }
}
