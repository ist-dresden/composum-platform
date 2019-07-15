package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Specialization of {@link NodeTreeSynchronizer} that replaces versionable nodes by cpl:VersionReference .
 * @deprecated not used in release mechanism after all
 * // TODO hps 2019-04-09 remove this once unused
 */
@Deprecated
public class ReleaseTreeSynchronizer extends NodeTreeSynchronizer {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseTreeSynchronizer.class);

    @Override
    public void update(@Nonnull Resource from, @Nonnull Resource to) throws RepositoryException, PersistenceException {
        moveVersionReferences(from, to);
        super.update(from, to);
    }

    /**
     * We move {@link com.composum.sling.platform.staging.StagingConstants#TYPE_VERSIONREFERENCE} nodes in {to} to the places
     * where the corresponding {@link com.composum.sling.platform.staging.StagingConstants#TYPE_VERSIONREFERENCE} or
     * {@link com.composum.sling.core.util.ResourceUtil#TYPE_VERSIONABLE}'s are. Thus they get updated instead of deleted
     * by {@link NodeTreeSynchronizer#update(Resource, Resource)}.
     */
    protected void moveVersionReferences(Resource from, Resource to) throws RepositoryException {
        Map<String, String> fromVersionables = findAttributeOccurrences(from, ResourceUtil.TYPE_VERSIONABLE, JcrConstants.JCR_VERSIONHISTORY);
        Map<String, String> fromVersionrefs = findAttributeOccurrences(from, StagingConstants.TYPE_VERSIONREFERENCE, StagingConstants.PROP_VERSIONHISTORY);
        Map<String, String> toVersionrefs = findAttributeOccurrences(to, StagingConstants.TYPE_VERSIONREFERENCE, StagingConstants.PROP_VERSIONHISTORY);
        for (Map<String, String> fromNodes : Arrays.asList(fromVersionables, fromVersionrefs)) {
            for (Map.Entry<String, String> entry : fromNodes.entrySet()) {
                String toRelPath = toVersionrefs.get(entry.getKey());
                String fromRelPath = entry.getValue();
                if (toRelPath != null && !toRelPath.equals(fromRelPath)) {
                    String newPath = to.getPath() + "/" + fromRelPath;
                    String newPathParent = ResourceUtil.getParent(newPath);
                    Resource parent = ResourceUtil.getOrCreateResource(to.getResourceResolver(), newPathParent);
                    Session session = to.getResourceResolver().adaptTo(Session.class);
                    if (session == null) throw new IllegalStateException("No session for " + to); // hell froze over.
                    session.move(to.getPath() + "/" + toRelPath, newPath);
                }
            }
        }
    }

    @Override
    protected void updateSubtree(@Nonnull ResourceHandle from, @Nonnull ResourceHandle to) throws RepositoryException, PersistenceException {
        if (from.isOfType(ResourceUtil.TYPE_VERSIONABLE) || from.isOfType(StagingConstants.TYPE_VERSIONREFERENCE)) {
            updateVersionReference(from, to);
        } else {
            super.updateSubtree(from, to);
        }
    }

    /**
     * Ensure {from} is a version reference to {to} if it is a versionable,
     * or a version reference referencing the same versionable as {from} if {from} is a version reference.
     * Updating the version references is done explicitly in the ReleaseManager.
     * Cases:
     * <ul>
     * <li>{from} is a versionable:
     * <ul>
     * <li>if {to} is not a version reference or a version reference of a different versionable, clear it and initialize it from {from}</li>
     * <li>otherwise ({to} is version reference to {from}) do nothing</li>
     * </ul>
     * </li>
     * <li>{from} is a version reference:
     * <ul>
     * <li>if {to} is not a version reference or a version reference of a different versionable, clear it and copy stuff from {from}.</li>
     * <li>otherwise ({to} is a version reference from the same versionable): do nothing</li>
     * </ul>
     * </li>
     * </ul>
     */
    protected void updateVersionReference(ResourceHandle from, ResourceHandle to) throws RepositoryException, PersistenceException {
        if (to.isOfType(StagingConstants.TYPE_VERSIONREFERENCE) &&
                StringUtils.equals(getReferencedVersionHistory(from), getReferencedVersionHistory(to))) {
            LOG.debug("Is already reference to same versionable - do nothing: {}", to.getPath());
        } else {
            if (from.isOfType(StagingConstants.TYPE_VERSIONREFERENCE)) {
                super.updateSubtree(from, to);
            } else {
                // initialize fresh version reference.
                to.setProperty(ResourceUtil.PROP_PRIMARY_TYPE, StagingConstants.TYPE_VERSIONREFERENCE);
                to.setProperty(ResourceUtil.PROP_MIXINTYPES, Collections.emptyList());
                // if we turned a previously unversionable node into versionable, there might be already properties and children,
                // which are obsolete since they are now versioned. Remove this stuff.
                clearNode(to);
                updateVersionAttributes(from, to);
            }
        }
    }

    protected String getReferencedVersionHistory(ResourceHandle resource) {
        return resource.isOfType(StagingConstants.TYPE_VERSIONREFERENCE) ? resource.getProperty(StagingConstants.PROP_VERSIONHISTORY, String.class)
                : resource.getProperty(JcrConstants.JCR_VERSIONHISTORY);
    }

    protected void updateVersionAttributes(ResourceHandle from, ResourceHandle to) throws RepositoryException, PersistenceException {
        if (to.getProperty(StagingConstants.PROP_VERSIONHISTORY) != null && !
                StringUtils.equals(getReferencedVersionHistory(from), getReferencedVersionHistory(to)))
            throw new IllegalArgumentException("Abuse: set attributes from different versionable? " + from.getPath() + " to " + to.getPath());

        if (from.isOfType(ResourceUtil.TYPE_VERSIONABLE)) {
            LOG.debug("Set from versionable: {}", to.getPath());
            to.setProperty(StagingConstants.PROP_VERSIONHISTORY, from.getProperty("jcr:versionHistory"), PropertyType.REFERENCE);
            to.setProperty(StagingConstants.PROP_VERSION, from.getProperty("jcr:baseVersion"), PropertyType.REFERENCE);
            to.setProperty(StagingConstants.PROP_VERSIONABLEUUID, from.getProperty(JcrConstants.JCR_UUID), PropertyType.WEAKREFERENCE);
            to.setProperty(StagingConstants.PROP_DEACTIVATED, false);
        } else if (from.isOfType(StagingConstants.TYPE_VERSIONREFERENCE)) {
            to.setProperty(StagingConstants.PROP_VERSIONHISTORY, from.getProperty(StagingConstants.PROP_VERSIONHISTORY), PropertyType.REFERENCE);
            to.setProperty(StagingConstants.PROP_VERSION, from.getProperty(StagingConstants.PROP_VERSION), PropertyType.REFERENCE);
            to.setProperty(StagingConstants.PROP_VERSIONABLEUUID, from.getProperty(StagingConstants.PROP_VERSIONABLEUUID), PropertyType.WEAKREFERENCE);
            to.setProperty(StagingConstants.PROP_DEACTIVATED, from.getProperty(StagingConstants.PROP_VERSIONABLEUUID, false));
        } else
            throw new IllegalArgumentException("Bug: should be versionable or versionreference for this method: " + from);
    }

    /**
     * Removes children and attributes from a node, except the primary type and mixins and the mandatory version attributes.
     */
    protected void clearNode(ResourceHandle to) throws RepositoryException, PersistenceException {
        LOG.debug("Clearing {}", to.getPath());
        for (String key : new LinkedHashSet<>(to.getValueMap().keySet())) {
            if (!ResourceUtil.PROP_PRIMARY_TYPE.equals(key) && !ResourceUtil.PROP_MIXINTYPES.equals(key) &&
                    !StagingConstants.PROP_VERSIONABLEUUID.equals(key) &&
                    !StagingConstants.PROP_VERSION.equals(key) &&
                    !StagingConstants.PROP_VERSIONHISTORY.equals(key))
                try {
                    to.setProperty(key, (String) null); // remove other properties
                } catch (RepositoryException e) {
                    LOG.info("Shouldn't happen at " + to.getPath() + " , " + key, e);
                }
        }
        for (Resource child : to.getChildren()) {
            to.getResourceResolver().delete(child);
        }
    }

    /**
     * Finds occurrences of attribute {attribute} on node types {type} below {path} : maps attribute value to path.
     */
    protected Map<String, String> findAttributeOccurrences(Resource rootNode, String type, String attribute) {
        String query = "SELECT n.[jcr:path], n.[" + attribute + "] FROM [" + type + "] AS n WHERE ISDESCENDANTNODE(n, \"" + rootNode.getPath() + "\")";
        Iterator<Map<String, Object>> result = rootNode.getResourceResolver().queryResources(query, Query.JCR_SQL2);
        List<Map<String, Object>> resultList = IteratorUtils.toList(result);
        return resultList.stream()
                .collect(Collectors.toMap(m -> (String) m.get("n." + attribute), m -> relativePath(rootNode, (String) m.get("n.jcr:path"))));

    }

    protected String relativePath(Resource root, String absolutePath) {
        if (!StringUtils.startsWith(absolutePath + "/", root.getPath()))
            throw new IllegalArgumentException("Bug: " + absolutePath + " doesn't start with " + root.getPath());
        return StringUtils.removeStart(absolutePath, root.getPath() + "/");
    }

    /** Skip {@value StagingConstants#TYPE_MIX_RELEASE_CONFIG} to avoid copying the release config into releases. */
    @Override
    protected boolean skipNode(@Nonnull Resource from) {
        // FIXME(hps,2019-07-15) this does not work like that anymore.
        return false;
        // return super.skipNode(from) || ResourceHandle.use(from).isOfType(StagingConstants.TYPE_MIX_RELEASE_CONFIG);
    }

}
