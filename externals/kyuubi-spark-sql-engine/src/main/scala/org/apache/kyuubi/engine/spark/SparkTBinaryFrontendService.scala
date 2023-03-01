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

package org.apache.kyuubi.engine.spark

import java.text.DecimalFormat

import scala.collection.JavaConverters._

import org.apache.hadoop.io.Text
import org.apache.hadoop.security.{Credentials, UserGroupInformation}
import org.apache.hadoop.security.token.{Token, TokenIdentifier}
import org.apache.hive.service.rpc.thrift.{TOpenSessionReq, TOpenSessionResp, TRenewDelegationTokenReq, TRenewDelegationTokenResp}
import org.apache.spark.SparkContext
import org.apache.spark.kyuubi.SparkContextHelper

import org.apache.kyuubi.{KyuubiSQLException, Logging}
import org.apache.kyuubi.config.KyuubiReservedKeys._
import org.apache.kyuubi.ha.client.{EngineServiceDiscovery, ServiceDiscovery}
import org.apache.kyuubi.service.{Serverable, Service, TBinaryFrontendService}
import org.apache.kyuubi.service.TFrontendService._
import org.apache.kyuubi.util.KyuubiHadoopUtils

class SparkTBinaryFrontendService(
    override val serverable: Serverable)
  extends TBinaryFrontendService("SparkTBinaryFrontend") {
  import SparkTBinaryFrontendService._

  private lazy val sc = be.asInstanceOf[SparkSQLBackendService].sparkSession.sparkContext

  override def RenewDelegationToken(req: TRenewDelegationTokenReq): TRenewDelegationTokenResp = {
    debug(req.toString)

    // We hacked `TCLIService.Iface.RenewDelegationToken` to transfer Credentials from Kyuubi
    // Server to Spark SQL engine
    val resp = new TRenewDelegationTokenResp()
    try {
      renewDelegationToken(sc, req.getDelegationToken)
      resp.setStatus(OK_STATUS)
    } catch {
      case e: Exception =>
        warn("Error renew delegation tokens: ", e)
        resp.setStatus(KyuubiSQLException.toTStatus(e))
    }
    resp
  }

  override def OpenSession(req: TOpenSessionReq): TOpenSessionResp = {
    debug(req.toString)
    info("Client protocol version: " + req.getClient_protocol)
    val resp = new TOpenSessionResp
    try {
      val respConfiguration = Map(
        KYUUBI_ENGINE_ID -> KyuubiSparkUtil.engineId,
        KYUUBI_ENGINE_NAME -> KyuubiSparkUtil.engineName,
        KYUUBI_ENGINE_URL -> KyuubiSparkUtil.engineUrl).asJava

      if (req.getConfiguration != null) {
        val credentials = req.getConfiguration.remove(KYUUBI_ENGINE_CREDENTIALS_KEY)
        Option(credentials).filter(_.nonEmpty).foreach(renewDelegationToken(sc, _))
      }

      val sessionHandle = getSessionHandle(req, resp)
      resp.setSessionHandle(sessionHandle.toTSessionHandle)
      resp.setConfiguration(respConfiguration)
      resp.setStatus(OK_STATUS)
      Option(CURRENT_SERVER_CONTEXT.get()).foreach(_.setSessionHandle(sessionHandle))
    } catch {
      case e: Exception =>
        error("Error opening session: ", e)
        resp.setStatus(KyuubiSQLException.toTStatus(e, verbose = true))
    }
    resp
  }

  override lazy val discoveryService: Option[Service] = {
    if (ServiceDiscovery.supportServiceDiscovery(conf)) {
      Some(new EngineServiceDiscovery(this))
    } else {
      None
    }
  }

  def parseMemory2GB(size: String): String = {
    val memory = size.dropRight(1).toDouble
    val unit = size.last.toLower
    val dec = new DecimalFormat("0.00")
    val formattedMemory = unit match {
      case 'k' => dec.format(memory / 1024.0 / 1024.0)
      case 'm' => dec.format(memory / 1024.0)
      case 'g' => dec.format(memory)
      case 't' => dec.format(memory * 1024.0)
    }
    formattedMemory
  }

  override def attributes: Map[String, String] = {

    val settings = sc.getConf.getAll.toMap
    val executorInstances = sc.getExecutorMemoryStatus.size - 1
    val executorMemory = executorInstances.toLong *
      settings.get(SPARK_ENGINE_EXECUTOR_MEMORY).getOrElse("1g")
    val executorCores = executorInstances.toInt *
      settings.get(SPARK_ENGINE_EXECUTOR_CORES).getOrElse("1").asInstanceOf[Int]
    val driverMemory = settings.get(SPARK_ENGINE_DRIVER_MEMORY).getOrElse("1g")
    val driverCores = settings.get(SPARK_ENGINE_DRIVER_CORES).getOrElse("1").asInstanceOf[Int]
    val memory = parseMemory2GB(executorMemory) + parseMemory2GB(driverMemory)
    val cores = executorCores + driverCores
    val address = java.net.InetAddress.getLocalHost.getHostAddress
    Map(
      KYUUBI_ENGINE_ID -> KyuubiSparkUtil.engineId,
      KYUUBI_ENGINE_URL -> sc.uiWebUrl.get.replace("//", ""),
      KYUUBI_ENGINE_SUBMIT_TIME -> sc.startTime.toString,
      KYUUBI_ENGINE_MEMORY -> s"$memory GB",
      KYUUBI_ENGINE_CPU -> cores.toString,
      KYUUBI_ENGINE_DRIVER_IP -> address,
      KYUUBI_ENGINE_UI_PORT -> sc.getConf.get("spark.ui.port"),
      KYUUBI_ENGINE_USERNAME -> sc.sparkUser,
      KYUUBI_SERVER_IP_KEY -> sc.getConf.get(KYUUBI_SERVER_IP_KEY))
  }
}

