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

package org.apache.spark.ml.r

import org.apache.hadoop.fs.Path
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.attribute.{Attribute, AttributeGroup, NominalAttribute}
import org.apache.spark.ml.feature.RFormula
import org.apache.spark.ml.regression.{DecisionTreeRegressionModel, DecisionTreeRegressor}
import org.apache.spark.ml.util._
import org.apache.spark.sql.{DataFrame, Dataset}

private[r] class DecisionTreeRegressorWrapper private (
  val pipeline: PipelineModel,
  val features: Array[String],
  val labels: Array[String]) extends MLWritable {

  private val DTModel: DecisionTreeRegressionModel =
    pipeline.stages(1).asInstanceOf[DecisionTreeRegressionModel]

  lazy val maxDepth: Int = DTModel.getMaxDepth

  lazy val maxBins: Int = DTModel.getMaxBins

  def transform(dataset: Dataset[_]): DataFrame = {
    pipeline.transform(dataset)
      .drop(DTModel.getFeaturesCol)
      .drop(DTModel.getLabelCol)
  }

  override def write: MLWriter = new
      DecisionTreeRegressorWrapper.DecisionTreeRegressorWrapperWriter(this)
}

private[r] object DecisionTreeRegressorWrapper extends MLReadable[DecisionTreeRegressorWrapper] {

  def fit(
           data: DataFrame,
           formula: String,
           maxDepth: Int,
           maxBins: Int): DecisionTreeRegressorWrapper = {

    val rFormulaModel = new RFormula()
      .setFormula(formula)
      .setFeaturesCol("features")
      .setLabelCol("labels")
      .fit(data)

    // get feature names from output schema
    val schema = rFormulaModel.transform(data).schema
    val labelAttr = Attribute.fromStructField(schema(rFormulaModel.getLabelCol))
      .asInstanceOf[NominalAttribute]
    val labels = labelAttr.values.get
    val featureAttrs = AttributeGroup.fromStructField(schema(rFormulaModel.getFeaturesCol))
      .attributes.get
    val features = featureAttrs.map(_.name.get)

    val decisionTree = new DecisionTreeRegressor()
      .setMaxDepth(maxDepth)
      .setMaxBins(maxBins)

    val pipeline = new Pipeline()
      .setStages(Array(rFormulaModel, decisionTree))
      .fit(data)

    new DecisionTreeRegressorWrapper(pipeline, features, labels)
  }

  override def read: MLReader[DecisionTreeRegressorWrapper] = new DecisionTreeRegressorWrapperReader

  override def load(path: String): DecisionTreeRegressorWrapper = super.load(path)

  class DecisionTreeRegressorWrapperWriter(instance: DecisionTreeRegressorWrapper)
    extends MLWriter {

    override protected def saveImpl(path: String): Unit = {
      val rMetadataPath = new Path(path, "rMetadata").toString
      val pipelinePath = new Path(path, "pipeline").toString

      val rMetadata = ("class" -> instance.getClass.getName) ~
        ("features" -> instance.features.toSeq) ~
        ("labels" -> instance.labels.toSeq)
      val rMetadataJson: String = compact(render(rMetadata))

      sc.parallelize(Seq(rMetadataJson), 1).saveAsTextFile(rMetadataPath)
      instance.pipeline.save(pipelinePath)
    }
  }

  class DecisionTreeRegressorWrapperReader extends MLReader[DecisionTreeRegressorWrapper] {

    override def load(path: String): DecisionTreeRegressorWrapper = {
      implicit val format = DefaultFormats
      val rMetadataPath = new Path(path, "rMetadata").toString
      val pipelinePath = new Path(path, "pipeline").toString
      val pipeline = PipelineModel.load(pipelinePath)

      val rMetadataStr = sc.textFile(rMetadataPath, 1).first()
      val rMetadata = parse(rMetadataStr)
      val features = (rMetadata \ "features").extract[Array[String]]
      val labels = (rMetadata \ "labels").extract[Array[String]]
      new DecisionTreeRegressorWrapper(pipeline, features, labels)
    }
  }
}
