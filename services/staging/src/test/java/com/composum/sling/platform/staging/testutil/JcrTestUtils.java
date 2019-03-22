package com.composum.sling.platform.staging.testutil;

import com.composum.sling.core.mapping.MappingRules;
import com.composum.sling.core.util.JsonUtil;
import com.google.gson.stream.JsonWriter;
import org.apache.sling.api.resource.Resource;

import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.StringWriter;

/**
 * Some utility methods for JCR.
 * TODO: move somewhere more appropriate. Test project in platform?
 */
public class JcrTestUtils {

    /**
     * Prints a resource and its subresources as JSON, depth effectively unlimited.
     */
    public static void printResourceRecursivelyAsJson(Resource resource) {
        try {
            StringWriter writer = new StringWriter();
            JsonWriter jsonWriter = new JsonWriter(writer);
            jsonWriter.setHtmlSafe(true);
            jsonWriter.setIndent("    ");
            JsonUtil.exportJson(jsonWriter, resource, MappingRules.getDefaultMappingRules(), 99);
            System.out.println(writer);
        } catch (RepositoryException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Uses the varargs mechanism to easily construct an array - shorter than e.g. new String[]{objects...}.
     */
    @SafeVarargs
    public static <T> T[] array(T... objects) {
        return objects;
    }

}
