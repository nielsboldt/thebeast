package thebeast.pml.parser;

import thebeast.pml.*;
import thebeast.pml.corpora.*;
import thebeast.pml.formula.*;
import thebeast.pml.function.Function;
import thebeast.pml.function.IntAdd;
import thebeast.pml.function.IntMinus;
import thebeast.pml.function.WeightFunction;
import thebeast.pml.predicate.IntGT;
import thebeast.pml.predicate.IntLEQ;
import thebeast.pml.predicate.IntLT;
import thebeast.pml.predicate.Predicate;
import thebeast.pml.term.*;
import thebeast.pml.training.FeatureCollector;
import thebeast.pml.training.OnlineLearner;
import thebeast.pml.training.TrainingInstances;
import thebeast.util.DotProgressReporter;
import thebeast.util.Util;

import java.io.*;
import java.util.*;

/**
 * The Shell processes PML commands either in an active mode from standard in or directly from a file or any other input
 * stream.
 */
public class Shell implements ParserStatementVisitor, ParserFormulaVisitor, ParserTermVisitor {

  private Model model;
  private Signature signature;
  private BooleanFormula formula;
  private Term term;
  private Stack<Type> typeContext = new Stack<Type>();
  private Stack<HashMap<String, Variable>> variables = new Stack<HashMap<String, Variable>>();
  private InputStream in;
  private PrintStream out;
  private PrintStream err;
  private LinkedList<ParserStatement> history = new LinkedList<ParserStatement>();
  private ParserFactorFormula rootFactor;

  private HashMap<String, CorpusFactory> corpusFactories = new HashMap<String, CorpusFactory>();
  private HashMap<String, TypeGenerator> typeGenerators = new HashMap<String, TypeGenerator>();

  private GroundAtoms guess, gold;
  private Corpus guessCorpus, corpus;
  private Iterator<GroundAtoms> iterator;
  private ListIterator<GroundAtoms> listIterator;

  private CuttingPlaneSolver solver;
  private Scores scores;
  private Weights weights;
  private Solution solution;
  private OnlineLearner learner;
  private LocalFeatures features;
  private TrainingInstances instances;

  private boolean signatureUpdated = false;
  private boolean modelUpdated = true;
  private boolean weightsUpdated = false;

  private boolean printStackTraces = true;

  private boolean printPrompt = true;

  private String directory;

  public Shell() {
    this(System.in, System.out, System.err);
  }

  public Shell(InputStream in, PrintStream out, PrintStream err) {
    this.in = in;
    this.out = out;
    this.err = err;
    signature = TheBeast.getInstance().createSignature();
    model = signature.createModel();
    solver = new CuttingPlaneSolver();
    initCorpusTools();
  }


  public boolean isPrintPrompt() {
    return printPrompt;
  }

  public void setPrintPrompt(boolean printPrompt) {
    this.printPrompt = printPrompt;
  }

  public boolean isPrintStackTraces() {
    return printStackTraces;
  }

  public void setPrintStackTraces(boolean printStackTraces) {
    this.printStackTraces = printStackTraces;
  }

  /**
   * Fills up a model (and its signature) with the formulas, types and predices from a PML stream.
   *
   * @param inputStream the stream to load from
   * @param model       the model to save to
   * @throws Exception in case there are some semantic errors or IO problems.
   */
  public void load(InputStream inputStream, Model model) throws Exception {
    this.model = model;
    this.signature = model.getSignature();
    this.typeContext = new Stack<Type>();
    this.variables = new Stack<HashMap<String, Variable>>();
    byte[] buffer = new byte[1000];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    while (inputStream.available() > 0) {
      int howmany = inputStream.read(buffer);
      os.write(buffer, 0, howmany);
    }
    byte[] file = os.toByteArray();
    PMLParser parser = new PMLParser(new Yylex(new ByteArrayInputStream(file)));
    try {
      for (Object obj : ((List) parser.parse().value)) {
        ParserStatement statement = (ParserStatement) obj;
        statement.acceptParserStatementVisitor(this);
      }
    } catch (PMLParseException e) {
      err.println(errorMessage(e, new ByteArrayInputStream(file)));
    }
  }


