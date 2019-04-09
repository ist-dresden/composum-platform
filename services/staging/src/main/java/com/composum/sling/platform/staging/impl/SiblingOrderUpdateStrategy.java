package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strategy to ensure that the node ordering of a node in a release (= destination) with respect to its siblings is updated from the workspace (= source)
 * when necessary. Unfortunately, there are some cases when the ordering cannot be automatically decided - especially if the orders
 * are contradictory or there are nodes that have been deleted from workspace.
 * In such cases, we use a heuristic as far as possible and alert the user. Possible cases are:
 * <ul>
 * <li> If there is a single place for the node where all siblings preceeding the source node which have corresponding siblings
 * in the destination node do precede the destination node, and similarily for the following siblings, the destination is put there ( result {@link Result#deterministicallyReordered} )
 * or nothing is changed if it already was there ( {@link Result#unchanged} ). </li>
 * <li>If there are several such places and the node was in one of these places, the destination is not moved ({@link Result#unchanged}).
 * If it needs to be moved to be in one of these places, the first location is taken and the user is alerted ( {@link Result#heuristicallyReordered} ).
 * This can only be the case if the destination contains nodes deleted or moved in the source.</li>
 * <li>If there are no such places, then the ordering in the source and in the destination contradict. In this case
 * the destination nodes which do have counterparts in the source are reordered as in the source, and the remaining nodes
 * are re-inserted at those places where their successors and predecessors match best the situation before the reordering.
 * If there is only one ordering that does that, we return {@link Result#deterministicallyReordered}
 * The user is alerted with user is alerted ( {@link Result#heuristicallyReordered} ).</li>
 * </ul>
 */
public class SiblingOrderUpdateStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(SiblingOrderUpdateStrategy.class);

    public enum Result {
        notOrderable,
        /** The node seemed to be at its right place - nothing changed. */
        unchanged,
        /** The algorithm found a single place where the node should be, so that the likelihood that the user isn't satisfied is low. */
        deterministicallyReordered,
        /**
         * The algorithm had to use some sort of heuristic, which means that the user should be alerted since that might
         * not have been his/her intention.
         */
        heuristicallyReordered
    }

    /**
     * See class comment for description of algorithm
     *
     * @param sourceNode      the node in the workspace, with the reference for the ordering
     * @param destinationNode the node in the release, whose order wrt. its siblings is adjusted
     * @return true if the ordering was deterministic, false if there was heuristics involved and the user should check the result.
     * @see SiblingOrderUpdateStrategy
     */
    public Result adjustSiblingOrderOfDestination(ResourceHandle sourceNode, ResourceHandle destinationNode) throws RepositoryException {
        if (!sourceNode.getName().equals(destinationNode.getName())) // bug in caller
            throw new IllegalArgumentException("Different node names for " + sourceNode.getPath() + " , " + destinationNode.getPath());

        if (!destinationNode.getNode().getPrimaryNodeType().hasOrderableChildNodes()) return Result.notOrderable;

        List<String> sourceOrdering = IteratorUtils.toList(sourceNode.getParent().listChildren()).stream()
                .map(Resource::getName).collect(Collectors.toList());
        if (sourceOrdering.size() != new HashSet<String>(sourceOrdering).size()) // seems not possible in Jackrabbit
            throw new IllegalArgumentException("Same name siblings not supported but present in " + sourceNode.getPath());
        List<String> destinationOrdering = IteratorUtils.toList(sourceNode.getParent().listChildren()).stream()
                .map(Resource::getName).collect(Collectors.toList());
        if (destinationOrdering.size() != new HashSet<String>(destinationOrdering).size())  // seems not possible in Jackrabbit
            throw new IllegalArgumentException("Same name siblings not supported but present in " + destinationNode.getPath());

        Orderer orderer = new Orderer(sourceOrdering, destinationOrdering, sourceNode.getName()).run();
        adaptSiblingOrder(destinationNode, orderer.ordering, destinationOrdering);
        return Result.unchanged;
    }

    protected void adaptSiblingOrder(ResourceHandle destinationNode, List<String> ordering, List<String> originalDestinationOrdering) throws RepositoryException {
        if (!ordering.equals(originalDestinationOrdering)) {
            Node parent = destinationNode.getParent().adaptTo(Node.class);
            if (ordering.size() != parent.getNodes().getSize())
                throw new IllegalArgumentException("Different size of required ordering and child node count: " + ordering + " , " + destinationNode.getPath());
            for (String name : ordering) {
                parent.orderBefore(name, null);
            }
        }
    }

    static class Orderer {

        @Nonnull
        final List<String> sourceOrdering;
        @Nonnull
        final List<String> originalDestinationOrdering;
        @Nonnull
        final String node;
        @Nonnull
        final List<String> ordering;
        Result result;

        public Orderer(@Nonnull List<String> sourceOrdering, @Nonnull List<String> destinationOrdering, @Nonnull String node) {
            this.sourceOrdering = Collections.unmodifiableList(sourceOrdering);
            this.originalDestinationOrdering = Collections.unmodifiableList(destinationOrdering);
            this.node = node;
            this.result = Result.unchanged;
            this.ordering = new ArrayList<String>(originalDestinationOrdering);
        }

        public Orderer run() {
            return this;
        }
    }
}
