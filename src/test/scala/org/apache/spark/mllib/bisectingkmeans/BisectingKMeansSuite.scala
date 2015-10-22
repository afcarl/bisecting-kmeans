/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.bisectingkmeans

import breeze.linalg.{Vector => BV, norm => breezeNorm}
import org.apache.spark.SparkFunSuite
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.mllib.util.TestingUtils._


class BisectingKMeansSuite extends SparkFunSuite with MLlibTestSparkContext {

  test("run") {
    val algo = new BisectingKMeans().setK(123).setSeed(1)
    val localSeed: Seq[Vector] = (0 to 999).map(i => Vectors.dense(i.toDouble, i.toDouble)).toSeq
    val data = sc.parallelize(localSeed, 2)
    val model = algo.run(data)
    assert(model.getClusters.length == 123)
    assert(model.node.getHeight ~== 705.6925 absTol 10E-4)

    // check the relations between a parent cluster and its children
    assert(model.node.getParent === None)
    assert(model.node.getChildren.head.getParent.get === model.node)
    assert(model.node.getChildren.apply(1).getParent.get === model.node)
    assert(model.getClusters.forall(_.getParent.isDefined))
  }

  test("run with too many cluster size than the records") {
    val algo = new BisectingKMeans().setK(123).setSeed(1)
    val localSeed: Seq[Vector] = (0 to 99).map(i => Vectors.dense(i.toDouble, i.toDouble)).toSeq
    val data = sc.parallelize(localSeed)
    val model = algo.run(data)
    assert(model.getClusters.length == 100)
    assert(model.node.getHeight ~== 72.12489 absTol 10E-4)
  }

  test("setNumClusters") {
    val algo = new BisectingKMeans()
    assert(algo.getK == 20)
    algo.setK(1000)
    assert(algo.getK == 1000)
  }

  test("setSubIterations") {
    val algo = new BisectingKMeans()
    assert(algo.getMaxIterations == 20)
    algo.setMaxIterations(15)
    assert(algo.getMaxIterations == 15)
  }

  test("setSeed") {
    val algo = new BisectingKMeans()
    assert(algo.getSeed == 1)
    algo.setSeed(987)
    assert(algo.getSeed == 987)
  }

  test("summarize center stats") {
    val algo = new BisectingKMeans
    val local = Seq(
      (BigInt(4), Vectors.dense(1.5, 1.5).toBreeze),
      (BigInt(4), Vectors.dense(2.5, 2.5).toBreeze),
      (BigInt(5), Vectors.dense(11.5, 11.5).toBreeze),
      (BigInt(5), Vectors.dense(12.5, 12.5).toBreeze),
      (BigInt(6), Vectors.dense(21.5, 21.5).toBreeze),
      (BigInt(6), Vectors.dense(22.5, 22.5).toBreeze),
      (BigInt(7), Vectors.dense(31.5, 31.5).toBreeze),
      (BigInt(7), Vectors.dense(32.5, 32.5).toBreeze)
    )
    val data = sc.parallelize(local)

    val clusters = BisectingKMeans.summarizeClusters(data)
    assert(clusters.size === 4)
    assert(clusters(4).center === Vectors.dense(2.0, 2.0).toBreeze)
    assert(clusters(4).variances === Vectors.dense(0.25, 0.25).toBreeze)
    assert(clusters(4).rows === 2)
    assert(clusters(5).center === Vectors.dense(12.0, 12.0).toBreeze)
    assert(clusters(5).variances === Vectors.dense(0.25, 0.25).toBreeze)
    assert(clusters(5).rows === 2)
    assert(clusters(6).center === Vectors.dense(22.0, 22.0).toBreeze)
    assert(clusters(6).variances === Vectors.dense(0.25, 0.25).toBreeze)
    assert(clusters(6).rows === 2)
    assert(clusters(7).center === Vectors.dense(32.0, 32.0).toBreeze)
    assert(clusters(7).variances === Vectors.dense(0.25, 0.25).toBreeze)
    assert(clusters(7).rows === 2)
  }

  test("initialize centers at next step") {
    val local = Seq(
      (BigInt(2), BV[Double](0.9, 0.9)), (BigInt(2), BV[Double](1.1, 1.1)),
      (BigInt(3), BV[Double](1.9, 1.9)), (BigInt(3), BV[Double](2.1, 2.1))
    )
    val data = sc.parallelize(local)
    val stats = Map[BigInt, BisectingClusterStat](
      BigInt(2) -> new BisectingClusterStat(2, BV[Double](1.0, 1.0) * 2.0, BV.zeros[Double](2)),
      BigInt(3) -> new BisectingClusterStat(2, BV[Double](2.0, 2.0) * 2.0, BV.zeros[Double](2))
    )
    val initNextCenters = BisectingKMeans.initNextCenters(data, stats)
    assert(initNextCenters.size === 4)
    assert(initNextCenters.keySet === Set(4, 5, 6, 7))
  }