object SparkTBinaryFrontendService extends Logging {

  val HIVE_DELEGATION_TOKEN = new Text("HIVE_DELEGATION_TOKEN")
  final val SPARK_ENGINE_DRIVER_MEMORY = "spark.driver.memory"
  final val SPARK_ENGINE_EXECUTOR_MEMORY = "spark.executor.memory"

  final val SPARK_ENGINE_EXECUTOR_CORES = "spark.executor.cores"
  final val SPARK_ENGINE_DRIVER_CORES = "spark.driver.cores"

  final val SPARK_ENGINE_EXECUTOR_INSTANCE = "spark.executor.instances"
  final val SPARK_ENGINE_EXECUTOR_MAX_INSTANCE = "spark.dynamicAllocation.maxExecutors"

  private[spark] def renewDelegationToken(sc: SparkContext, delegationToken: String): Unit = {
    val newCreds = KyuubiHadoopUtils.decodeCredentials(delegationToken)
    val (hiveTokens, otherTokens) =
      KyuubiHadoopUtils.getTokenMap(newCreds).partition(_._2.getKind == HIVE_DELEGATION_TOKEN)

    val updateCreds = new Credentials()
    val oldCreds = UserGroupInformation.getCurrentUser.getCredentials
    addHiveToken(sc, hiveTokens, oldCreds, updateCreds)
    addOtherTokens(otherTokens, oldCreds, updateCreds)
    if (updateCreds.numberOfTokens() > 0) {
      info("Update delegation tokens. " +
        s"The number of tokens sent by the server is ${newCreds.numberOfTokens()}. " +
        s"The actual number of updated tokens is ${updateCreds.numberOfTokens()}.")
      SparkContextHelper.updateDelegationTokens(sc, updateCreds)
    }
  }

  private def addHiveToken(
      sc: SparkContext,
      newTokens: Map[Text, Token[_ <: TokenIdentifier]],
      oldCreds: Credentials,
      updateCreds: Credentials): Unit = {
    val metastoreUris = sc.hadoopConfiguration.getTrimmed("hive.metastore.uris", "")

    // `HiveMetaStoreClient` selects the first token whose service is "" and kind is
    // "HIVE_DELEGATION_TOKEN" to authenticate.
    val oldAliasAndToken = KyuubiHadoopUtils.getTokenMap(oldCreds)
      .find { case (_, token) =>
        token.getKind == HIVE_DELEGATION_TOKEN && token.getService == new Text()
      }

    if (metastoreUris.nonEmpty && oldAliasAndToken.isDefined) {
      // Each entry of `newTokens` is a <uris, token> pair for a metastore cluster.
      // If entry's uris and engine's metastore uris have at least 1 same uri, we presume they
      // represent the same metastore cluster.
      val uriSet = metastoreUris.split(",").filter(_.nonEmpty).toSet
      val newToken = newTokens
        .find { case (uris, token) =>
          val matched = uris.toString.split(",").exists(uriSet.contains) &&
            token.getService == new Text()
          if (!matched) {
            debug(s"Filter out Hive token $token")
          }
          matched
        }
        .map(_._2)
      newToken.foreach { token =>
        if (compareIssueDate(token, oldAliasAndToken.get._2) > 0) {
          updateCreds.addToken(oldAliasAndToken.get._1, token)
        } else {
          warn(s"Ignore Hive token with earlier issue date: $token")
        }
      }
      if (newToken.isEmpty) {
        warn(s"No matching Hive token found for engine metastore uris $metastoreUris")
      }
    } else if (metastoreUris.isEmpty) {
      info(s"Ignore Hive token as hive.metastore.uris are empty")
    } else {
      // Either because Hive metastore is not secured or because engine is launched with keytab
      info(s"Ignore Hive token as engine does not need it")
    }
  }

  private def addOtherTokens(
      tokens: Map[Text, Token[_ <: TokenIdentifier]],
      oldCreds: Credentials,
      updateCreds: Credentials): Unit = {
    tokens.foreach { case (alias, newToken) =>
      val oldToken = oldCreds.getToken(alias)
      if (oldToken != null) {
        if (compareIssueDate(newToken, oldToken) > 0) {
          updateCreds.addToken(alias, newToken)
        } else {
          warn(s"Ignore token with earlier issue date: $newToken")
        }
      } else {
        info(s"Ignore unknown token $newToken")
      }
    }
  }

  private def compareIssueDate(
      newToken: Token[_ <: TokenIdentifier],
      oldToken: Token[_ <: TokenIdentifier]): Int = {
    val newDate = KyuubiHadoopUtils.getTokenIssueDate(newToken)
    val oldDate = KyuubiHadoopUtils.getTokenIssueDate(oldToken)
    if (newDate.isDefined && oldDate.isDefined && newDate.get <= oldDate.get) {
      -1
    } else {
      1
    }
  }
}
