package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;

import javax.annotation.Nonnull;
import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import static com.composum.sling.platform.staging.StagingUtils.isInVersionStorage;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;
import static org.apache.jackrabbit.JcrConstants.JCR_VERSIONABLEUUID;

class StagingResourceChildrenIterator implements Iterator<Resource> {

    /**
     * All children of a resource regardless, if they are versionable and labeled.
     */
    @Nonnull
    private final Iterator<Resource> iterator;

    /**
     * Potential children of the resource only existing in the version storage.
     * All resources in this iterator are somewhere below the original resource. They may or may not be direct children
     * of the resource.
     */
    @Nonnull
    private final Iterator<Resource> potentialVersionableChildren;

    @Nonnull
    private final StagingResourceResolver resourceResolver;

    /**
     * UUIDs of the resources already delivered by this iterator.
     */
    @Nonnull
    private final Set<String> uuids = new HashSet<>();

    private final String parentPath;
    private Resource next;
    private boolean hasNext;

    StagingResourceChildrenIterator(@Nonnull final Iterator<Resource> iterator,
                                    @Nonnull final StagingResourceResolver resourceResolver) {
        this.iterator = iterator;
        this.resourceResolver = resourceResolver;
        this.potentialVersionableChildren = Collections.emptyIterator();
        this.parentPath = "";
        readNext();
    }

    StagingResourceChildrenIterator(@Nonnull final Iterator<Resource> iterator,
                                    @Nonnull final Iterator<Resource> potentialVersionableChildren,
                                    @Nonnull final StagingResourceResolver resourceResolver,
                                    @Nonnull final String parentPath) {
        this.iterator = iterator;
        this.resourceResolver = resourceResolver;
        this.potentialVersionableChildren = potentialVersionableChildren;
        this.parentPath = parentPath;
        readNext();
    }

    /**
     * Reads the next value from the children iterator.
     *
     * @return next resource from version storage or null
     */
    private Resource getNextFromIterator() {
        if (iterator.hasNext()) {
            Resource tr;
            do {
                Resource inext = iterator.next();
                if (isInVersionStorage(inext)) {
                    tr = StagingResource.wrap(inext, resourceResolver);
                } else {
                    final Resource releasedResource = resourceResolver.getReleasedResource(inext);
                    if (releasedResource instanceof StagingResource && isInVersionStorage(((StagingResource)releasedResource).getFrozenResource())) {
                        // released child in version store
                        tr = releasedResource;
                    } else if (ResourceUtil.isNonExistingResource(releasedResource)) {
                        // versionable child but not released
                        tr = releasedResource;
                    } else if (releasedResource instanceof StagingResource) {
                        // non versionable child
                        tr = releasedResource;
                    } else {
                        tr = new NonExistingResource(resourceResolver, inext.getPath());
                    }
                }
            } while (iterator.hasNext() && ResourceUtil.isNonExistingResource(tr));
            if (ResourceUtil.isNonExistingResource(tr)) {
                return null;
            } else {
                return tr;
            }
        } else {
            return null;
        }

    }

    /**
     * Reads the next value from the potential children iterator.
     *
     * @return a child or null
     */
    private Resource getNextPotentialChild() {
        if (potentialVersionableChildren.hasNext()) {
            do {
                Resource inext = potentialVersionableChildren.next();
                String inextPath = inext.getValueMap().get("default", String.class);
                final String subpath = inextPath.replaceAll("^" + parentPath, "");
                if (subpath.lastIndexOf('/') < 1) {
                    // this is a direct child
                    final String uuid = inext.getValueMap().get(JCR_VERSIONABLEUUID, String.class);
                    if (!uuids.contains(uuid)) {
                        try {
                            return StagingResource.wrap(resourceResolver.getReleasedFrozenResource(inext), resourceResolver);
                        } catch (RepositoryException e) {
                            //nothing - not released
                        }
                    }
                }
            } while (potentialVersionableChildren.hasNext());
            return null;
        } else {
            return null;
        }
    }

    /**
     * Reads the next value from the available iterators into the state of this Iterator.
     */
    private void readNext() {
        final Resource nextFromIterator = getNextFromIterator();
        if (nextFromIterator != null) {
            this.next = nextFromIterator;
            this.hasNext = true;
            final String uuid = next.getValueMap().get(JCR_UUID, String.class);
            this.uuids.add(uuid);
        } else {
            final Resource nextPotentialChild = getNextPotentialChild();
            if (nextPotentialChild != null) {
                this.next = nextPotentialChild;
                this.hasNext = true;
            } else {
                this.next = null;
                this.hasNext = false;
            }
        }
    }

    /**
     * @see java.util.Iterator#hasNext()
     */
    public boolean hasNext() {
        return hasNext;
    }

    /**
     * @see java.util.Iterator#next()
     */
    public Resource next() {
        if (!hasNext) {
            throw new NoSuchElementException();
        }
        final Resource frozenResource = next;
        readNext();
        return frozenResource;
    }

    /**
     * @see java.util.Iterator#remove()
     */
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
