package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.Nullable;

import javax.annotation.CheckReturnValue;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import java.util.regex.Pattern;

import static com.composum.sling.platform.staging.StagingConstants.NODE_RELEASES;
import static com.composum.sling.platform.staging.StagingConstants.RELEASE_ROOT_PATH;

public class StagingUtils {

    /** {@value VERSIONS_ROOT} */
    public static final String VERSIONS_ROOT = "/" + JcrConstants.JCR_SYSTEM + "/" + JcrConstants.JCR_VERSIONSTORAGE;

    @CheckReturnValue
    public static boolean isInVersionStorage(@Nullable Resource resource) {
        return resource != null && resource.getPath().startsWith(VERSIONS_ROOT);
    }

    @CheckReturnValue
    public static boolean isInVersionStorage(@Nullable String path) {
        return path != null && path.startsWith(VERSIONS_ROOT);
    }

    public static final Pattern RELEASE_PATH_PATTERN = Pattern.compile("^" + RELEASE_ROOT_PATH + "(/.*)" + NODE_RELEASES + "/(.+)$");

    /** True if the resource is in version storage or if it's in a release. */
    public static boolean isInStorage(@Nullable Resource resource) {
        if (resource == null) { return false; }
        String path = resource.getPath();
        if (isInVersionStorage(path)) { return true; }
        return path.startsWith(RELEASE_ROOT_PATH) && RELEASE_PATH_PATTERN.matcher(path).matches();
    }

    /** Checks whether a node is the jcr:frozenNode that represents the original versionable in the version storage. */
    public static boolean isStoredVersionTopNode(@Nullable Resource resource) {
        if (!isInStorage(resource)) { return false; }
        return JcrConstants.JCR_FROZENNODE.equals(resource.getName()) &&
                ResourceUtil.isPrimaryType(resource.getParent(), JcrConstants.NT_VERSION);
    }

    /** Checks whether a node is the jcr:frozenNode that represents the original versionable in the version storage. */
    public static boolean isStoredVersionTopNode(@Nullable Node node) throws RepositoryException {
        if (!isInStorage(node)) { return false; }
        return JcrConstants.JCR_FROZENNODE.equals(node.getName()) &&
                node.getParent().getPrimaryNodeType().isNodeType(JcrConstants.NT_VERSION);
    }

    public static boolean isInStorage(@Nullable Node node) throws RepositoryException {
        if (node == null) { return false; }
        String path = node.getPath();
        if (path.startsWith(VERSIONS_ROOT)) { return true; }
        return path.startsWith(RELEASE_ROOT_PATH) && RELEASE_PATH_PATTERN.matcher(path).matches();
    }


    @CheckReturnValue
    public static boolean isPropertyResource(@Nullable final Resource resource) {
        return resource != null && resource.adaptTo(Property.class) != null;
    }

    @CheckReturnValue
    public static boolean isRoot(@Nullable final Resource resource) {
        return resource != null && resource.getPath().equals("/");
    }

}
