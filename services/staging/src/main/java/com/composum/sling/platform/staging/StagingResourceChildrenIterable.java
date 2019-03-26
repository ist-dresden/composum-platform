package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import java.util.Iterator;

/**
 * @deprecated we can just use {@link org.apache.commons.collections4.IteratorUtils#asIterable(Iterator)}
 */
@Deprecated
class StagingResourceChildrenIterable implements Iterable<Resource> {

    private final Iterator<Resource> iterator;

    StagingResourceChildrenIterable(Iterator<Resource> iterator) {
        this.iterator = iterator;
    }

    @Override
    public Iterator<Resource> iterator() {
        return this.iterator;
    }
}
