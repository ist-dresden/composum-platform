package com.composum.platform.commons.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.version.Version;
import javax.jcr.version.VersionIterator;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Stuff related to foreach loops - turing ancient pre java-5 iterators into proper iterables. */
public class JcrIteratorUtil {

    /** Turns a VersionIterator into a onetime iterable usable with for-each loops. */
    @Nonnull
    public static final Iterable<Version> asIterable(@Nullable VersionIterator iterator) {
        if (iterator == null) return Collections.emptyList();
        return new Iterable<Version>() {
            @Override
            public Iterator<Version> iterator() {
                return iterator;
            }
        };
    }

    /** Turns a NodeIterator into a onetime iterable usable with for-each loops. */
    @Nonnull
    public static final Iterable<Node> asIterable(@Nullable NodeIterator iterator) {
        if (iterator == null) return Collections.emptyList();
        return new Iterable<Node>() {
            @Override
            public Iterator<Node> iterator() {
                return iterator;
            }
        };
    }

    /** Turns a VersionIterator into a stream. */
    public static final Stream<Version> asStream(@Nullable VersionIterator iterator) {
        return StreamSupport.stream(asIterable(iterator).spliterator(), false);
    }

    /** Turns a NodeIterator into a stream. */
    public static final Stream<Node> asStream(@Nullable NodeIterator iterator) {
        return StreamSupport.stream(asIterable(iterator).spliterator(), false);
    }

}
