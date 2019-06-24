package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import java.util.regex.Pattern;

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

    private static final Pattern IN_STORAGE_PATTERN = Pattern.compile(".*/" + StagingConstants.NODE_RELEASES + "/[^/]+/" + StagingConstants.NODE_RELEASE_ROOT + "(/.*|$)");

    /** True if the resource is in version storage or if it's in a release. */
    public static boolean isInStorage(@Nullable Resource resource) {
        if (resource == null) return false;
        if (isInVersionStorage(resource))
            return true;
        if (!IN_STORAGE_PATTERN.matcher(resource.getPath()).matches())
            return false;
        Resource siteCandidate = resource;
        while (!siteCandidate.getName().equals(StagingConstants.NODE_RELEASES))
            siteCandidate = siteCandidate.getParent();
        if (siteCandidate.getParent().getParent() == null)
            return false;
        siteCandidate = siteCandidate.getParent().getParent();
        boolean result = ResourceUtil.isNodeType(siteCandidate, StagingConstants.TYPE_MIX_RELEASE_ROOT);
        return result;
    }

    public static boolean isInStorage(@Nullable Node node) throws RepositoryException {
        if (node == null) return false;
        if (node.isNodeType("nt:frozenNode"))
            return true;
        if (!IN_STORAGE_PATTERN.matcher(node.getPath()).matches())
            return false;
        Node siteCandidate = node;
        while (!siteCandidate.getName().equals(StagingConstants.NODE_RELEASES))
            siteCandidate = siteCandidate.getParent();
        if (siteCandidate.getParent().getParent() == null)
            return false;
        siteCandidate = siteCandidate.getParent().getParent();
        boolean result = siteCandidate.isNodeType(StagingConstants.TYPE_MIX_RELEASE_ROOT);
        return result;
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
