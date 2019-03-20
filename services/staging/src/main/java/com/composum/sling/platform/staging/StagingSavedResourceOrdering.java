package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.service.StagingCheckinPreprocessor;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Determines the ordering of the children of {parentPath} from the data saved by {@link com.composum.sling.platform.staging.service.StagingCheckinPreprocessor}
 * in the {versionedChildrenList}.
 */
class StagingSavedResourceOrdering {

    private static final Logger LOG = LoggerFactory.getLogger(StagingSavedResourceOrdering.class);

    private final StagingResourceResolver resolver;

    private final Map<Long, List<String>> orderings;

    public StagingSavedResourceOrdering(@Nonnull String parentPath, @Nullable List<Resource> versionedChildrenList, @Nonnull StagingResourceResolver resourceResolver) {
        resolver = resourceResolver;
        if (versionedChildrenList != null) {
            orderings = new TreeMap<>();
            final Session session = resolver.adaptTo(Session.class);
            for (Resource versionedResource : versionedChildrenList) {
                String path = versionedResource.getValueMap().get("default", String.class);
                if (parentPath.equals(ResourceUtil.getParent(path))) {
                    ResourceHandle handle = ResourceHandle.use(versionedResource);
                    collect(handle, StagingCheckinPreprocessor.PROP_SIBLINGS_ON_CHECKIN, session);
                }
                if (parentPath.equals(ResourceUtil.getParent(path, 2))) {
                    ResourceHandle handle = ResourceHandle.use(versionedResource);
                    collect(handle, StagingCheckinPreprocessor.PROP_PARENT_SIBLINGS_ON_CHECKIN, session);
                }
            }
            LOG.debug("Ordering map for {} : {}", parentPath, orderings);
        } else {
            orderings = null;
        }
    }

    protected void collect(ResourceHandle handle, String propertyName, Session session) {
        LOG.debug("{}", handle.getPath());
        String uuid = handle.getProperty(JcrConstants.JCR_VERSIONLABELS + "/" + resolver.getReleasedLabel());
        if (StringUtils.isNotBlank(uuid)) {
            try {
                final Node taggedVersionNode = session.getNodeByIdentifier(uuid);
                Property versions = taggedVersionNode.getProperty(JcrConstants.JCR_FROZENNODE + "/" + propertyName);
                List<String> siblingnames = new ArrayList<>();
                for (Value value : versions.getValues()) {
                    siblingnames.add(value.getString());
                }
                long created = taggedVersionNode.getProperty(JcrConstants.JCR_CREATED).getDate().getTimeInMillis();
                orderings.put(created, siblingnames);
            } catch (RepositoryException e) { // shouldn't happen. Why?
                LOG.error("Failed reading " + propertyName + " for " + handle.getPath(), e);
            }
        }
    }
}
