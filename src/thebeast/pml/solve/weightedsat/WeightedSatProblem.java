package thebeast.pml.solve.weightedsat;

import thebeast.pml.*;
import thebeast.pml.solve.PropositionalModel;
import thebeast.pml.formula.FactorFormula;
import thebeast.util.Profiler;
import thebeast.util.NullProfiler;
import thebeast.nod.variable.RelationVariable;
import thebeast.nod.variable.IntVariable;
import thebeast.nod.variable.Index;
import thebeast.nod.expression.RelationExpression;
import thebeast.nod.statement.Interpreter;
import thebeast.nod.type.Heading;
import thebeast.nod.util.TypeBuilder;
import thebeast.nod.util.ExpressionBuilder;
import thebeast.nod.value.TupleValue;
import thebeast.nod.value.ArrayValue;

import java.util.Collection;
import java.util.HashMap;

/**
 * A WeightedSatProblem represents a set of weighted clauses in CNF.
 *
 * @author Sebastian Riedel
 */
public class WeightedSatProblem implements PropositionalModel {

  private Model model;
  private WeightedSatSolver solver;
  private Scores scores;
  private Weights weights;
  private Profiler profiler = new NullProfiler();

  private int oldNumAtoms;

  private HashMap<UserPredicate, RelationVariable>
          mappings = new HashMap<UserPredicate, RelationVariable>(),
          newMappings = new HashMap<UserPredicate, RelationVariable>();
  private IntVariable atomCounter;

  private GroundFormulas groundFormulas;
  private GroundAtoms solution;

  private HashMap<FactorFormula, RelationExpression>
          groundingQueries = new HashMap<FactorFormula, RelationExpression>(),
          newQueries = new HashMap<FactorFormula, RelationExpression>();

  private HashMap<UserPredicate, RelationExpression>
          scoresAndIndices = new HashMap<UserPredicate, RelationExpression>(),
          removeFalseAtoms = new HashMap<UserPredicate, RelationExpression>(),
          addTrueAtoms = new HashMap<UserPredicate, RelationExpression>();

  private RelationExpression newAtomCostsQuery;

  private RelationVariable
          clauses, newClauses, groundedClauses,
          newAtomCosts, oldAtomCosts, atomCosts, trueAtoms, falseAtoms;

  boolean changed = false;

  private static Heading index_heading;
  private static Heading clause_heading;
  private Interpreter interpreter = TheBeast.getInstance().getNodServer().interpreter();
  private ExpressionBuilder builder = TheBeast.getInstance().getNodServer().expressionBuilder();
  private static Heading indexScore_heading;

  static {
    TypeBuilder typeBuilder = new TypeBuilder(TheBeast.getInstance().getNodServer());
    typeBuilder.doubleType().att("weight").
            boolType().arrayType().arrayType().att("signs").
            intType().arrayType().arrayType().att("atoms").relationType(3);
    clause_heading = typeBuilder.buildRelationType().heading();

    index_heading = typeBuilder.intType().att("index").relationType(1).buildRelationType().heading();

    indexScore_heading = typeBuilder.intType().att("index").doubleType().att("score").
            relationType(2).buildRelationType().heading();

  }


  public WeightedSatProblem(WeightedSatSolver solver) {
    this.solver = solver;
  }

  /**
   * This returns a table that maps ground atoms to indices and a score.
   *
   * @param userPredicate the predicate we want the table for
   * @return a table with the following format: |arg1|arg2|...|argn|index|score|.
   */
  public RelationVariable getMapping(UserPredicate userPredicate) {
    return mappings.get(userPredicate);
  }

  public RelationVariable getNewMapping(UserPredicate pred) {
    return newMappings.get(pred);
  }

  /**
   * This is the variable that represents the current count of atoms. It can be used
   *
   * @return a no d variable representing the current number of atoms.
   */
  IntVariable getAtomCounter() {
    return atomCounter;
  }


  public void init(Scores scores) {
    this.scores.load(scores);
    solver.init();
    clear();
  }

  public void buildLocalModel() {

  }

  public void solve(GroundAtoms solution) {
    this.solution.load(solution);
    profiler.start("Update solver");
    updateSolver();
    profiler.end().start("Solve");
    boolean[] result = solver.solve();
    profiler.end().start("Extract result");
    //System.out.println(Arrays.toString(result));
    fillTrueFalseTables(result);
    //System.out.println(trueAtoms.value());
    //System.out.println(falseAtoms.value());
    for (UserPredicate pred : model.getHiddenPredicates()) {
      RelationVariable target = solution.getGroundAtomsOf(pred).getRelationVariable();
      interpreter.assign(target, removeFalseAtoms.get(pred));
      interpreter.insert(target, addTrueAtoms.get(pred));
    }
    profiler.end();
    //System.out.println(solution);

  }

