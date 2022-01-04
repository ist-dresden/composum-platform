package com.composum.sling.platform.testing.testutil;

import com.composum.sling.core.mapping.MappingRules;
import com.composum.sling.core.util.JsonUtil;
import com.google.gson.stream.JsonWriter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Some utility methods for JCR.
 * TODO: remove once replacement is updated.
 *
 * @deprecated moved to com.composum.sling.test.util.JcrTestUtils in nodes
 */
@Deprecated
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
                jsonWriter.setLenient(true);
                jsonWriter.setSerializeNulls(false);
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
     * Prints a resource and its subresources as JSON, depth effectively unlimited.
     */
    public static void printResourceRecursivelyAsJson(@Nullable ResourceResolver resolver, @Nullable String path) {
        if (resolver == null) {
            System.out.println("NO RESOLVER for printing resource");
        } else if (path == null) {
            System.out.println("INVALID NULL PATH");
        } else {
            Resource resource = resolver.getResource(path);
            if (resource != null) {
                printResourceRecursivelyAsJson(resource);
            } else {
                System.out.println("NO RESOURCE at " + path);
            }
        }
    }

    /**
     * Uses the varargs mechanism to easily construct an array - shorter than e.g. new String[]{objects...}.
     */
    @SafeVarargs
    @NotNull
    public static <T> T[] array(@NotNull T... objects) {
        return objects;
    }

    @NotNull
    public static List<Resource> ancestorsAndSelf(@Nullable Resource r) {
        List<Resource> list = new ArrayList<>();
        while (r != null) {
            list.add(r);
            r = r.getParent();
        }
        return list;
    }

}
