/*
 * Copyright 2018 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.sync.cli

import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Failure

object ParameterManagerSpec {
  /** Timeout when waiting for future results. */
  private val Timeout = 1.second

  /**
    * Waits for the given future to complete and returns the result.
    *
    * @param future the future
    * @tparam A the type of the future
    * @return the result of the future
    */
  private def futureResult[A](future: Future[A]): A =
    Await.result(future, Timeout)
}

/**
  * Test class for ''ParameterManager''.
  */
class ParameterManagerSpec extends FlatSpec with Matchers {

  import ParameterManagerSpec._

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''IllegalArgumentException'' that
    * has a specific error message.
    *
    * @param future the future to be checked
    * @param msg    text to be expected in the exception message
    */
  private def expectFailedFuture(future: Future[_], msg: String): Unit = {
    Await.ready(future, Timeout)
    future.value match {
      case Some(Failure(exception)) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(msg)
      case r =>
        fail("Unexpected result: " + r)
    }
  }

  "ParameterManager" should "parse an empty sequence of arguments" in {
    val argMap = futureResult(ParameterManager.parseParameters(Nil))

    argMap should have size 0
  }

  it should "correctly parse non-option parameters" in {
    val syncUris = List("uri1", "uri2")
    val expArgMap = Map(ParameterManager.SyncUriOption -> syncUris.reverse)

    val argMap = futureResult(ParameterManager.parseParameters(syncUris))
    argMap should be(expArgMap)
  }

  it should "correctly parse arguments with options" in {
    val args = Array("--opt1", "opt1Val1", "--opt2", "opt2Val1", "--opt1", "opt1Val2")
    val expArgMap = Map("--opt1" -> List("opt1Val2", "opt1Val1"),
      "--opt2" -> List("opt2Val1"))

    val argMap = futureResult(ParameterManager.parseParameters(args))
    argMap should be(expArgMap)
  }

  it should "fail with a correct message if an option is the last argument" in {
    val undefOption = "--undefinedOption"
    val args = List("--opt1", "optValue", undefOption)

    expectFailedFuture(ParameterManager.parseParameters(args), undefOption)
  }

  it should "convert options to lower case" in {
    val args = List("--TestOption", "TestValue", "--FOO", "BAR", "testUri")
    val expArgMap = Map("--testoption" -> List("TestValue"),
      "--foo" -> List("BAR"),
      ParameterManager.SyncUriOption -> List("testUri"))

    val argMap = futureResult(ParameterManager.parseParameters(args))
    argMap should be(expArgMap)
  }

  it should "extract URI parameters if they are present" in {
    val SourceUri = "source"
    val DestUri = "dest"
    val otherOption = "--foo" -> List("bar")
    val argsMap = Map(otherOption, ParameterManager.SyncUriOption -> List(DestUri, SourceUri))

    val (map, (src, dst)) = futureResult(ParameterManager.extractSyncUris(argsMap))
    src should be(SourceUri)
    dst should be(DestUri)
    map should contain only otherOption
  }

  it should "reject URI parameters if there are more than 2" in {
    val argsMap = Map(ParameterManager.SyncUriOption -> List("u1", "u2", "u3"))

    expectFailedFuture(ParameterManager.extractSyncUris(argsMap), "Too many sync URIs")
  }

  it should "reject URI parameters if no destination URI is provided" in {
    val argsMap = Map(ParameterManager.SyncUriOption -> List("u1"))

    expectFailedFuture(ParameterManager.extractSyncUris(argsMap),
      "Missing destination URI")
  }

  it should "reject URI parameters if no URIs are provided" in {
    var argsMap = Map(ParameterManager.SyncUriOption -> List.empty[String])

    expectFailedFuture(ParameterManager.extractSyncUris(argsMap),
      "Missing URIs for source and destination")
  }

  it should "reject URI parameters if no non-option parameters are provided" in {
    expectFailedFuture(ParameterManager.extractSyncUris(Map.empty),
      "Missing URIs for source and destination")
  }

  it should "validate a map with all parameters consumed" in {
    val result = futureResult(ParameterManager.checkParametersConsumed(Map.empty))

    result should have size 0
  }

  it should "fail the check for consumed parameters if there are remaining parameters" in {
    val argsMap = Map("foo" -> List("bar"))

    expectFailedFuture(ParameterManager.checkParametersConsumed(argsMap),
      "unexpected parameters: " + argsMap)
  }
}
