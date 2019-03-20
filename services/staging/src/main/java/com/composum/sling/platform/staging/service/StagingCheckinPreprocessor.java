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
     * Saves an ordered list of the names of the siblings of the parent of a checked in jcr:content.
     */
    public static final String PROP_PARENT_SIBLINGS_ON_CHECKIN = "parentSiblingsOnCheckin";

    /**
     * Saves an ordered list of the names of the siblings of a checked in jcr:content.
     */
    public static final String PROP_SIBLINGS_ON_CHECKIN = "siblingsOnCheckin";

    @Override
    public void beforeCheckin(@Nonnull SlingHttpServletRequest request, @Nonnull JackrabbitSession session, VersionManager versionManager, @Nullable ResourceHandle resource) throws RepositoryException {
        if (null != resource && CONTENT_NODE.equals(resource.getName())
                && resource.isOfType(TYPE_UNSTRUCTURED) && resource.isOfType(TYPE_VERSIONABLE)) {
            ResourceHandle parent = resource.getParent();
            if (parent != null) {
                List<String> siblingnames = collectChildrensNames(parent);
                resource.setProperty(PROP_SIBLINGS_ON_CHECKIN, siblingnames);
                List<String> parentsiblingnames = null;
                ResourceHandle greatparent = parent.getParent();
                if (greatparent != null) {
                    parentsiblingnames = collectChildrensNames(greatparent);
                    resource.setProperty(PROP_PARENT_SIBLINGS_ON_CHECKIN, parentsiblingnames);
                }
                LOG.debug("On {} noting page siblings {} and parent siblings {}", resource.getPath(), siblingnames, parentsiblingnames);
                session.save();
            }
        }
    }

    protected List<String> collectChildrensNames(ResourceHandle greatparent) {
        List<Resource> pageSiblings = IteratorUtils.toList(greatparent.listChildren());
        return pageSiblings.stream()
                .map(Resource::getName)
                .collect(Collectors.toList());
    }

}