  /**
   * Processes the input line by line.
   *
   * @throws IOException if there is some I/O problem.
   */
  public void interactive() throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    out.println("Markov The Beast v0.1");
    if (printPrompt) out.print("# ");
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      PMLParser parser = new PMLParser(new Yylex(new ByteArrayInputStream(line.getBytes())));
      try {
        for (Object obj : ((List) parser.parse().value)) {
          ParserStatement statement = (ParserStatement) obj;
          statement.acceptParserStatementVisitor(this);
          history.add(statement);
        }
      } catch (PMLParseException e) {
        System.out.println(errorMessage(e, new ByteArrayInputStream(line.getBytes())));
      } catch (Exception e) {
        if (printStackTraces) e.printStackTrace();
        else out.print(e.getMessage());
      }
      if (printPrompt) out.print("# ");
    }
  }


  public void execute() throws IOException {
    byte[] buffer = new byte[1000];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    while (in.available() > 0) {
      int howmany = in.read(buffer);
      os.write(buffer, 0, howmany);
    }
    byte[] file = os.toByteArray();

    PMLParser parser = new PMLParser(new Yylex(new ByteArrayInputStream(file)));
    try {
      for (Object obj : ((List) parser.parse().value)) {
        ParserStatement statement = (ParserStatement) obj;
        statement.acceptParserStatementVisitor(this);
        history.add(statement);
      }
    } catch (PMLParseException e) {
      System.out.println(errorMessage(e, new ByteArrayInputStream(file)));
    } catch (Exception e) {
      if (printStackTraces) e.printStackTrace();
      else out.print(e.getMessage());
    }
  }

  public static void main(String[] args) throws Exception {
    Shell shell = new Shell(System.in, System.out, System.err);
    shell.interactive();
  }

  public static String errorMessage(PMLParseException exception, InputStream is) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    int lineNr = 0;
    int colNr = exception.getCol();
    for (String line = reader.readLine(); line != null; line = reader.readLine(), ++lineNr) {
      if (lineNr == exception.getLine()) {
        StringBuffer buffer = new StringBuffer(line);
        //System.out.println(buffer);
        buffer.insert(colNr, "!ERROR!");
        return "Error on line " + lineNr + ": " + buffer.toString();
      }
      colNr -= line.length() + 1;
    }
    return "Syntax Error!";
  }

  public void visitCreateType(ParserCreateType parserCreateType) {
    signature.createType(parserCreateType.name, parserCreateType.unknowns, parserCreateType.getNames());
    out.println("Type " + parserCreateType.name + " created.");
    signatureUpdated = true;
  }

  public void visitCreatePredicate(ParserCreatePredicate parserCreatePredicate) {
    LinkedList<Type> types = new LinkedList<Type>();
    for (String name : parserCreatePredicate.types) {
      Type type = signature.getType(name);
      if (type == null) throw new RuntimeException("There is no type with name " + name);
      types.add(type);
    }
    signature.createPredicate(parserCreatePredicate.name, types);
    out.println("Predicate " + parserCreatePredicate.name + " created.");
    signatureUpdated = true;

  }

  public void visitCreateWeightFunction(ParserCreateWeightFunction parserCreateWeightFunction) {
    LinkedList<Type> types = new LinkedList<Type>();
    for (String name : parserCreateWeightFunction.argTypes)
      types.add(signature.getType(name));
    String returnType = parserCreateWeightFunction.returnType;
    String name = parserCreateWeightFunction.name;
    if (!returnType.startsWith("Double"))
      throw new RuntimeException("Return type of a weight function must be Double(+/-)");
    if (returnType.endsWith("-"))
      signature.createWeightFunction(name, false, types);
    else if (returnType.endsWith("+"))
      signature.createWeightFunction(name, true, types);
    else
      signature.createWeightFunction(name, types);
    signatureUpdated = true;
  }

  public Quantification pushQuantification(List<ParserTyping> vars) {
    LinkedList<Variable> quantification = new LinkedList<Variable>();
    HashMap<String, Variable> map = new HashMap<String, Variable>();
    for (ParserTyping typing : vars) {
      Variable variable = new Variable(signature.getType(typing.type), typing.var);
      quantification.add(variable);
      map.put(typing.var, variable);
    }
    variables.push(map);
    return new Quantification(quantification);
  }

  public Map<String, Variable> popQuantification() {
    return variables.pop();
  }

  public void visitFactorFormula(ParserFactorFormula parserFactorFormula) {
    rootFactor = parserFactorFormula;
    Quantification quantification = parserFactorFormula.quantification == null ?
            new Quantification(new ArrayList<Variable>()) :
            pushQuantification(parserFactorFormula.quantification);
    if (parserFactorFormula.condition != null)
      parserFactorFormula.condition.acceptParserFormulaVisitor(this);
    else
      formula = null;
    BooleanFormula condition = formula;
    parserFactorFormula.formula.acceptParserFormulaVisitor(this);
    BooleanFormula formula = this.formula;
    parserFactorFormula.weight.acceptParserTermVisitor(this);
    Term weight = term;
    FactorFormula factorFormula = new FactorFormula(quantification, condition, formula, weight);
    model.addFactorFormula(factorFormula);
    if (parserFactorFormula.quantification != null)
      popQuantification();
    out.println("Factor added: " + factorFormula);
  }

  public void visitImport(ParserImport parserImport) {
    File file = null;
    try {
      file = new File(filename(parserImport.filename));
      PMLParser parser = new PMLParser(new Yylex(new FileInputStream(file)));
      for (Object obj : ((List) parser.parse().value)) {
        ParserStatement statement = (ParserStatement) obj;
        statement.acceptParserStatementVisitor(this);
      }
    } catch (PMLParseException e) {
      try {
        System.out.println(errorMessage(e, new FileInputStream(file)));
      } catch (IOException e1) {
        e1.printStackTrace();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  public void visitAddPredicateToModel(ParserAddPredicateToModel parserAddPredicateToModel) {
    out.print("Predicates ");
    int index = 0;
    for (String name : parserAddPredicateToModel.predicates) {
      if (index++ > 0) out.print(", ");
      out.print(name);
      UserPredicate predicate = (UserPredicate) signature.getPredicate(name);
      switch (parserAddPredicateToModel.type) {
        case HIDDEN:
          model.addHiddenPredicate(predicate);
          break;
        case OBSERVED:
          model.addObservedPredicate(predicate);
          break;
      }
    }
    out.println(" added to the model.");
  }

  public void visitInspect(ParserInspect parserInspect) {
    if (!parserInspect.inspectType) {
      Predicate predicate = signature.getPredicate(parserInspect.target);
      if (predicate != null) {
        out.println(predicate);
        return;
      }
      Function function = signature.getFunction(parserInspect.target);
      if (function != null) {
        out.println(function);
      }
    } else {
      Type type = signature.getType(parserInspect.target);
      if (type.getTypeClass() == Type.Class.CATEGORICAL || type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) {
        out.print(type);
        out.print(": ");
        if (type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) out.print("... ");
        out.println(Util.toStringWithDelimiters(type.getConstants(), ", "));
      } else {
        out.println(type);
      }

    }
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }

  private String filename(String name) {
    if (name.startsWith("/") || directory == null) return name;
    return directory + "/" + name;
  }

  public void visitLoad(ParserLoad parserLoad) {
    update();
    try {
      if (parserLoad.target == null) {
        if (!parserLoad.gold) {
          guess.load(new FileInputStream(filename(parserLoad.file)));
        }
      } else {

      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    out.println("Atoms loaded.");
  }

  private void update() {
    if (signatureUpdated) {
      guess = signature.createGroundAtoms();
      gold = signature.createGroundAtoms();
      //solution = new Solution(model, weights);
      signatureUpdated = false;
    }
    if (modelUpdated) {
      weights = signature.createWeights();
      scores = new Scores(model, weights);
      solver.configure(model, weights);
      solution = new Solution(model, weights);
      if (learner == null) {
        learner = new OnlineLearner(model, weights);
      } else {
        learner.configure(model, weights);
      }
      instances = new TrainingInstances();
      modelUpdated = false;
    }

  }

  public void visitPrint(ParserPrint parserPrint) {
    if (parserPrint.target == null) {
      if (!parserPrint.scores) {
        if (!parserPrint.gold) {
          out.print(guess);
        }
      } else {
        out.print(scores);
      }
    } else {
      UserPredicate predicate = (UserPredicate) signature.getPredicate(parserPrint.target);
      out.print(guess.getGroundAtomsOf(predicate));
    }
  }

  public void visitSolve(ParserSolve parserSolve) {
    update();
    if (!guess.isEmpty(model.getHiddenPredicates()))
      solver.solve(guess, parserSolve.numIterations);
    else
      solver.solve(parserSolve.numIterations);
    guess.load(solver.getAtoms(), model.getHiddenPredicates());

    out.println("Solved in " + solver.getIterationCount() + " step(s).");
  }

  public void visitGenerateTypes(ParserGenerateTypes parserGenerateTypes) {
    TypeGenerator generator = typeGenerators.get(parserGenerateTypes.generator);
    if (generator == null)
      throw new RuntimeException("No type generator with nane " + parserGenerateTypes.generator);
    try {
      generator.generateTypes(new FileInputStream(filename(parserGenerateTypes.file)), signature);
      out.println("Types generated.");
    } catch (FileNotFoundException e) {
      throw new RuntimeException("Couldn't load " + filename(parserGenerateTypes.file), e);
    }
    signatureUpdated = true;
  }

  public void visitSaveTypes(ParserSaveTypes parserSaveTypes) {
    try {
      PrintStream file = new PrintStream(new FileOutputStream(filename(parserSaveTypes.file)));
      for (Type type : signature.getTypes())
        if (type.getTypeClass() == Type.Class.CATEGORICAL || type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) {
          file.print("type " + type + ": ");
          if (type.getTypeClass() == Type.Class.CATEGORICAL_UNKNOWN) file.print("... ");
          file.print(Util.toStringWithDelimiters(type.getConstants(), ", "));
          file.println(";");
        }
      file.close();
      out.println("Types saved.");
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public void visitLoadCorpus(ParserLoadCorpus parserLoadCorpus) {
    update();
    if (parserLoadCorpus.factory != null) {
      CorpusFactory factory = getCorpusFactory(parserLoadCorpus.factory);
      corpus = factory.createCorpus(signature, new File(filename(parserLoadCorpus.file)));
      if (parserLoadCorpus.from != -1) {
        Iterator<GroundAtoms> instance = corpus.iterator();
        corpus = new RandomAccessCorpus(signature, parserLoadCorpus.to - parserLoadCorpus.from);
        for (int i = 0; i < parserLoadCorpus.from; ++i) instance.next();
        for (int i = parserLoadCorpus.from; i < parserLoadCorpus.to; ++i) corpus.add(instance.next());
      }
      iterator = corpus.iterator();
      listIterator = corpus.listIterator();
      GroundAtoms first = iterator.next();
      loadAtoms(first);
    }
    out.println("Corpus loaded.");
  }

  public void visitLoadScores(ParserLoadScores parserLoadScores) {
    update();
    File file = new File(filename(parserLoadScores.file));
    try {
      scores.load(new FileInputStream(file));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    out.println("Scores loaded.");
  }

  public void visitShift(ParserShift parserShift) {
    int delta = parserShift.delta;
    jump(delta);
  }

  private void jump(int delta) {
    if (iterator == null)
      throw new RuntimeException("No corpus loaded, can't go forward or backwards");
    if (delta < 0 && listIterator == null)
      throw new RuntimeException("Can't go backwards with this corpus.");
    try {
      if (delta < 0) {
        for (int i = 0; i > delta + 1; --i)
          listIterator.previous();
        GroundAtoms atoms = listIterator.previous();
        loadAtoms(atoms);
      } else {
        for (int i = 0; i < delta - 1; ++i)
          iterator.next();
        GroundAtoms atoms = iterator.next();
        loadAtoms(atoms);
      }
    } catch (Exception e) {
      throw new RuntimeException("You ran out of bounds.");
    }
  }

  private void loadAtoms(GroundAtoms atoms) {
    gold.load(atoms);
    guess.load(atoms);
    solver.setObservation(atoms);
  }

  public void visitGreedy(ParserGreedy parserGreedy) {
    guess.load(scores.greedySolve(0.0), model.getHiddenPredicates());
    out.println("Greedy solution extracted.");
  }

  public void visitLoadWeights(ParserLoadWeights parserLoadWeights) {
    try {
      if (weights == null) weights = signature.createWeights();
      weights.load(new FileInputStream(parserLoadWeights.file));
      weightsUpdated = true;
      out.println(weights.getFeatureCount() + " weights loaded.");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void visitCollect(ParserCollect parserCollect) {
    if (corpus == null)
      throw new ShellException("Need a corpus for collecting features!");
    update();
    int oldCount = weights.getFeatureCount();
    FeatureCollector collector = new FeatureCollector(model);
    collector.setProgressReporter(new DotProgressReporter(out, 5, 5, 5));
    collector.collect(corpus, weights);
    out.println("Collected " + (weights.getFeatureCount() - oldCount) + " features.");


  }

  public void visitPrintWeights(ParserPrintWeights parserPrintWeights) {
    if (parserPrintWeights.function == null) weights.save(out);
    else {
      WeightFunction function = (WeightFunction) signature.getFunction(parserPrintWeights.function);
      weights.save(function, out);
    }
  }

  public void visitLearn(ParserLearn parserLearn) {
    update();
    if (parserLearn.epochs == -1) {
      if (parserLearn.instances == -1) {
        learner.learnOne(gold, instances);
        while (iterator.hasNext()) {
          jump(1);
          learner.learnOne(gold, instances);
        }
      } else {
        int instance = 1;
        learner.learnOne(gold, instances);
        while (iterator.hasNext() && instance++ < parserLearn.instances) {
          jump(1);
          learner.learnOne(gold, instances);
        }
      }
    } else {

    }

  }

  public void visitSet(ParserSet parserSet) {
    if ("learner".equals(parserSet.qualifier)) {
      if ("maxIterations".equals(parserSet.property)) {
        CuttingPlaneSolver solver = (CuttingPlaneSolver) learner.getSolver();
        solver.setMaxIterations(parserSet.intValue);
        out.println(parserSet.qualifier + "." + parserSet.property + " set to " + parserSet.intValue + ".");
      }
    }
  }

  public void visitClear(ParserClear parserClear) {
    if (parserClear.what.equals("atoms")) {
      guess.clear(model.getHiddenPredicates());
      out.println("Atoms cleared.");
    }
    else if (parserClear.what.equals("scores")) {
      scores.clear();
      out.println("Scores cleared.");
    }
  }


  public void visitAtom(ParserAtom parserAtom) {
    LinkedList<Term> args = new LinkedList<Term>();
    Predicate predicate = signature.getPredicate(parserAtom.predicate);
    if (predicate == null)
      throw new RuntimeException("There is no predicate called " + parserAtom.predicate);
    int index = 0;
    for (ParserTerm term : parserAtom.args) {
      typeContext.push(predicate.getArgumentTypes().get(index++));
      term.acceptParserTermVisitor(this);
      args.add(this.term);
      typeContext.pop();
    }
    formula = new PredicateAtom(predicate, args);
  }

  public void visitConjuction(ParserConjunction parserConjunction) {
    LinkedList<BooleanFormula> args = new LinkedList<BooleanFormula>();
    parserConjunction.lhs.acceptParserFormulaVisitor(this);
    args.add(this.formula);
    ParserFormula rhs = parserConjunction.rhs;
    while (rhs instanceof ParserConjunction) {
      ParserConjunction c = (ParserConjunction) rhs;
      c.lhs.acceptParserFormulaVisitor(this);
      args.add(this.formula);
      rhs = c.rhs;
    }
    rhs.acceptParserFormulaVisitor(this);
    args.add(this.formula);
    formula = new Conjunction(args);
  }

  public void visitImplies(ParserImplies parserImplies) {
    parserImplies.lhs.acceptParserFormulaVisitor(this);
    BooleanFormula lhs = this.formula;
    parserImplies.rhs.acceptParserFormulaVisitor(this);
    BooleanFormula rhs = this.formula;
    formula = new Implication(lhs, rhs);
  }

  public void visitCardinalityConstraint(ParserCardinalityConstraint parserCardinalityConstraint) {
    typeContext.push(Type.INT);
    parserCardinalityConstraint.lowerBound.acceptParserTermVisitor(this);
    typeContext.pop();
    Term lb = term;
    Quantification quantification = pushQuantification(parserCardinalityConstraint.quantification);
    parserCardinalityConstraint.formula.acceptParserFormulaVisitor(this);
    popQuantification();
    typeContext.push(Type.INT);
    parserCardinalityConstraint.upperBound.acceptParserTermVisitor(this);
    typeContext.pop();
    Term ub = term;
    formula = new CardinalityConstraint(lb, quantification, formula, ub);
  }

  public void visitComparison(ParserComparison parserComparison) {
    parserComparison.lhs.acceptParserTermVisitor(this);
    Term lhs = term;
    parserComparison.rhs.acceptParserTermVisitor(this);
    Term rhs = term;
    switch (parserComparison.type) {
      case LEQ:
        formula = new PredicateAtom(IntLEQ.INT_LEQ, lhs, rhs);
        break;
      case LT:
        formula = new PredicateAtom(IntLT.INT_LT, lhs, rhs);
        break;
      case GT:
        formula = new PredicateAtom(IntGT.INT_GT, lhs, rhs);
        break;
    }
  }

  public void visitAcyclicityConstraint(ParserAcyclicityConstraint parserAcyclicityConstraint) {
    UserPredicate predicate = (UserPredicate) signature.getPredicate(parserAcyclicityConstraint.predicate);
    formula = new AcyclicityConstraint(predicate);
  }

  public void visitNamedConstant(ParserNamedConstant parserNamedConstant) {
    term = typeContext.peek().getConstant(parserNamedConstant.name);
    //typeCheck();
  }

  public void visitIntConstant(ParserIntConstant parserIntConstant) {
    term = new IntConstant(parserIntConstant.number);
    typeCheck();

  }

  public void visitParserAdd(ParserAdd parserAdd) {
    parserAdd.lhs.acceptParserTermVisitor(this);
    Term lhs = term;
    parserAdd.rhs.acceptParserTermVisitor(this);
    Term rhs = term;
    term = new FunctionApplication(IntAdd.ADD, lhs, rhs);
    typeCheck();
  }

  public void visitParserMinus(ParserMinus parserMinus) {
    parserMinus.lhs.acceptParserTermVisitor(this);
    Term lhs = term;
    parserMinus.rhs.acceptParserTermVisitor(this);
    Term rhs = term;
    term = new FunctionApplication(IntMinus.MINUS, lhs, rhs);
    typeCheck();
  }

  public void visitDontCare(ParserDontCare parserDontCare) {
    term = DontCare.DONTCARE;
    //typeCheck();
  }

  public void visitFunctionApplication(ParserFunctionApplication parserFunctionApplication) {
    LinkedList<Term> args = new LinkedList<Term>();
    Function function = signature.getFunction(parserFunctionApplication.function);
    int index = 0;
    for (ParserTerm term : parserFunctionApplication.args) {
      typeContext.push(function.getArgumentTypes().get(index++));
      term.acceptParserTermVisitor(this);
      args.add(this.term);
      typeContext.pop();
    }
    term = new FunctionApplication(function, args);
    typeCheck();
  }

  public void visitDoubleConstant(ParserDoubleConstant parserDoubleConstant) {
    term = new DoubleConstant(parserDoubleConstant.number);
    typeCheck();
  }

  private Variable resolve(String name) {
    for (HashMap<String, Variable> scope : variables) {
      Variable var = scope.get(name);
      if (var != null) return var;
    }
    return null;
  }

  public void visitVariable(ParserVariable parserVariable) {
    term = resolve(parserVariable.name);
    if (term == null) throw new RuntimeException(parserVariable.name + " was not quantified in " + rootFactor);
    typeCheck();
  }

  public void visitBins(ParserBins parserBins) {
    LinkedList<Integer> bins = new LinkedList<Integer>();
    for (ParserTerm term : parserBins.bins){
      if (term instanceof ParserIntConstant) {
        ParserIntConstant intConstant = (ParserIntConstant) term;
        bins.add(intConstant.number);
      } else
        throw new ShellException("bins must be integers");
    }
    parserBins.argument.acceptParserTermVisitor(this);
    term = new BinnedInt(bins, term);

  }

  private void typeCheck() {
    if (!typeContext.isEmpty() && !term.getType().equals(typeContext.peek()))
      throw new RuntimeException("Variable " + term + " must be of type " + typeContext.peek() + " in " +
              rootFactor);
  }

  /**
   * Gets a factory which can build corpora. We provide a few built-in factories but user defined ones can be added (and
   * then used from within the interpreter).
   *
   * @param name the name of the factory
   * @return a corpus factory that his been registered with this beast under the given name
   */
  public CorpusFactory getCorpusFactory(String name) {
    return corpusFactories.get(name);
  }

  /**
   * Registers a corpus factory under a name which can be referred to from within the  interpreter.
   *
   * @param name    name of the factory
   * @param factory the factory to be registered.
   */
  public void registerCorpusFactory(String name, CorpusFactory factory) {
    corpusFactories.put(name, factory);
  }

  public void registerTypeGenerator(String name, TypeGenerator generator) {
    typeGenerators.put(name, generator);
  }

  private void initCorpusTools() {
    registerCorpusFactory("conll06", CoNLLCorpus.CONLL_06_FACTORY);
    registerTypeGenerator("conll06", CoNLLCorpus.CONLL_06_GENERATOR);
  }


}