package com.composum.sling.platform.staging.impl;

import com.composum.sling.platform.staging.StagingConstants;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import com.composum.sling.core.util.ResourceUtil;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

public class StagingUtils {

    /** {@value VERSIONS_ROOT} */
    public static final String VERSIONS_ROOT = "/" + JcrConstants.JCR_SYSTEM + "/" + JcrConstants.JCR_VERSIONSTORAGE;

    @CheckReturnValue
    public static boolean isInVersionStorage(@Nullable Resource resource) {
        return resource != null && resource.getPath().startsWith(VERSIONS_ROOT);
    }

    @CheckReturnValue
    public static boolean isVersionable(@Nullable Node node) throws RepositoryException {
        return node != null && node.isNodeType(NodeType.MIX_VERSIONABLE);
    }

    @CheckReturnValue
    public static boolean isVersionable(@Nullable Resource resource) throws RepositoryException {
        if (resource == null || ResourceUtil.isNonExistingResource(resource)) {
            return false;
        }
        Node n = resource.adaptTo(Node.class);
        return n != null && isVersionable(n);
    }

    @CheckReturnValue
    public static boolean isUnderVersionControl(@Nullable Resource resource) throws RepositoryException {
        if (resource == null) return false;
        if (isInVersionStorage(resource)) {
            return true;
        }
        if (isVersionable(resource)) {
            return true;
        }
        // now check if a parent isUnderVersionControl
        final Resource parent = resource.getParent();
        return parent != null && isUnderVersionControl(parent);
    }

    @CheckReturnValue
    public static boolean isVersionReference(@Nullable Resource resource) {
        return resource != null && ResourceUtil.isResourceType(resource, StagingConstants.TYPE_VERSIONREFERENCE);
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
