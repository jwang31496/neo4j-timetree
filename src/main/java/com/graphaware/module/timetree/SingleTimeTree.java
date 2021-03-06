/*
 * Copyright (c) 2013-2016 GraphAware
 *
 * This file is part of the GraphAware Framework.
 *
 * GraphAware Framework is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details. You should have received a copy of
 * the GNU General Public License along with this program.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.graphaware.module.timetree;

import com.graphaware.common.log.LoggerFactory;
import com.graphaware.common.util.IterableUtils;
import com.graphaware.module.timetree.domain.Resolution;
import com.graphaware.module.timetree.domain.TimeInstant;
import com.graphaware.module.timetree.domain.TimeTreeLabels;
import com.graphaware.runtime.config.util.InstanceRoleUtils;
import org.joda.time.DateTime;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventHandler;
import org.neo4j.logging.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static com.graphaware.common.util.PropertyContainerUtils.getInt;
import static com.graphaware.module.timetree.SingleTimeTree.ChildNotFoundPolicy.*;
import static com.graphaware.module.timetree.domain.Resolution.YEAR;
import static com.graphaware.module.timetree.domain.Resolution.findForNode;
import static com.graphaware.module.timetree.domain.TimeTreeLabels.TimeTreeRoot;
import static com.graphaware.module.timetree.domain.TimeTreeRelationshipTypes.*;
import static org.neo4j.graphdb.Direction.INCOMING;
import static org.neo4j.graphdb.Direction.OUTGOING;

/**
 * Default implementation of {@link TimeTree}, which builds a single tree and maintains its own root.
 */
public class SingleTimeTree implements TimeTree {

    private static final Log LOG = LoggerFactory.getLogger(SingleTimeTree.class);

    protected static final String VALUE_PROPERTY = "value";

    private final GraphDatabaseService database;
    private final ReentrantLock rootLock = new ReentrantLock();
    private final InstanceRoleUtils instanceRoleUtils;

