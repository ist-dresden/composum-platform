package com.composum.sling.platform.testing.testutil;

import com.composum.sling.core.mapping.MappingRules;
import com.composum.sling.core.util.JsonUtil;
import com.google.gson.stream.JsonWriter;
import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Some utility methods for JCR.
 * TODO: move somewhere more appropriate. Test project in platform?
 */
public class JcrTestUtils {

    /**
     * Prints a resource and its subresources as JSON, depth effectively unlimited.
     */
    public static void printResourceRecursivelyAsJson(@Nullable Resource resource) {
        if (resource != null) {
            try {
                StringWriter writer = new StringWriter();
                JsonWriter jsonWriter = new JsonWriter(writer);
                jsonWriter.setHtmlSafe(true);
                jsonWriter.setIndent("    ");
                JsonUtil.exportJson(jsonWriter, resource, MappingRules.getDefaultMappingRules(), 99);
                // ensure uninterrupted printing : wait for logmessages being printed, flush
                Thread.sleep(200);
                System.err.flush();
                System.out.flush();
                System.out.println("JCR TREE FOR " + resource.getPath());
                System.out.println(writer);
                System.out.flush();
            } catch (RepositoryException | IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            System.out.println("NO RESOURCE");
        }
    }

    /**
     * Uses the varargs mechanism to easily construct an array - shorter than e.g. new String[]{objects...}.
     */
    @SafeVarargs
    @Nonnull
    public static <T> T[] array(@Nonnull T... objects) {
        return objects;
    }

    @Nonnull
    public static List<Resource> ancestorsAndSelf(@Nullable Resource r) {
        List<Resource> list = new ArrayList<>();
        while (r != null) {
            list.add(r);
            r = r.getParent();
        }
        return list;
    }

}