  public boolean isFractional() {
    return false;
  }

  public void update(GroundFormulas formulas, GroundAtoms atoms) {
    update(formulas, atoms, model.getGlobalFactorFormulas());
    //System.out.println(formulas);
    //System.out.println(toString());

  }

  private void clear() {
    interpreter.assign(atomCounter, builder.num(0).getInt());
    interpreter.clear(clauses);
    interpreter.clear(newClauses);
    interpreter.clear(groundedClauses);
    interpreter.clear(oldAtomCosts);
    interpreter.clear(atomCosts);
    for (UserPredicate pred : model.getHiddenPredicates()) {
      //interpreter.insert(this.mappings.get(pred), newMappings.get(pred));
      interpreter.clear(mappings.get(pred));
    }


  }

  public void update(GroundFormulas formulas, GroundAtoms atoms, Collection<FactorFormula> factors) {
    int oldNumClauses = clauses.value().size();
    oldNumAtoms = atomCounter.value().getInt();
    groundFormulas.load(formulas);
    interpreter.clear(newClauses);
    for (FactorFormula factor : factors) {
      interpreter.assign(groundedClauses, groundingQueries.get(factor));
      //System.out.println("GC:" + groundedClauses.value());
      interpreter.insert(newClauses, newQueries.get(factor));
    }
    interpreter.insert(clauses, newClauses);
    interpreter.assign(oldAtomCosts, atomCosts);
    interpreter.clear(atomCosts);
    for (UserPredicate pred : model.getHiddenPredicates()) {
      //interpreter.insert(this.mappings.get(pred), newMappings.get(pred));
      interpreter.insert(atomCosts, scoresAndIndices.get(pred));
    }
//    System.out.println("atomcosts");
//    System.out.println(atomCosts.value());
    interpreter.assign(newAtomCosts, newAtomCostsQuery);
//    System.out.println("newatomcosts");
//    System.out.println(newAtomCosts.value());
    changed = clauses.value().size() > oldNumClauses || atomCounter.value().getInt() > oldNumAtoms;
  }

  public boolean changed() {
    return changed;
  }

  public void setClosure(GroundAtoms closure) {

  }

  private void fillTrueFalseTables(boolean[] result) {
    int trueCount = 0;
    for (boolean state : result) if (state) ++trueCount;
    int falseCount = result.length - trueCount;
    int[] trueIndices = new int[trueCount];
    int[] falseIndices = new int[falseCount];
    int truePointer = 0;
    int falsePointer = 0;
    for (int i = 0; i < result.length; ++i)
      if (result[i]) trueIndices[truePointer++] = i;
      else falseIndices[falsePointer++] = i;
    trueAtoms.assignByArray(trueIndices, null);
    falseAtoms.assignByArray(falseIndices, null);

  }

  public void enforceIntegerSolution() {

  }

