package org.riedelcastro.thebeast.learn

import org.specs._
import runner.JUnit4
import org.riedelcastro.thebeast.solve.ExhaustiveSearch
import org.riedelcastro.thebeast.CitationMatchingFixtures
import org.riedelcastro.thebeast.env.doubles.{SumOverGroundings, LogLinear}
import org.riedelcastro.thebeast.env.{MutableEnv, Env, TheBeastEnv}
import org.riedelcastro.thebeast.env.vectors.{Vector, VectorVar, UnitVector}

/**
 * @author Sebastian Riedel
 */

class OnlineLearnerTest extends JUnit4(OnlineLearnerSpecification)
object OnlineLearnerSpecification extends Specification with TheBeastEnv with CitationMatchingFixtures {

  "An Online Learner" should {
    "separate data if data is separable" in {

      //todo: factor out this training set
      val y1 = createWorldWhereABAreSimilarAndSame
      val y2 = createWorldWhereABAreSimilarButABCAreSame

      val trainingSet = Seq(y1,y2).map(y => y.mask(Set(same)))

      val features =
        vectorSum(Citations,Citations)
                  {(c1,c2)=>$(similar(c1,c2) ~> same(c1,c2)) * UnitVector("similar")} +
        vectorSum(Citations,Citations,Citations)
                  {(c1,c2,c3)=>$((same(c1,c2) && same(c2,c3)) ~> same(c1,c3)) * UnitVector("trans")}

      val theta = new VectorVar("theta")
//      val unnormalized = LogLinear(features, theta, 0.0) ? same
      val unnormalized = exp(features dot theta) ? same
      val model = normalize(unnormalized)
      val ll = SumOverGroundings(model,Seq(y1,y2)) ? theta


      val learner = new OnlineLearner

      learner.maxEpochs = 1
      val result = learner.argmax(ll)

      for (y:Env <- Seq(y1,y2)){
        val objective = unnormalized.ground(y.mask(Set(same)).overlay(result.result))
        val guess = ExhaustiveSearch.argmax(objective).result
        y(same).getSources(Some(true)) must_== guess(same).getSources(Some(true))
      }


//      val weights = learner.learn(features,trainingSet)

//      for (y <- trainingSet){
//        val score = (features dot weights).ground(y)
//        val guess = ExhaustiveSearch.argmax(score).result
//        y.unmasked(same).getSources(Some(true)) must_== guess(same).getSources(Some(true))
//      }
    }
  }
}