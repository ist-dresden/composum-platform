package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.sling.api.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.singleton;

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
        /** The node's siblings are in a parent that supports no child ordering. */
        notOrderable,
        /** The node seemed to be at its right place - nothing changed. */
        unchanged,
        /** The algorithm found a single place where the node should be, so that the likelihood that the user isn't satisfied is low. */
        deterministicallyReordered,
        /**
         * The algorithm had to use some sort of heuristic, which means that the user should be alerted since that might
         * not have been his/her intention.
         */
        heuristicallyReordered;

        /** The "worst" result of r1 and r2 - the one that has the largest likelyhood of attention by the user. */
        @Nullable
        public static Result max(@Nullable Result r1, @Nullable Result r2) {
            if (r1 == null) return r2;
            if (r2 == null) return r1;
            int i1 = Arrays.asList(Result.values()).indexOf(r1);
            int i2 = Arrays.asList(Result.values()).indexOf(r2);
            return Result.values()[Math.max(i1, i2)];
        }
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

        if (!destinationNode.getParent().getNode().getPrimaryNodeType().hasOrderableChildNodes())
            return Result.notOrderable;

        List<String> sourceOrdering = IteratorUtils.toList(sourceNode.getParent().listChildren()).stream()
                .map(Resource::getName).collect(Collectors.toList());
        if (sourceOrdering.size() != new HashSet<String>(sourceOrdering).size()) // seems not possible in Jackrabbit
            throw new IllegalArgumentException("Same name siblings not supported but present in " + sourceNode.getPath());
        List<String> destinationOrdering = IteratorUtils.toList(destinationNode.getParent().listChildren()).stream()
                .map(Resource::getName).collect(Collectors.toList());
        if (destinationOrdering.size() != new HashSet<String>(destinationOrdering).size())  // seems not possible in Jackrabbit
            throw new IllegalArgumentException("Same name siblings not supported but present in " + destinationNode.getPath());

        Orderer orderer = new Orderer(sourceOrdering, destinationOrdering, sourceNode.getName()).run();
        adaptSiblingOrder(destinationNode, orderer.ordering, destinationOrdering);
        return orderer.result;
    }

    protected void adaptSiblingOrder(ResourceHandle destinationNode, List<String> ordering, List<String> originalDestinationOrdering) throws RepositoryException {
        if (!ordering.equals(originalDestinationOrdering)) {
            LOG.info("Adjusting order of {} from {} to {}", destinationNode.getPath(), originalDestinationOrdering, ordering);
            Node parent = destinationNode.getParent().adaptTo(Node.class);
            if (ordering.size() != parent.getNodes().getSize())
                throw new IllegalArgumentException("Different size of required ordering and child node count: " + ordering + " , " + destinationNode.getPath());
            for (String name : ordering) {
                parent.orderBefore(name, null);
            }
        }
    }

    static class Relationships {

        final List<String> predecessors;
        final List<String> successors;
        final String node;

        public Relationships(String node, List<String> allnodes) {
            this.node = node;
            int position = allnodes.indexOf(node);
            predecessors = allnodes.subList(0, position);
            successors = allnodes.subList(position + 1, allnodes.size());
        }

        /**
         * Ratio of violations of predecessors being before position and successors being after position
         * if node was inserted into newNodes at the given position. It doesn't matter whether node is already in
         * the list - that'll be automatically ignored.
         */
        public double violationRatio(List<String> newNodes, int position) {
            List<String> beforePosition = newNodes.subList(0, position);
            List<String> afterPosition = newNodes.subList(position, newNodes.size());
            int countRelevantElements = ListUtils.retainAll(newNodes, ListUtils.union(predecessors, successors)).size();
            int violations = ListUtils.retainAll(beforePosition, successors).size()
                    + ListUtils.retainAll(afterPosition, predecessors).size();
            return violations / (double) countRelevantElements;
        }

        /** Returns the positions in a node list that have the least violations. */
        public List<Pair<Integer, Double>> bestPositions(List<String> newNodes) {
            List<String> cleanedUpNodes = ListUtils.removeAll(newNodes, singleton(node));
            List<Pair<Integer, Double>> result = new ArrayList<>();
            for (int pos = 0; pos <= cleanedUpNodes.size(); ++pos) {
                result.add(Pair.of(pos, violationRatio(cleanedUpNodes, pos)));
            }
            Double bestRatio = result.stream().map(Pair::getRight).min(Comparator.naturalOrder()).get();
            result = ListUtils.select(result, (n) -> n.getRight().equals(bestRatio));
            return result;
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
        List<String> ordering;
        Result result;

        public Orderer(@Nonnull List<String> sourceOrdering, @Nonnull List<String> destinationOrdering, @Nonnull String node) {
            if (!sourceOrdering.contains(node) || !destinationOrdering.contains(node))
                throw new IllegalArgumentException(node + " not in " + sourceOrdering + " or " + destinationOrdering);
            this.sourceOrdering = Collections.unmodifiableList(sourceOrdering);
            this.originalDestinationOrdering = Collections.unmodifiableList(destinationOrdering);
            this.node = node;
            this.result = Result.unchanged;
            this.ordering = new ArrayList<String>(originalDestinationOrdering);
        }

        public Orderer run() {
            Relationships relationships = new Relationships(node, sourceOrdering);
            List<Pair<Integer, Double>> bestPositions = relationships.bestPositions(originalDestinationOrdering);
            Double bestRatio = bestPositions.stream().map(Pair::getRight).min(Comparator.naturalOrder()).get();
            if (bestRatio > 0)
                processContradictions();
            else {
                if (bestPositions.size() == 1) {

                    ordering.removeAll(singleton(node));
                    ordering.add(bestPositions.get(0).getKey(), node);
                    result = ordering.equals(originalDestinationOrdering) ? Result.unchanged : Result.deterministicallyReordered;

                } else { // several possible positions where all constraints are satisfied.

                    Integer currentPosition = ordering.indexOf(node);
                    if (bestPositions.stream().map(Pair::getLeft).anyMatch(currentPosition::equals)) {
                        result = Result.unchanged;
                    } else {
                        result = Result.heuristicallyReordered;
                        ordering.removeAll(singleton(node));
                        ordering.add(bestPositions.get(0).getKey(), node);
                    }

                }
            }
            return this;
        }

        protected void processContradictions() {
            result = Result.deterministicallyReordered; // will change if there is something nondeterministic

            ordering = ListUtils.select(sourceOrdering, (n) -> originalDestinationOrdering.contains(n));
            List<String> missingNodes = ListUtils.removeAll(originalDestinationOrdering, ordering);

            missingNodes:
            while (!missingNodes.isEmpty()) {
                for (String checkNode : missingNodes) {
                    Relationships checkNodeRelations = new Relationships(checkNode, originalDestinationOrdering);
                    List<Pair<Integer, Double>> bestPositions = checkNodeRelations.bestPositions(ordering);
                    if (bestPositions.size() == 1) {
                        if (bestPositions.get(0).getRight() > 0) // unique but not fully satisfied
                            result = Result.heuristicallyReordered;
                        ordering.add(bestPositions.get(0).getLeft(), checkNode);
                        missingNodes.remove(checkNode);
                        continue missingNodes;
                    }
                }

                // all nodes have ambiguous positions - just take first one and put it on one of the possible positions
                result = Result.heuristicallyReordered;
                String nextNode = missingNodes.get(0);
                missingNodes.remove(nextNode);
                Relationships nextNodeRelations = new Relationships(nextNode, originalDestinationOrdering);
                List<Pair<Integer, Double>> bestPositions = nextNodeRelations.bestPositions(ordering);
                ordering.add(bestPositions.get(0).getLeft(), nextNode);
            }
        }
    }
}