  public void configure(Model model, Weights weights) {
    this.model = model;
    this.weights = weights;

    groundFormulas = new GroundFormulas(model, weights);
    solution = model.getSignature().createGroundAtoms();
    scores = new Scores(model, weights);

    clauses = interpreter.createRelationVariable(clause_heading);
    newClauses = interpreter.createRelationVariable(clause_heading);
    groundedClauses = interpreter.createRelationVariable(clause_heading);
    trueAtoms = interpreter.createRelationVariable(index_heading);
    falseAtoms = interpreter.createRelationVariable(index_heading);
    atomCounter = interpreter.createIntVariable();
    atomCosts = interpreter.createRelationVariable(indexScore_heading);
    oldAtomCosts = interpreter.createRelationVariable(indexScore_heading);
    newAtomCosts = interpreter.createRelationVariable(indexScore_heading);
    mappings = new HashMap<UserPredicate, RelationVariable>();


    clauses = interpreter.createRelationVariable(clause_heading);
    groundedClauses = interpreter.createRelationVariable(clause_heading);

    for (UserPredicate pred : model.getHiddenPredicates()) {
      RelationVariable mapping = interpreter.createRelationVariable(pred.getHeadingArgsIndexScore());
      mappings.put(pred, mapping);
      interpreter.addIndex(mapping, "args", Index.Type.HASH, pred.getHeading().getAttributeNames());
      interpreter.addIndex(mapping, "index", Index.Type.HASH, "index");
      //create query to update solutions
      //remove wrong solutions
      builder.expr(solution.getGroundAtomsOf(pred).getRelationVariable());
      builder.expr(mapping).from("mapping").expr(falseAtoms).from("falseAtoms");
      builder.intAttribute("mapping", "index").intAttribute("falseAtoms", "index").equality().where();
      for (int i = 0; i < pred.getArity(); ++i) {
        builder.id(pred.getColumnName(i)).attribute("mapping", pred.getAttribute(i));
      }
      builder.tuple(pred.getArity()).select().query();
      builder.relationMinus();
      removeFalseAtoms.put(pred, builder.getRelation());

      //a query that produces a table with atoms to add
      builder.expr(mapping).from("mapping").expr(trueAtoms).from("trueAtoms");
      builder.intAttribute("mapping", "index").intAttribute("trueAtoms", "index").equality().where();
      for (int i = 0; i < pred.getArity(); ++i) {
        builder.id(pred.getColumnName(i)).attribute("mapping", pred.getAttribute(i));
      }
      builder.tuple(pred.getArity()).select().query();
      addTrueAtoms.put(pred, builder.getRelation());

      //a query that selects the scores and indices from the mapping
      builder.expr(mapping).from("mapping").
              id("index").intAttribute("mapping", "index").
              id("score").doubleAttribute("mapping", "score").tupleForIds().select().query();
      scoresAndIndices.put(pred, builder.getRelation());


    }

    builder.expr(atomCosts).expr(oldAtomCosts).relationMinus();
    newAtomCostsQuery = builder.getRelation();


    WeightedSatGrounder grounder = new WeightedSatGrounder();

    for (FactorFormula formula : model.getGlobalFactorFormulas()) {
      groundingQueries.put(formula, grounder.createGroundingQuery(formula, groundFormulas, this));
      newQueries.put(formula, builder.expr(groundedClauses).expr(clauses).relationMinus().getRelation());
    }


  }

  public void setProperty(PropertyName name, Object value) {
    if (name.getHead().equals("solver")) {
      if (name.isTerminal())
        if ("maxwalksat".equals(value))
          solver = new MaxWalkSat();
        else
          solver.setProperty(name.getTail(), value);
    }
  }

  public Object getProperty(PropertyName name) {
    return null;
  }

  public void setProfiler(Profiler profiler) {
    this.profiler = profiler;
  }

  private static WeightedSatClause toClause(TupleValue tuple) {
    ArrayValue signs = (ArrayValue) tuple.element("signs");
    boolean[][] signsArr = new boolean[signs.size()][];
    for (int i = 0; i < signsArr.length; ++i) {
      ArrayValue disjunction = (ArrayValue) signs.element(i);
      signsArr[i] = new boolean[disjunction.size()];
      for (int j = 0; j < signsArr[i].length; ++j)
        signsArr[i][j] = disjunction.boolElement(j).getBool();
    }
    ArrayValue indices = (ArrayValue) tuple.element("atoms");
    int[][] indicesArr = new int[signs.size()][];
    for (int i = 0; i < indicesArr.length; ++i) {
      ArrayValue disjunction = (ArrayValue) indices.element(i);
      indicesArr[i] = new int[disjunction.size()];
      for (int j = 0; j < indicesArr[i].length; ++j)
        indicesArr[i][j] = disjunction.intElement(j).getInt();
    }
    double weight = tuple.doubleElement("weight").getDouble();
    return new WeightedSatClause(weight, indicesArr, signsArr);
  }

  private void updateSolver() {
    int howMany = atomCounter.value().getInt() - oldNumAtoms;
    double[] scores = newAtomCosts.getDoubleColumn("score");
    int[] indices = newAtomCosts.getIntColumn("index");
    double[] ordered = new double[scores.length];
    for (int i = 0; i < scores.length; ++i)
      ordered[indices[i] - oldNumAtoms] = scores[i];
    boolean[] states = new boolean[howMany];
    solver.addAtoms(states, ordered);

    WeightedSatClause[] clauses = new WeightedSatClause[newClauses.value().size()];
    int i = 0;
    for (TupleValue tuple : newClauses.value()) {
      clauses[i++] = toClause(tuple);
    }
    solver.addClauses(clauses);
  }


  public Model getModel() {
    return model;
  }

  public WeightedSatSolver getSolver() {
    return solver;
  }

  public Scores getScores() {
    return scores;
  }

  public Weights getWeights() {
    return weights;
  }

  public String toString() {
    StringBuffer result = new StringBuffer();
    //result.append(groundedClauses.value());
    //result.append(newClauses.value());
    result.append(clauses.value());
    result.append(atomCosts.value());
    for (UserPredicate pred : model.getHiddenPredicates()) {
      result.append(mappings.get(pred).value());
    }
    return result.toString();
  }

}
