package com.googlecode.thebeast.pml.pmtl.typeinference;

import com.googlecode.thebeast.pml.pmtl.node.Node;

/**
 * A NodeTypeVariable is variable that one can assign node types to.
 *
 * @author Sebastian Riedel
 */
public class NodeTypeVariable implements NodeTypeExpression {

    /**
     * The node for which this type variable holds/represents the type.
     */
    private Node node;

    /**
     * Creates a new NodeTypeVariable for the given node.
     *
     * @param node the node for which this variable represents the type.
     */
    public NodeTypeVariable(final Node node) {
        this.node = node;
    }


    /**
     * {@inheritDoc}
     */
    public void accept(final NodeTypeExpressionVisitor visitor) {
        visitor.visitNodeTypeVariable(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NodeTypeVariable that = (NodeTypeVariable) o;

        if (node != null ? !node.equals(that.node) : that.node != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return node != null ? node.hashCode() : 0;
    }

    @Override
    public String toString() {
        return node.toString();
    }
}