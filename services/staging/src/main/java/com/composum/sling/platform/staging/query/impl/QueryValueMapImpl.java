package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.core.util.PropertyUtil;
import com.composum.sling.platform.staging.query.Query;
import com.composum.sling.platform.staging.query.QueryValueMap;
import org.apache.commons.lang3.Validate;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.query.Row;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Implementation of {@link QueryValueMap} for the {@link com.composum.sling.platform.staging.impl.StagingResourceResolver}. */
public class QueryValueMapImpl extends AbstractMap<String, Object> implements QueryValueMap {

    /** An explicit selector that still contains the quoting characters. */
    protected final Pattern UNNORMALIZEDKEY = Pattern.compile("(\\w+)\\.\\[([\\w:]+)\\]");

    private final StagingQueryImpl query;
    private final Row row;
    private final List<String> columns;
    private Resource resource;
    private Map<String, Object> entries;

    QueryValueMapImpl(StagingQueryImpl query, Row row, String... columns) {
        this.query = query;
        this.row = row;
        this.columns = new ArrayList<>();
        for (String column : columns) {
            this.columns.add(column);
            String normalized = normalizeKey(column);
            if (!normalized.equals(column)) this.columns.add(normalized);
        }
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        if (entries == null) {
            entries = new LinkedHashMap<>();
            for (String column : columns) {
                String normalizedColumn = normalizeKey(column);
                Object value = get(normalizedColumn, Object.class);
                if (null != value) {
                    entries.put(column, value);
                    if (!normalizedColumn.equals(column)) entries.put(normalizedColumn, value);
                }
            }
        }
        return entries.entrySet();
    }

    /**
     * Normalizes keys like "m.[jcr:path]" to "m.jcr:path", as returned in the {@link javax.jcr.query.Query} results .
     */
    protected String normalizeKey(String key) {
        Matcher m = UNNORMALIZEDKEY.matcher(key);
        if (m.matches()) {
            return m.group(1) + "." + m.group(2);
        }
        return key;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when a {@link RepositoryException} occurs
     */
    @CheckForNull
    @Override
    public <T> T get(@Nonnull String name, @Nonnull Class<T> type) throws IllegalArgumentException {
        if (!columns.contains(name))
            throw new IllegalArgumentException("Trying to access column " + name + " that was not selected.");
        String normalized = normalizeKey(name);
        String columnname = normalized.contains(".") ? normalized : "n." + name;
        if (Query.COLUMN_EXCERPT.equals(name)) columnname = name;
        if (Query.COLUMN_PATH.equals(name) || columnname.endsWith("." + Query.COLUMN_PATH)) {
            Validate.isTrue(String.class.equals(type) || Object.class.equals(type),
                    "For " + name + " only type String is supported.");
            String path = query.getString(row, columnname);
            if (path.startsWith("/jcr:system/jcr:versionStorage") && null != query.release.getNumber() && !query.getPath()
                    .startsWith("/jcr:system/jcr:versionStorage")) {
                // create real historical path for something contained in a release
                String originalPath = query.getString(row, "query:originalPath");
                path = originalPath + path.substring(path.indexOf("jcr:frozenNode") + "jcr:frozenNode".length());
            }
            return type.cast(path);
        }

        try {
            return PropertyUtil.readValue(row.getValue(columnname), type);
        } catch (RepositoryException e) {
            throw new IllegalArgumentException("Could not select " + name, e);
        }
    }

    @Override
    public <T> T get(@Nonnull String name, T defaultValue) {
        T realValue = get(name, PropertyUtil.getType(defaultValue));
        return null != realValue ? realValue : defaultValue;
    }

    /** Returns the found resource the values are from. Could be null in case of right outer join. */
    @Override
    public Resource getResource() {
        return resource = null == resource ? getJoinResource("n") : resource;
    }

    /** In case of a join, returns the found resource for the given join selector. */
    @Override
    public Resource getJoinResource(String selector) {
        return query.getResource(row, selector);
    }

}
