package com.composum.platform.models.simple;

import com.composum.sling.core.util.ValueEmbeddingReader;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;

/**
 * a 'meta data' hash map to replace placeholders in strings by the meta data values
 */
public class MetaData extends HashMap<String, Object> {

    protected static final Logger LOG = LoggerFactory.getLogger(MetaData.class);

    @Nonnull
    public String getValue(String value) {
        ValueEmbeddingReader reader = new ValueEmbeddingReader(new StringReader(value), this);
        String result = "";
        try {
            result = IOUtils.toString(reader);
        } catch (IOException ex) {
            LOG.error(ex.toString());
        }
        return result;
    }
}
