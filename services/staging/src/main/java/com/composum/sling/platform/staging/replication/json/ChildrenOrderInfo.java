package com.composum.sling.platform.staging.replication.json;

import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;

/**
 * Information about the order of children of one node, for use with JSON transmission.
 */
public class ChildrenOrderInfo {

    private static final Logger LOG = LoggerFactory.getLogger(ChildrenOrderInfo.class);

    private String path;

    private List<String> childNames;

    /**
     * The path of a node whose children order is relevant.
     */
    @Nonnull
    public String getPath() {
        return path;
    }

    /**
     * The names of the children of node {@link #getPath()}, in the ordering they appear in the resource tree. We only
     * transmit this if they are orderable and if there is more than one.
     */
    @Nonnull
    public List<String> getChildNames() {
        return childNames;
    }

    /**
     * Collects the information about the children of one resource - if it has orderable children and more than one
     * child. Otherwise null is returned.
     */
    @Nullable
    public static ChildrenOrderInfo of(@Nullable Resource resource) {
        ChildrenOrderInfo result = null;
        if (hasOrderableChildren(resource)) {
            List<String> childNames = new ArrayList<>();
            for (Resource child : resource.getChildren()) {
                childNames.add(child.getName());
            }
            if (childNames.size() > 1) {
                result = new ChildrenOrderInfo();
                result.path = resource.getPath();
                result.childNames = childNames;
            }
        }
        return result;
    }

    protected static boolean hasOrderableChildren(@Nullable Resource resource) {
        if (resource == null) {
            return false;
        }
        Node node = resource.adaptTo(Node.class);
        if (node == null) { // no way to find out, so we play it safe and assume it has orderable children. :-/
            return true; // Shouldn't happen.
        }
        try {
            boolean hasOrderableChildNodes = node.getPrimaryNodeType().hasOrderableChildNodes();
            return hasOrderableChildNodes;
        } catch (RepositoryException e) {
            LOG.error("Trouble determining child orderability for {}", SlingResourceUtil.getPath(resource), e);
            return true; // play safe.
        }
    }

    @Override
    public String toString() {
        return "ChildrenOrderInfo{" +
                "path='" + path + '\'' +
                ", childNames=" + childNames +
                '}';
    }
}
