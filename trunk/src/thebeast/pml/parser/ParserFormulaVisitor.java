package thebeast.pml.parser;

/**
 * @author Sebastian Riedel
 */
public interface ParserFormulaVisitor {
  void visitAtom(ParserAtom parserAtom);

  void visitConjuction(ParserConjunction parserConjunction);

  void visitImplies(ParserImplies parserImplies);

  void visitCardinalityConstraint(ParserCardinalityConstraint parserCardinalityConstraint);

  void visitComparison(ParserComparison parserComparison);

  void visitAcyclicityConstraint(ParserAcyclicityConstraint parserAcyclicityConstraint);
}