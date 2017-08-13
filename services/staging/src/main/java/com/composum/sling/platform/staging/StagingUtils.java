package com.composum.sling.platform.staging;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

public class StagingUtils {

    public static final String VERSIONS_ROOT = "/" + JcrConstants.JCR_SYSTEM + "/" + JcrConstants.JCR_VERSIONSTORAGE;

    @CheckReturnValue
    static boolean isInVersionStorage(@Nonnull Resource resource) {
        return resource.getPath().startsWith("/jcr:system/jcr:versionStorage");
    }

    @CheckReturnValue
    public static boolean isVersionable(@Nonnull Node node) throws RepositoryException {
        return node.isNodeType(NodeType.MIX_VERSIONABLE) || node.isNodeType(NodeType.MIX_SIMPLE_VERSIONABLE);
    }

    @CheckReturnValue
    public static boolean isVersionable(@Nonnull Resource resource) throws RepositoryException {
        if (ResourceUtil.isNonExistingResource(resource)) {
            return false;
        }
        Node n = resource.adaptTo(Node.class);
        return n != null && isVersionable(n);
    }

    @CheckReturnValue
    public static boolean isUnderVersionControl(@Nonnull Resource resource) throws RepositoryException {
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
    public static boolean isPropertyResource(@Nonnull final Resource resource) {
        return resource.getClass().getSimpleName().equals("JcrPropertyResource");
    }

    @CheckReturnValue
    public static boolean isRoot(@Nonnull final Resource resource) {
        return resource.getPath().equals("/");
    }

}
