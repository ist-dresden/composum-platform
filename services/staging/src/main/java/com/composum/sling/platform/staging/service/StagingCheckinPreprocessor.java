package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.service.VersionCheckinPreprocessor;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.version.VersionManager;
import java.util.List;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.ResourceUtil.*;

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
    public static final String PROP_SIBLINGSONCHECKIN = "pageSiblingsOnCheckin";

    @Override
    public void beforeCheckin(@Nonnull SlingHttpServletRequest request, @Nonnull JackrabbitSession session, VersionManager versionManager, @Nullable ResourceHandle resource) throws RepositoryException {
        if (null != resource && CONTENT_NODE.equals(resource.getName())
                && resource.getParent() != null && resource.getParent().getParent() != null
                && resource.isOfType(TYPE_UNSTRUCTURED) && resource.isOfType(TYPE_VERSIONABLE)) {
            List<Resource> pageSiblings = IteratorUtils.toList(resource.getParent().getParent().listChildren());
            List<String> siblingnames = pageSiblings.stream()
                    .map(Resource::getName)
                    .collect(Collectors.toList());
            resource.setProperty(PROP_SIBLINGSONCHECKIN, siblingnames);
            LOG.debug("On {} noting page siblings {}", resource.getPath(), siblingnames);
            session.save();
        }
    }

}
