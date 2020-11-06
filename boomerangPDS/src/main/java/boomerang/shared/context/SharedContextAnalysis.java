package boomerang.shared.context;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.DefaultBoomerangOptions;
import boomerang.ForwardQuery;
import boomerang.Query;
import boomerang.QueryGraph;
import boomerang.callgraph.ObservableICFG;
import boomerang.results.AbstractBoomerangResults.Context;
import boomerang.results.BackwardBoomerangResults;
import boomerang.results.ForwardBoomerangResults;
import boomerang.scene.AllocVal;
import boomerang.scene.ControlFlowGraph.Edge;
import boomerang.scene.DataFlowScope;
import boomerang.scene.Method;
import boomerang.scene.SootDataFlowScope;
import boomerang.scene.Statement;
import boomerang.scene.Val;
import boomerang.scene.jimple.IntAndStringBoomerangOptions;
import boomerang.scene.jimple.JimpleStatement;
import boomerang.scene.jimple.SootCallGraph;
import boomerang.shared.context.Specification.Parameter;
import boomerang.shared.context.Specification.QueryDirection;
import boomerang.shared.context.Specification.QuerySelector;
import boomerang.shared.context.Specification.SootMethodWithSelector;
import boomerang.solver.ForwardBoomerangSolver;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import soot.Scene;
import soot.jimple.Stmt;
import sync.pds.solver.nodes.Node;
import wpds.impl.Weight.NoWeight;

public class SharedContextAnalysis {

  private final DefaultBoomerangOptions customBoomerangOptions;
  private final Specification spec;
  private final DataFlowScope scope;
  private final SootCallGraph callGraph;
  private final LinkedList<QueryWithContext> queryQueue = Lists.newLinkedList();
  private final Set<Query> visited = Sets.newHashSet();

  public SharedContextAnalysis(Specification specification) {
    spec = specification;
    callGraph = new SootCallGraph();
    scope = SootDataFlowScope.make(Scene.v());
    customBoomerangOptions =
        new IntAndStringBoomerangOptions() {
          @Override
          public Optional<AllocVal> getAllocationVal(
              Method m, Statement stmt, Val fact, ObservableICFG<Statement, Method> icfg) {
            if (stmt.isAssign() && stmt.getLeftOp().equals(fact) && isStringOrIntAllocation(stmt)) {
              return Optional.of(new AllocVal(stmt.getLeftOp(), stmt, stmt.getRightOp()));
            }
            return super.getAllocationVal(m, stmt, fact, icfg);
          }

          @Override
          public int analysisTimeoutMS() {
            return 1999999;
          }

          @Override
          public boolean allowMultipleQueries() {
            return true;
          }
        };
  }

  public Collection<ForwardQuery> run(Query query) {
    queryQueue.add(new QueryWithContext(query));
    Boomerang bSolver = new Boomerang(callGraph, scope, customBoomerangOptions);
    Collection<ForwardQuery> finalAllocationSites = Sets.newHashSet();
    while (!queryQueue.isEmpty()) {
      QueryWithContext pop = queryQueue.pop();
      if (pop.query instanceof ForwardQuery) {
        ForwardBoomerangResults<NoWeight> results;
        ForwardQuery currentQuery = (ForwardQuery) pop.query;
        if (pop.parentQuery == null) {
          results = bSolver.solve(currentQuery);
        } else {
          results = bSolver.solveUnderScope(currentQuery, pop.triggeringNode, pop.parentQuery);
        }

        Table<Edge, Val, NoWeight> forwardResults =
            results.asStatementValWeightTable((ForwardQuery) pop.query);
        // Any ForwardQuery may trigger additional ForwardQuery under its own scope.
        triggerNewForwardQueries(forwardResults, currentQuery);
      } else {
        BackwardBoomerangResults<NoWeight> results;
        if (pop.parentQuery == null) {
          results = bSolver.solve((BackwardQuery) pop.query);
        } else {
          results =
              bSolver.solveUnderScope(
                  (BackwardQuery) pop.query, pop.triggeringNode, pop.parentQuery);
        }
        Table<Edge, Val, NoWeight> backwardResults = bSolver.getBackwardSolvers().get(query)
            .asStatementValWeightTable();

        triggerNewBackwardQueries(backwardResults, pop.query);
        Map<ForwardQuery, Context> allocationSites = results.getAllocationSites();

        for (Entry<ForwardQuery, Context> entry : allocationSites.entrySet()) {
          ForwardQuery forwardQuery = entry.getKey();
          Statement start = forwardQuery.cfgEdge().getStart();
          if (isStringOrIntAllocation(start)) {
            finalAllocationSites.add(entry.getKey());
          }
        }
        // Any ForwardQuery may trigger additional ForwardQuery under its own scope.
        for (ForwardBoomerangSolver<NoWeight> solver : bSolver.getSolvers().values()) {
          triggerNewForwardQueries(solver.asStatementValWeightTable(), solver.getQuery());
        }
      }
    }

    QueryGraph<NoWeight> queryGraph = bSolver.getQueryGraph();
    bSolver.unregisterAllListeners();
    System.out.println(queryGraph.toDotString());
    return finalAllocationSites;
  }

  private void triggerNewBackwardQueries(Table<Edge, Val, NoWeight> backwardResults, Query lastQuery) {
    for(Cell<Edge, Val, NoWeight> cell : backwardResults.cellSet()){
      Edge triggeringEdge = cell.getRowKey();
      Statement stmt = triggeringEdge.getStart();
      Val fact = cell.getColumnKey();
      if (stmt.containsInvokeExpr()) {
        Set<SootMethodWithSelector> selectors = spec.getMethodAndQueries().stream().filter(x -> isInOnList(x, stmt, fact, QueryDirection.BACKWARD)).collect(
            Collectors.toSet());
        for(SootMethodWithSelector sel : selectors){
            Collection<Query> queries = createNewQueries(sel, stmt);
            for(Query q : queries){
              addToQueue(
                  new QueryWithContext(
                      q,
                      new Node<>(
                          triggeringEdge, fact),
                      lastQuery));
            }
        }
      }
    }
  }

