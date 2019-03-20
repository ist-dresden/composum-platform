package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.Resource;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Objects;

class StagingResourceChildrenIterable implements Iterable<Resource> {

    private Iterator<Resource> iterator;

    StagingResourceChildrenIterable(@Nonnull Iterator<Resource> iterator) {
        this.iterator = Objects.requireNonNull(iterator);
    }

    /**
     * Returns once an iterator over the list this was constructed from - that'll be the list of children
     * of some resource. Limitation: can be called only once - which is enough for for-each loops.
     *
     * @throws IllegalStateException if this is called twice
     */
    @Override
    public Iterator<Resource> iterator() {
        if (this.iterator == null) throw new IllegalStateException("Only one call to iterator() is allowed here");
        Iterator<Resource> iterator = this.iterator;
        this.iterator = null;
        return iterator;
    }
}