  test("should assign each data to new clusters") {
    val seed = Seq(
      (BigInt(2), Vectors.dense(0.0, 0.0)), (BigInt(2), Vectors.dense(1.0, 1.0)),
      (BigInt(2), Vectors.dense(2.0, 2.0)), (BigInt(2), Vectors.dense(3.0, 3.0)),
      (BigInt(2), Vectors.dense(4.0, 4.0)), (BigInt(2), Vectors.dense(5.0, 5.0)),
      (BigInt(3), Vectors.dense(6.0, 6.0)), (BigInt(3), Vectors.dense(7.0, 7.0)),
      (BigInt(3), Vectors.dense(8.0, 8.0)), (BigInt(3), Vectors.dense(9.0, 9.0)),
      (BigInt(3), Vectors.dense(10.0, 10.0)), (BigInt(3), Vectors.dense(11.0, 11.0))
    ).map { case (idx, vector) => (idx, vector.toBreeze) }
    val newClusters = Map(
      BigInt(4) -> new BisectingClusterStat(3L, BV[Double](1.0, 1.0) :* 3.0, BV[Double](1.0, 1.0)),
      BigInt(5) -> new BisectingClusterStat(3L, BV[Double](4.0, 4.0) :* 3.0, BV[Double](1.0, 1.0)),
      BigInt(6) -> new BisectingClusterStat(3L, BV[Double](7.0, 7.0) :* 3.0, BV[Double](1.0, 1.0)),
      BigInt(7) -> new BisectingClusterStat(3L, BV[Double](10.0, 10.0) :* 3.0, BV[Double](1.0, 1.0))
    )
    val data = sc.parallelize(seed)
    val result = BisectingKMeans.updateClusterIndex(data, newClusters).collect().toSeq

    val expected = Seq(
      (4, Vectors.dense(0.0, 0.0)), (4, Vectors.dense(1.0, 1.0)), (4, Vectors.dense(2.0, 2.0)),
      (5, Vectors.dense(3.0, 3.0)), (5, Vectors.dense(4.0, 4.0)), (5, Vectors.dense(5.0, 5.0)),
      (6, Vectors.dense(6.0, 6.0)), (6, Vectors.dense(7.0, 7.0)), (6, Vectors.dense(8.0, 8.0)),
      (7, Vectors.dense(9.0, 9.0)), (7, Vectors.dense(10.0, 10.0)), (7, Vectors.dense(11.0, 11.0))
    ).map { case (idx, vector) => (idx, vector.toBreeze) }
    assert(result === expected)
  }

  test("findClosestCenter") {
    val metric = (bv1: BV[Double], bv2: BV[Double]) => breezeNorm(bv1 - bv2, 2.0)
    val centers = Seq(
      Vectors.sparse(5, Array(0, 1, 2), Array(0.0, 1.0, 2.0)).toBreeze,
      Vectors.sparse(5, Array(1, 2, 3), Array(1.0, 2.0, 3.0)).toBreeze,
      Vectors.sparse(5, Array(2, 3, 4), Array(2.0, 3.0, 4.0)).toBreeze
    )

    for (i <- 0 to (centers.size - 1)) {
      val point = centers(i)
      val closestIndex = BisectingKMeans.findClosestCenter(metric)(centers)(point)
      assert(closestIndex === i)
    }
  }

  test("should divide clusters correctly") {
    val local = Seq(
      (BigInt(2), BV[Double](0.9, 0.9)), (BigInt(2), BV[Double](1.1, 1.1)),
      (BigInt(2), BV[Double](9.9, 9.9)), (BigInt(2), BV[Double](10.1, 10.1)),
      (BigInt(3), BV[Double](99.9, 99.9)), (BigInt(3), BV[Double](100.1, 100.1)),
      (BigInt(3), BV[Double](109.9, 109.9)), (BigInt(3), BV[Double](110.1, 110.1))
    )
    val data = sc.parallelize(local)
    val stats = BisectingKMeans.summarizeClusters(data)
    val newClusters = BisectingKMeans.divideClusters(data, stats, 20)

    assert(newClusters.size === 4)
    assert(newClusters(4).center === BV[Double](1.0, 1.0))
    assert(newClusters(4).rows === 2)
    assert(newClusters(5).center === BV[Double](10.0, 10.0))
    assert(newClusters(5).rows === 2)
    assert(newClusters(6).center === BV[Double](100.0, 100.0))
    assert(newClusters(6).rows === 2)
    assert(newClusters(7).center === BV[Double](110.0, 110.0))
    assert(newClusters(7).rows === 2)
  }

}
