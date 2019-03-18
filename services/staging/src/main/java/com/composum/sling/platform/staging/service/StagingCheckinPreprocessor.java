package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.service.VersionCheckinPreprocessor;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.version.VersionManager;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@link VersionCheckinPreprocessor} that saves the order of the node within it siblings to enable the
 * {@link com.composum.sling.platform.staging.StagingResourceResolver} to restore the order of the children.
 * <p>
 * FIXME: only on cpp:PageContent? This is somewhat pages specific, but in platform
 */
@Component
public class StagingCheckinPreprocessor implements VersionCheckinPreprocessor {

    private static final Logger LOG = LoggerFactory.getLogger(StagingCheckinPreprocessor.class);

    /**
     * Saves the names of the siblings of the page of a checked in page in their order when the page
     * was checked in on the the page content node {@link #NODE_TYPE_PAGE_CONTENT}.
     */
    String PROP_SIBLINGSONCHECKIN = "pageSiblingsOnCheckin";

    @Override
    public void beforeCheckin(@Nonnull SlingHttpServletRequest request, @Nonnull JackrabbitSession session, VersionManager versionManager, @Nullable ResourceHandle resource) throws RepositoryException {
        if (null != resource && resource.isResourceType("cpp:PageContent")) {
            List<String> childnames = IteratorUtils.toList(resource.getParent().listChildren())
                    .stream()
                    .map(Resource::getName)
                    .collect(Collectors.toList());
            resource.setProperty(PROP_SIBLINGSONCHECKIN, childnames);

            try {
                Node config = versionManager.createConfiguration(resource.getPath());
                config.setProperty("before", new Date().toString());
            } catch (Exception e) {
                LOG.error("" + e, e);
            }
        }
    }

    @Override
    public void afterCheckin(SlingHttpServletRequest request, JackrabbitSession session, VersionManager versionManager, ResourceHandle resource) throws RepositoryException, PersistenceException {
        try {
            Node config = versionManager.createConfiguration(resource.getPath());
            config.setProperty("after", new Date().toString());
        } catch (Exception e) {
            LOG.error("" + e, e);
        }
    }
}
