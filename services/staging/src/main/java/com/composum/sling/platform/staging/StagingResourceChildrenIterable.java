package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import java.util.Iterator;

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
