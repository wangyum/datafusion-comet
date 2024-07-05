/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.spark.sql

import java.io.File
import java.nio.file.{Files, Paths}

import scala.collection.JavaConverters._

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.catalyst.util.{fileToString, resourceToString, stringToFile}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.TestSparkSession

/**
 * Because we need to modify some methods of Spark `TPCDSQueryTestSuite` but they are private, we
 * copy Spark `TPCDSQueryTestSuite`.
 */
class CometTPCDSQueryTestSuite extends QueryTest with TPCDSBase with CometSQLQueryTestHelper {

  private val tpcdsDataPath = sys.env.get("SPARK_TPCDS_DATA")

  private val regenGoldenFiles: Boolean = System.getenv("SPARK_GENERATE_GOLDEN_FILES") == "1"

  // To make output results deterministic
  override protected def sparkConf: SparkConf = super.sparkConf
    .set(SQLConf.SHUFFLE_PARTITIONS.key, "1")

  protected override def createSparkSession: TestSparkSession = {
    new TestSparkSession(new SparkContext("local[1]", this.getClass.getSimpleName, sparkConf))
  }

  // We use SF=1 table data here, so we cannot use SF=100 stats
  protected override val injectStats: Boolean = false

  if (tpcdsDataPath.nonEmpty) {
    val nonExistentTables = tableNames.filterNot { tableName =>
      Files.exists(Paths.get(s"${tpcdsDataPath.get}/$tableName"))
    }
    if (nonExistentTables.nonEmpty) {
      fail(
        s"Non-existent TPCDS table paths found in ${tpcdsDataPath.get}: " +
          nonExistentTables.mkString(", "))
    }
  }

  protected val baseResourcePath: String = {
    // use the same way as `SQLQueryTestSuite` to get the resource path
    getWorkspaceFilePath(
      "sql",
      "core",
      "src",
      "test",
      "resources",
      "tpcds-query-results").toFile.getAbsolutePath
  }

  override def createTable(
      spark: SparkSession,
      tableName: String,
      format: String = "parquet",
      options: scala.Seq[String]): Unit = {
    spark.sql(s"""
         |CREATE TABLE `$tableName` (${tableColumns(tableName)})
         |USING $format
         |LOCATION '${tpcdsDataPath.get}/$tableName'
         |${options.mkString("\n")}
       """.stripMargin)
  }

  private def runQuery(query: String, goldenFile: File, conf: Map[String, String]): Unit = {
    // This is `sortMergeJoinConf != conf` in Spark, i.e., it sorts results for other joins
    // than sort merge join. But in some queries DataFusion sort returns correct results
    // in terms of required sorting columns, but the results are not same as Spark in terms of
    // order of irrelevant columns. So, we need to sort the results for all joins.
    val shouldSortResults = true
    withSQLConf(conf.toSeq: _*) {
      try {
        val (schema, output) = handleExceptions(getNormalizedResult(spark, query))
        val queryString = query.trim
        val outputString = output.mkString("\n").replaceAll("\\s+$", "")
        if (regenGoldenFiles) {
          val goldenOutput = {
            s"-- Automatically generated by ${getClass.getSimpleName}\n\n" +
              "-- !query schema\n" +
              schema + "\n" +
              "-- !query output\n" +
              outputString +
              "\n"
          }
          val parent = goldenFile.getParentFile
          if (!parent.exists()) {
            assert(parent.mkdirs(), "Could not create directory: " + parent)
          }
          stringToFile(goldenFile, goldenOutput)
        }

        // Read back the golden file.
        val (expectedSchema, expectedOutput) = {
          val goldenOutput = fileToString(goldenFile)
          val segments = goldenOutput.split("-- !query.*\n")

          // query has 3 segments, plus the header
          assert(
            segments.size == 3,
            s"Expected 3 blocks in result file but got ${segments.size}. " +
              "Try regenerate the result files.")

          (segments(1).trim, segments(2).replaceAll("\\s+$", ""))
        }

        val notMatchedSchemaOutput = if (schema == emptySchema) {
          // There might be exception. See `handleExceptions`.
          s"Schema did not match\n$queryString\nOutput/Exception: $outputString"
        } else {
          s"Schema did not match\n$queryString"
        }

        assertResult(expectedSchema, notMatchedSchemaOutput) {
          schema
        }
        if (shouldSortResults) {
          val expectSorted = expectedOutput
            .split("\n")
            .sorted
            .map(_.trim)
            .mkString("\n")
            .replaceAll("\\s+$", "")
          val outputSorted = output.sorted.map(_.trim).mkString("\n").replaceAll("\\s+$", "")
          assertResult(expectSorted, s"Result did not match\n$queryString") {
            outputSorted
          }
        } else {
          assertResult(expectedOutput, s"Result did not match\n$queryString") {
            outputString
          }
        }
      } catch {
        case e: Throwable =>
          val configs = conf.map { case (k, v) =>
            s"$k=$v"
          }
          throw new Exception(s"${e.getMessage}\nError using configs:\n${configs.mkString("\n")}")
      }
    }
  }

  val sortMergeJoinConf: Map[String, String] = Map(
    SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
    SQLConf.PREFER_SORTMERGEJOIN.key -> "true")

  val broadcastHashJoinConf: Map[String, String] = Map(
    SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "10485760")

  val shuffledHashJoinConf: Map[String, String] = Map(
    SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
    "spark.sql.join.forceApplyShuffledHashJoin" -> "true")

  val allJoinConfCombinations: Seq[Map[String, String]] =
    Seq(sortMergeJoinConf, broadcastHashJoinConf, shuffledHashJoinConf)

  val joinConfs: Seq[Map[String, String]] = if (regenGoldenFiles) {
    require(
      !sys.env.contains("SPARK_TPCDS_JOIN_CONF"),
      "'SPARK_TPCDS_JOIN_CONF' cannot be set together with 'SPARK_GENERATE_GOLDEN_FILES'")
    Seq(sortMergeJoinConf)
  } else {
    sys.env
      .get("SPARK_TPCDS_JOIN_CONF")
      .map { s =>
        val p = new java.util.Properties()
        p.load(new java.io.StringReader(s))
        Seq(p.asScala.toMap)
      }
      .getOrElse(allJoinConfCombinations)
  }

  assert(joinConfs.nonEmpty)
  joinConfs.foreach(conf =>
    require(
      allJoinConfCombinations.contains(conf),
      s"Join configurations [$conf] should be one of $allJoinConfCombinations"))

  if (tpcdsDataPath.nonEmpty) {
    tpcdsQueries.foreach { name =>
      val queryString = resourceToString(
        s"tpcds/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      test(name) {
        val goldenFile = new File(s"$baseResourcePath/v1_4", s"$name.sql.out")
        joinConfs.foreach { conf =>
          System.gc() // Workaround for GitHub Actions memory limitation, see also SPARK-37368
          runQuery(queryString, goldenFile, conf)
        }
      }
    }

    tpcdsQueriesV2_7_0.foreach { name =>
      val queryString = resourceToString(
        s"tpcds-v2.7.0/$name.sql",
        classLoader = Thread.currentThread().getContextClassLoader)
      test(s"$name-v2.7") {
        val goldenFile = new File(s"$baseResourcePath/v2_7", s"$name.sql.out")
        joinConfs.foreach { conf =>
          System.gc() // SPARK-37368
          runQuery(queryString, goldenFile, conf)
        }
      }
    }
  } else {
    ignore("skipped because env `SPARK_TPCDS_DATA` is not set") {}
  }
}