    /**
     * Constructor for time tree.
     *
     * @param database to talk to.
     */
    public SingleTimeTree(GraphDatabaseService database) {
        this.database = database;
        this.instanceRoleUtils = new InstanceRoleUtils(database);

        database.registerTransactionEventHandler(new TransactionEventHandler<Boolean>() {
            @Override
            public Boolean beforeCommit(TransactionData transactionData) throws Exception {
                if (!rootLock.isLocked()) {
                    return false;
                }

                for (Node node : transactionData.createdNodes()) {
                    if (node.hasLabel(TimeTreeRoot)) {
                        return true;
                    }
                }

                return false;
            }

            @Override
            public void afterCommit(TransactionData transactionData, Boolean rootCreated) {
                if (rootCreated) {
                    if (rootLock.isHeldByCurrentThread()) {
                        rootLock.unlock();
                    }
                }
            }

            @Override
            public void afterRollback(TransactionData transactionData, Boolean rootCreated) {
                if (rootCreated) {
                    if (rootLock.isHeldByCurrentThread()) {
                        rootLock.unlock();
                    }
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Node getInstant(TimeInstant timeInstant) {
        return getInstant(timeInstant, RETURN_NULL);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Node getInstantAtOrAfter(TimeInstant timeInstant) {
        return getInstant(timeInstant, RETURN_NEXT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Node getInstantAtOrBefore(TimeInstant timeInstant) {
        return getInstant(timeInstant, RETURN_PREVIOUS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Node> getInstants(TimeInstant startTime, TimeInstant endTime) {
        List<Node> result = new LinkedList<>();

        for (TimeInstant instant : TimeInstant.getInstants(startTime, endTime)) {
            Node toAdd = getInstant(instant);
            if (toAdd != null) {
                result.add(toAdd);
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Node getOrCreateInstant(TimeInstant timeInstant) {
        Node instant;
        DateTime dateTime = new DateTime(timeInstant.getTime(), timeInstant.getTimezone());

        try (Transaction tx = database.beginTx()) {
            Node timeRoot = getTimeRoot(true);
            tx.acquireWriteLock(timeRoot);
            instant = getOrCreateInstant(timeRoot, dateTime, timeInstant.getResolution());

            tx.success();
        }

        return instant;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Node> getOrCreateInstants(TimeInstant startTime, TimeInstant endTime) {
        List<Node> result = new LinkedList<>();

        for (TimeInstant instant : TimeInstant.getInstants(startTime, endTime)) {
            result.add(getOrCreateInstant(instant));
        }

        return result;
    }

    /**
     * Get the root of the time tree.
     *
     * @param createIfMissing Create it if it does not exist.
     * @return root of the time tree.
     */
    protected Node getTimeRoot(boolean createIfMissing) {
        Node timeTreeRoot = IterableUtils.getSingleOrNull(database.findNodes(TimeTreeRoot));

        if (timeTreeRoot != null) {
            try {
                timeTreeRoot.getDegree();
                return timeTreeRoot;
            } catch (NotFoundException e) {
                //ok
            }
        }

        if (!createIfMissing) {
            return null;
        }

        rootLock.lock();

        timeTreeRoot = IterableUtils.getSingleOrNull(database.findNodes(TimeTreeRoot));

        if (timeTreeRoot != null) {
            rootLock.unlock();
            return timeTreeRoot;
        }

        LOG.info("Creating time tree root");
        timeTreeRoot = database.createNode(TimeTreeRoot);

        return timeTreeRoot;
    }

    private Node getInstant(TimeInstant timeInstant, ChildNotFoundPolicy childNotFoundPolicy) {
        Node instant = null;

        try (Transaction tx = database.beginTx()) {
            DateTime dateTime = new DateTime(timeInstant.getTime(), timeInstant.getTimezone());

            Node timeRoot = getTimeRoot(false);

            if (timeRoot != null) {
                tx.acquireWriteLock(timeRoot);
                instant = getInstant(timeRoot, dateTime, timeInstant.getResolution(), childNotFoundPolicy);
            }

            tx.success();
        }

        return instant;
    }

    private Node getInstant(Node parent, DateTime dateTime, Resolution targetResolution, ChildNotFoundPolicy childNotFoundPolicy) {
        Resolution currentResolution = currentResolution(parent);

        if (targetResolution.equals(currentResolution)) {
            return parent;
        }

        Resolution newCurrentResolution = childResolution(parent);

        Node child = findChild(parent, dateTime.get(newCurrentResolution.getDateTimeFieldType()), RETURN_NULL);

        if (child == null) {
            switch (childNotFoundPolicy) {
                case RETURN_NULL:
                    return null;
                case RETURN_NEXT:
                    return getInstantViaClosestChild(parent, dateTime, targetResolution, childNotFoundPolicy, newCurrentResolution, FIRST);
                case RETURN_PREVIOUS:
                    return getInstantViaClosestChild(parent, dateTime, targetResolution, childNotFoundPolicy, newCurrentResolution, LAST);
            }
        }

        //recursion
        return getInstant(child, dateTime, targetResolution, childNotFoundPolicy);
    }

    private Node getInstantViaClosestChild(Node parent, DateTime dateTime, Resolution targetResolution, ChildNotFoundPolicy childNotFoundPolicy, Resolution newCurrentResolution, RelationshipType relationshipType) {
        Node closestChild = findChild(parent, dateTime.get(newCurrentResolution.getDateTimeFieldType()), childNotFoundPolicy);
        if (closestChild == null) {
            return null;
        }
        return findChild(closestChild, relationshipType, targetResolution);
    }

    private Node findChild(Node parent, RelationshipType relationshipType, Resolution targetResolution) {
        if (!isRoot(parent)) {
            Resolution currentResolution = findForNode(parent);

            if (currentResolution.equals(targetResolution)) {
                return parent;
            }
        }

        Relationship r = parent.getSingleRelationship(relationshipType, OUTGOING);
        if (r == null) {
            return null;
        }

        return findChild(r.getEndNode(), relationshipType, targetResolution);
    }

    private Resolution currentResolution(Node parent) {
        if (isRoot(parent)) {
            return null;
        }

        return findForNode(parent);
    }

    private Resolution childResolution(Node parent) {
        if (isRoot(parent)) {
            return YEAR;
        }

        return currentResolution(parent).getChild();
    }

    enum ChildNotFoundPolicy {
        RETURN_NULL, RETURN_PREVIOUS, RETURN_NEXT
    }

    /**
     * Get a node representing a specific time instant. If one doesn't exist, it will be created as well as any missing
     * nodes on the way down from parent (recursively).
     *
     * @param parent           parent node on path to desired instant node.
     * @param dateTime         time instant.
     * @param targetResolution target child resolution. Recursion stops when at this level.
     * @return node representing the time instant at the desired resolution level.
     */
    private Node getOrCreateInstant(Node parent, DateTime dateTime, Resolution targetResolution) {
        Resolution currentResolution = currentResolution(parent);

        if (targetResolution.equals(currentResolution)) {
            return parent;
        }

        Resolution newCurrentResolution = childResolution(parent);

        Node child = findOrCreateChild(parent, dateTime.get(newCurrentResolution.getDateTimeFieldType()));

        //recursion
        return getOrCreateInstant(child, dateTime, targetResolution);
    }

    /**
     * Find a child node with value equal to the given value. If no such child exists, return a value according to the
     * provided {@link ChildNotFoundPolicy}.
     *
     * @param parent              parent of the node to be found.
     * @param value               value of the node to be found.
     * @param childNotFoundPolicy what to do when child isn't found?
     * @return child node, or a value specified by the given {@link ChildNotFoundPolicy}.
     */
    private Node findChild(Node parent, int value, ChildNotFoundPolicy childNotFoundPolicy) {
        Relationship firstRelationship = parent.getSingleRelationship(FIRST, OUTGOING);
        if (firstRelationship == null) {
            return null;
        }

        Node existingChild = firstRelationship.getEndNode();
        while (getInt(existingChild, VALUE_PROPERTY) < value && parent(existingChild).getId() == parent.getId()) {
            Relationship nextRelationship = existingChild.getSingleRelationship(NEXT, OUTGOING);

            if (nextRelationship == null) {
                switch (childNotFoundPolicy) {
                    case RETURN_NULL:
                        return null;
                    case RETURN_NEXT:
                        return null;
                    case RETURN_PREVIOUS:
                        return existingChild;
                }
            }

            if (parent(nextRelationship.getEndNode()).getId() != parent.getId()) {
                switch (childNotFoundPolicy) {
                    case RETURN_NULL:
                        return null;
                    case RETURN_NEXT:
                        return nextRelationship.getEndNode();
                    case RETURN_PREVIOUS:
                        return existingChild;
                }
            }

            existingChild = nextRelationship.getEndNode();
        }

        if (getInt(existingChild, VALUE_PROPERTY) == value) {
            return existingChild;
        }

        //here we claim that getInt(existingChild, VALUE_PROPERTY) > value || parent(existingChild).getId() != parent.getId()
        switch (childNotFoundPolicy) {
            case RETURN_NULL:
                return null;
            case RETURN_NEXT:
                return existingChild;
            case RETURN_PREVIOUS:
                return existingChild.getSingleRelationship(NEXT, INCOMING) == null ? null : existingChild.getSingleRelationship(NEXT, INCOMING).getStartNode();
            default:
                throw new IllegalStateException("Unknown child not found policy: " + childNotFoundPolicy);
        }
    }

    /**
     * Find a child node with value equal to the given value. If no such child exists, create one.
     *
     * @param parent parent of the node to be found or created.
     * @param value  value of the node to be found or created.
     * @return child node.
     */
    private Node findOrCreateChild(Node parent, int value) {
        Relationship firstRelationship = parent.getSingleRelationship(FIRST, OUTGOING);
        if (firstRelationship == null) {
            return createFirstChildEver(parent, value);
        }

        Node existingChild = firstRelationship.getEndNode();
        boolean isFirst = true;
        while (getInt(existingChild, VALUE_PROPERTY) < value && parent(existingChild).getId() == parent.getId()) {
            isFirst = false;
            Relationship nextRelationship = existingChild.getSingleRelationship(NEXT, OUTGOING);

            if (nextRelationship == null || parent(nextRelationship.getEndNode()).getId() != parent.getId()) {
                return createLastChild(parent, existingChild, nextRelationship == null ? null : nextRelationship.getEndNode(), value);
            }

            existingChild = nextRelationship.getEndNode();
        }

        if (getInt(existingChild, VALUE_PROPERTY) == value) {
            return existingChild;
        }

        Relationship previousRelationship = existingChild.getSingleRelationship(NEXT, INCOMING);

        if (isFirst) {
            return createFirstChild(parent, previousRelationship == null ? null : previousRelationship.getStartNode(), existingChild, value);
        }

        return createChild(parent, previousRelationship.getStartNode(), existingChild, value);
    }

    /**
     * Create the first ever child of a parent.
     *
     * @param parent to create child for.
     * @param value  value of the node to be created.
     * @return child node.
     */
    private Node createFirstChildEver(Node parent, int value) {
        if (parent.getSingleRelationship(LAST, OUTGOING) != null) { //sanity check
            LOG.error(parent + " has no " + FIRST + " relationship, but has a " + LAST + " one!");
            throw new IllegalStateException(parent + " has no " + FIRST + " relationship, but has a " + LAST + " one!");
        }

        Node previousChild = null;
        Node previousParent = parent;
        while (previousChild == null) {
            Relationship previousParentRelationship = previousParent.getSingleRelationship(NEXT, INCOMING);
            if (previousParentRelationship == null) {
                break;
            }

            previousParent = previousParentRelationship.getStartNode();
            Relationship currentParentLastChildRelationship = previousParent.getSingleRelationship(LAST, OUTGOING);
            if (currentParentLastChildRelationship != null) {
                previousChild = currentParentLastChildRelationship.getEndNode();
            }
        }

        Node nextChild = null;
        Node nextParent = parent;
        while (nextChild == null) {
            Relationship nextParentRelationship = nextParent.getSingleRelationship(NEXT, OUTGOING);
            if (nextParentRelationship == null) {
                break;
            }

            nextParent = nextParentRelationship.getEndNode();
            Relationship nextParentFirstChildRelationship = nextParent.getSingleRelationship(FIRST, OUTGOING);
            if (nextParentFirstChildRelationship != null) {
                nextChild = nextParentFirstChildRelationship.getEndNode();
            }
        }

        Node child = createChild(parent, previousChild, nextChild, value);

        parent.createRelationshipTo(child, FIRST);
        parent.createRelationshipTo(child, LAST);

        return child;
    }

    /**
     * Create the first child node that belongs to a specific parent. "First" is with respect to ordering, not the
     * number of nodes. In other words, the node being created is not the first parent's child, but it is the child with
     * the lowest ordering.
     *
     * @param parent        to create child for.
     * @param previousChild previous child (has different parent), or null for no such child.
     * @param nextChild     next child (has same parent).
     * @param value         value of the node to be created.
     * @return child node.
     */
    private Node createFirstChild(Node parent, Node previousChild, Node nextChild, int value) {
        Relationship firstRelationship = parent.getSingleRelationship(FIRST, OUTGOING);

        if (nextChild.getId() != firstRelationship.getEndNode().getId()) { //sanity check
            LOG.error(nextChild + " seems to be the first child of node " + parent + ", but there is no " + FIRST + " relationship between the two!");
            throw new IllegalStateException(nextChild + " seems to be the first child of node " + parent + ", but there is no " + FIRST + " relationship between the two!");
        }

        firstRelationship.delete();

        Node child = createChild(parent, previousChild, nextChild, value);

        parent.createRelationshipTo(child, FIRST);

        return child;
    }

    /**
     * Create the last child node that belongs to a specific parent.
     *
     * @param parent        to create child for.
     * @param previousChild previous child (has same parent).
     * @param nextChild     next child (has different parent), or null for no such child.
     * @param value         value of the node to be created.
     * @return child node.
     */
    private Node createLastChild(Node parent, Node previousChild, Node nextChild, int value) {
        Relationship lastRelationship = parent.getSingleRelationship(LAST, OUTGOING);

        Node endNode = lastRelationship.getEndNode();
        if (previousChild.getId() != endNode.getId()) { //sanity check
            LOG.error(previousChild + " seems to be the last child of node " + parent + ", but there is no " + LAST + " relationship between the two!");
            throw new IllegalStateException(previousChild + " seems to be the last child of node " + parent + ", but there is no " + LAST + " relationship between the two!");
        }

        lastRelationship.delete();

        Node child = createChild(parent, previousChild, nextChild, value);

        parent.createRelationshipTo(child, LAST);

        return child;
    }

    /**
     * Create a child node.
     *
     * @param parent   parent node.
     * @param previous previous node on the same level, null if the child is the first one.
     * @param next     next node on the same level, null if the child is the last one.
     * @param value    value of the child.
     * @return the newly created child.
     */
    private Node createChild(Node parent, Node previous, Node next, int value) {
        if (previous != null && next != null && next.getId() != previous.getSingleRelationship(NEXT, OUTGOING).getEndNode().getId()) {
            LOG.error(previous + " and " + next + " are not connected with a " + NEXT + " relationship!");
            throw new IllegalArgumentException(previous + " and " + next + " are not connected with a " + NEXT + " relationship!");
        }

        Node child = database.createNode(TimeTreeLabels.getChild(parent));
        child.setProperty(VALUE_PROPERTY, value);
        parent.createRelationshipTo(child, CHILD);

        if (previous != null) {
            Relationship nextRelationship = previous.getSingleRelationship(NEXT, OUTGOING);
            if (nextRelationship != null) {
                nextRelationship.delete();
            }
            previous.createRelationshipTo(child, NEXT);
        }

        if (next != null) {
            child.createRelationshipTo(next, NEXT);
        }

        return child;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeAll() {
        removeChildren(getTimeRoot(true));
    }

    private void removeChildren(Node root) {
        for (Relationship relationship : root.getRelationships(OUTGOING)) {
            relationship.delete();
            if (relationship.isType(CHILD)) {
                removeChildren(relationship.getEndNode());
            }
        }
        root.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeInstant(Node instantNode) {
        if (instantNode.hasRelationship(CHILD, OUTGOING)) {
            LOG.warn("Cannot remove " + instantNode + ". It still has children.");
            return;
        }

        Relationship first = instantNode.getSingleRelationship(FIRST, INCOMING);
        Relationship last = instantNode.getSingleRelationship(LAST, INCOMING);

        Relationship prev = instantNode.getSingleRelationship(NEXT, INCOMING);
        Relationship next = instantNode.getSingleRelationship(NEXT, OUTGOING);

        if (prev != null && next != null) {  // middle
            if (last != null) {
                last.getStartNode().createRelationshipTo(prev.getStartNode(), LAST);
                last.delete();
            }
            if (first != null) {
                first.getStartNode().createRelationshipTo(next.getEndNode(), FIRST);
                first.delete();
            }

            prev.getStartNode().createRelationshipTo(next.getEndNode(), NEXT);
            prev.delete();
            next.delete();
        } else if (first != null && next != null) { // beginning
            first.getStartNode().createRelationshipTo(next.getEndNode(), FIRST);
            first.delete();
            next.delete();
        } else if (prev != null && last != null) { // end
            last.getStartNode().createRelationshipTo(prev.getStartNode(), LAST);
            last.delete();
            prev.delete();
        }


        if (instantNode.hasRelationship(FIRST, OUTGOING)) {
            instantNode.getSingleRelationship(FIRST, OUTGOING).delete();
        }

        if (instantNode.hasRelationship(LAST, OUTGOING)) {
            instantNode.getSingleRelationship(LAST, OUTGOING).delete();
        }

        if (instantNode.hasRelationship(CHILD, INCOMING)) {
            Relationship toParent = instantNode.getSingleRelationship(CHILD, INCOMING);
            toParent.delete();
            removeInstant(toParent.getStartNode());
        }
        instantNode.delete();
    }

    /**
     * Find the parent of a node.
     *
     * @param node to find a parent for.
     * @return parent.
     * @throws IllegalStateException in case the node has no parent.
     */
    static Node parent(Node node) {
        Relationship parentRelationship = node.getSingleRelationship(CHILD, INCOMING);

        if (parentRelationship == null) {
            LOG.error(node + " has no parent!");
            throw new IllegalStateException(node + " has no parent!");
        }

        return parentRelationship.getStartNode();
    }

    private boolean isRoot(Node node) {
        Node timeRoot = getTimeRoot(false);

        return timeRoot != null && node.getId() == timeRoot.getId();
    }
}
