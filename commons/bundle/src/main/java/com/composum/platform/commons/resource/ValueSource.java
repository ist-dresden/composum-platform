package com.composum.platform.commons.resource;

import com.composum.sling.core.util.ValueEmbeddingReader;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

/**
 * a map which provides the values for strings with embedded value references (${...})
 */
public class ValueSource extends ValueMapDecorator {

    private static final Logger LOG = LoggerFactory.getLogger(ValueSource.class);

    public ValueSource(Map<String,Object> values) {
        super(values);
    }

    /**
     * prepare a string value by resolving value references (${...}) using the values of this source
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T prepare(@Nullable T value) {
        if (value instanceof String) {
            ValueEmbeddingReader reader = new ValueEmbeddingReader(new StringReader((String) value), this);
            try {
                value = (T) IOUtils.toString(reader);
            } catch (IOException ex) {
                LOG.error(ex.toString());
            }
        }
        return value;
    }
}
