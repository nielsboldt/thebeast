package com.googlecode.thebeast.pml;

import com.googlecode.thebeast.query.NestedSubstitution;
import com.googlecode.thebeast.world.DoubleConstant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Sebastian Riedel
 */
public final class GroundFormulaFactor {

    private final NestedSubstitution substitution;
    private final PMLFormula pmlFormula;
    private final Formula groundFormula;
    private final List<GroundAtomNode> nodes;
    private final FeatureIndex featureIndex;
    private final double scale;

    GroundFormulaFactor(final PMLFormula pmlFormula,
                        final Formula groundFormula,
                        final NestedSubstitution substitution,
                        final List<GroundAtomNode> nodes) {
        this.pmlFormula = pmlFormula;
        this.substitution = substitution;
        this.groundFormula = groundFormula;
        this.nodes = new ArrayList<GroundAtomNode>(nodes);
        this.featureIndex = new FeatureIndex(
            substitution.getOuterSubstitution().getSubset(pmlFormula.getIndexVariables()));
        this.scale = pmlFormula.getScaleVariable() != null ?
            ((DoubleConstant)substitution.getOuterSubstitution().get(pmlFormula.getScaleVariable())).getValue() : 1.0;
    }

    public FeatureIndex getFeatureIndex() {
        return featureIndex;
    }


    public List<GroundAtomNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    public NestedSubstitution getSubstitution() {
        return substitution;
    }

    public PMLFormula getPmlFormula() {
        return pmlFormula;
    }

    public Formula getGroundFormula() {
        return groundFormula;
    }

    public double getScale() {
        return scale;
    }
}
