package com.composum.sling.platform.staging.query;

import com.composum.sling.core.util.PropertyUtil;
import org.apache.commons.lang3.Validate;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import javax.jcr.query.Row;
import java.util.*;

/**
 * Encapsulates the result of a query for specific properties and pseudo-properties. This contains only the queried
 * subset of properties of the queried resource. The {@link Map} functionality gives the entries with their default Java
 * types.
 *
 * @see Query#selectAndExecute(String...)
 */
public class QueryValueMap extends AbstractMap<String, Object> implements ValueMap {

    private final Query query;
    private final Row row;
    private final List<String> columns;
    private Resource resource;
    private Map<String, Object> entries;

    QueryValueMap(Query query, Row row, String... columns) {
        this.query = query;
        this.row = row;
        this.columns = Arrays.asList(columns);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        if (entries == null) {
            entries = new LinkedHashMap<>();
            for (String column : columns) {
                Object value = get(column, Object.class);
                if (null != value) entries.put(column, value);
            }
        }
        return entries.entrySet();
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
        String columnname = "n." + name;
        if (Query.COLUMN_EXCERPT.equals(name)) columnname = name;

        if (Query.COLUMN_PATH.equals(name)) {
            Validate.isTrue(String.class.equals(type) || Object.class.equals(type),
                    "For " + name + " only type String is supported.");
            String path = query.getString(row, "n.jcr:path");
            if (path.startsWith("/jcr:system/jcr:versionStorage") && null != query.releasedLabel && !query.path
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

    /** Returns the found resource the values are from. */
    public Resource getResource() {
        return resource = null == resource ? query.getResource(row) : resource;
    }

}