  private Collection<Query> createNewQueries(SootMethodWithSelector sel, Statement stmt) {
    Set<Query> results = Sets.newHashSet();
    Method method = stmt.getMethod();
    for(QuerySelector qSel : sel.getGo()){
      Optional<Val> parameterVal = getParameterVal(stmt, qSel.argumentSelection);
      if(parameterVal.isPresent()){
        if(qSel.direction == QueryDirection.BACKWARD){
          for(Statement pred : method.getControlFlowGraph().getPredsOf(stmt)){
              results.add(BackwardQuery.make(new Edge(pred, stmt), parameterVal.get()));
          }
        } else if(qSel.direction == QueryDirection.FORWARD){
          for(Statement succ : method.getControlFlowGraph().getSuccsOf(stmt)){
            results.add(BackwardQuery.make(new Edge(stmt, succ), parameterVal.get()));
          }
        }
      }
    }
    return results;
  }

  public boolean isInOnList(SootMethodWithSelector methodSelector, Statement stmt, Val fact, QueryDirection direction){
    if(stmt instanceof JimpleStatement){
      //This only works for Soot propagations
      Stmt jimpleStmt = ((JimpleStatement) stmt).getDelegate();
      if(jimpleStmt.getInvokeExpr().getMethod().getSignature().equals(methodSelector.getSootMethod())){
        Collection<QuerySelector> on = methodSelector.getOn();
        return isInList(on, direction, stmt, fact);
      }
    }
    return false;
  }

  private boolean isInList(Collection<QuerySelector> list,
      QueryDirection direction, Statement stmt, Val fact) {
    return list.stream().anyMatch(sel -> (sel.direction == direction && isParameter(stmt, fact, sel.argumentSelection)));
  }

  private boolean isParameter(Statement stmt, Val fact, Parameter argumentSelection) {
    if(argumentSelection.equals(Parameter.base())){
      return stmt.getInvokeExpr().getBase().equals(fact);
    } if(argumentSelection.equals(Parameter.returnParam())){
      return stmt.isAssign() && stmt.getLeftOp().equals(fact);
    }
    return stmt.getInvokeExpr().getArgs().size() > argumentSelection.getValue() && argumentSelection.getValue() >= 0 && stmt.getInvokeExpr().getArg(argumentSelection.getValue()).equals(fact);
  }

  private Optional<Val> getParameterVal(Statement stmt, Parameter selector){
    if(stmt.containsInvokeExpr() && !stmt.getInvokeExpr().isStaticInvokeExpr() && selector.equals(Parameter.base())){
      return Optional.of(stmt.getInvokeExpr().getBase());
    } if( stmt.isAssign() && selector.equals(Parameter.returnParam())){
      return Optional.of(stmt.getLeftOp());
    }
    if(stmt.getInvokeExpr().getArgs().size() > selector.getValue() && selector.getValue() >= 0){
      return Optional.of(stmt.getInvokeExpr().getArg(selector.getValue()));
    }
    return Optional.empty();
  }

  private void triggerNewForwardQueries(
      Table<Edge, Val, NoWeight> forwardResults, Query currentQuery) {
    for (Cell<Edge, Val, NoWeight> cell : forwardResults.cellSet()) {
      Val reachingVariable = cell.getColumnKey();
      Edge edge = cell.getRowKey();
      Collection<Query> newQueries = createNextQuery(edge, reachingVariable);
      for (Query newQuery : newQueries) {
        addToQueue(
            new QueryWithContext(newQuery, new Node<>(edge, reachingVariable), currentQuery));
      }
    }
  }

  private void addToQueue(QueryWithContext nextQuery) {
    if (visited.add(nextQuery.query)) {
      queryQueue.add(nextQuery);
    }
  }

  private boolean isStringOrIntAllocation(Statement stmt) {
    return stmt.isAssign()
        && (stmt.getRightOp().isIntConstant() || stmt.getRightOp().isStringConstant());
  }

  private boolean isInSourceList(Statement stmt) {
    return stmt.getInvokeExpr()
            .getMethod()
            .getDeclaringClass()
            .getFullyQualifiedName()
            .equals("java.lang.String")
        && stmt.getInvokeExpr().getMethod().getName().equals("<init>");
  }

  private Collection<Query> createNextQuery(Edge edge, Val reachingVariable) {
    Statement potentialCallSite = edge.getTarget();
    Method method = potentialCallSite.getMethod();
    if (potentialCallSite.containsInvokeExpr() && isInSourceList(potentialCallSite)) {
      if (potentialCallSite.getInvokeExpr().getArgs().contains(reachingVariable)) {
        Set<Query> res = Sets.newHashSet();
        for (Statement succ : method.getControlFlowGraph().getSuccsOf(potentialCallSite)) {
          Val base = potentialCallSite.getInvokeExpr().getBase();
          res.add(
              new ForwardQuery(
                  new Edge(potentialCallSite, succ), new AllocVal(base, potentialCallSite, base)));
        }

        return res;
      }
    }
    return Sets.newHashSet();
  }

  private static class QueryWithContext {
    private QueryWithContext(Query query) {
      this.query = query;
    }

    private QueryWithContext(Query query, Node<Edge, Val> triggeringNode, Query parentQuery) {
      this.query = query;
      this.parentQuery = parentQuery;
      this.triggeringNode = triggeringNode;
    }

    Query query;
    Query parentQuery;
    Node<Edge, Val> triggeringNode;
  }
}