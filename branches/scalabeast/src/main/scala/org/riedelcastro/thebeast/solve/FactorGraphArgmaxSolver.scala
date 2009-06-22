package org.riedelcastro.thebeast.solve


import env.functions.Sum
import env.{Term, DoubleTerm, Env}
import util.{DoubleFactorGraph, FactorGraph}
/**
 * @author Sebastian Riedel
 */

trait FactorGraphArgmaxSolver extends ArgmaxSolver {

  def solve(): ArgmaxResult;

  def setFactorGraph(graph:DoubleFactorGraph);

  def argmax(term: DoubleTerm) = {
    term match {
      case x:Sum => {
        setFactorGraph(new DoubleFactorGraph(x.args));
        solve();
      }
      case _ => ArgmaxResult(null, Status.CantDo, 0)
    }
  }
}