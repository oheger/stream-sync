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

import java.nio.file.Path

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.github.sync.FileTestHelper
import org.scalatest._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Failure

object ParameterManagerSpec {
  /** Timeout when waiting for future results. */
  private val Timeout = 5.second

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
class ParameterManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with BeforeAndAfter with Matchers with FileTestHelper {
  def this() = this(ActorSystem("ParameterManagerSpec"))

  import ParameterManagerSpec._

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
  }

  after {
    tearDownTestFile()
  }

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

  /**
    * Creates a temporary file that contains the given parameter strings.
    *
    * @param args the parameters to store in the file
    * @return the path to the newly created file
    */
  private def createParameterFile(args: String*): Path =
    createDataFile(parameterFileContent(args: _*))

  /**
    * Generates the content of a parameters file from the given parameter
    * strings.
    *
    * @param args the parameters to store in the file
    * @return the content of the parameter file as string
    */
  private def parameterFileContent(args: String*): String =
    args.mkString("\r\n")

  /**
    * Adds a parameter to read the given file to a parameter list.
    *
    * @param path    the path to the file to be read
    * @param argList the original parameter list
    * @return the parameter list with the file parameter added
    */
  private def appendFileParameter(path: Path, argList: List[String]): List[String] =
    ParameterManager.FileOption :: path.toString :: argList

  /**
    * Helper method for calling the parameter manager to parse a list of
    * parameters.
    *
    * @param args the list of parameters to be parsed
    * @return the parameters map as result of the parse operation
    */
  private def parseParameters(args: Seq[String]): Map[String, Iterable[String]] =
    futureResult(parseParametersFuture(args))

  /**
    * Helper method for calling the parameter manager's method to parse a list
    * of parameters and returning the future result.
    *
    * @param args the list of parameters to be parsed
    * @return the ''Future'' with the parse result
    */
  private def parseParametersFuture(args: Seq[String]): Future[Map[String, Iterable[String]]] = {
    implicit val mat: ActorMaterializer = ActorMaterializer()
    ParameterManager.parseParameters(args)
  }

  "ParameterManager" should "parse an empty sequence of arguments" in {
    val argMap = parseParameters(Nil)

    argMap should have size 0
  }

  it should "correctly parse non-option parameters" in {
    val syncUris = List("uri1", "uri2")
    val expArgMap = Map(ParameterManager.SyncUriOption -> syncUris.reverse)

    val argMap = parseParameters(syncUris)
    argMap should be(expArgMap)
  }

  it should "correctly parse arguments with options" in {
    val args = Array("--opt1", "opt1Val1", "--opt2", "opt2Val1", "--opt1", "opt1Val2")
    val expArgMap = Map("--opt1" -> List("opt1Val2", "opt1Val1"),
      "--opt2" -> List("opt2Val1"))

    val argMap = parseParameters(args)
    argMap should be(expArgMap)
  }

  it should "fail with a correct message if an option is the last argument" in {
    val undefOption = "--undefinedOption"
    val args = List("--opt1", "optValue", undefOption)

    expectFailedFuture(parseParametersFuture(args), undefOption)
  }

  it should "convert options to lower case" in {
    val args = List("--TestOption", "TestValue", "--FOO", "BAR", "testUri")
    val expArgMap = Map("--testoption" -> List("TestValue"),
      "--foo" -> List("BAR"),
      ParameterManager.SyncUriOption -> List("testUri"))

    val argMap = parseParameters(args)
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
    val argsMap = Map(ParameterManager.SyncUriOption -> List.empty[String])

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

  it should "add the content of parameter files to command line options" in {
    val OptionName1 = "--foo"
    val OptionName2 = "--test"
    val Opt1Val1 = "bar"
    val Opt1Val2 = "baz"
    val Opt2Val = "true"
    val uri1 = "testUri1"
    val uri2 = "testUri2"
    val args = appendFileParameter(createParameterFile(OptionName1, Opt1Val1, uri1),
      appendFileParameter(createParameterFile(OptionName2, Opt2Val),
        OptionName1 :: Opt1Val2 :: uri2 :: Nil))

    val argsMap = parseParameters(args)
    argsMap(OptionName1) should contain only(Opt1Val1, Opt1Val2)
    argsMap(OptionName2) should contain only Opt2Val
    argsMap.keys should not contain ParameterManager.FileOption

    val (_, uris) = futureResult(ParameterManager.extractSyncUris(argsMap))
    Set(uris._1, uris._2) should contain only(uri1, uri2)
  }

  it should "parse parameter files defined in another parameter file" in {
    val OptionName1 = "--top-level"
    val Option1Value = "onCommandLine"
    val OptionName2 = "--level1"
    val Option2Value = "inFirstFile"
    val OptionName3 = "--deep"
    val Option3Value = "inNestedFile"
    val nestedFile = createParameterFile(OptionName3, Option3Value)
    val args = appendFileParameter(
      createParameterFile(ParameterManager.FileOption, nestedFile.toString,
        OptionName2, Option2Value), OptionName1 :: Option1Value :: Nil)
    println("Parsing arguments " + args)
    val expArgs = Map(OptionName1 -> List(Option1Value),
      OptionName2 -> List(Option2Value),
      OptionName3 -> List(Option3Value))

    val argsMap = parseParameters(args)
    argsMap should be(expArgs)
  }

  it should "deal with cyclic references in parameter files" in {
    val file1 = createFileReference()
    val file3 = createParameterFile(ParameterManager.FileOption, file1.toString, "--op3", "v3")
    val file2 = createParameterFile(ParameterManager.FileOption, file3.toString, "--op2", "v2")
    writeFileContent(file1, parameterFileContent(ParameterManager.FileOption, file2.toString,
      "--op1", "v1", ParameterManager.FileOption, file2.toString))
    val args = appendFileParameter(file1, Nil)
    val expArgs = Map("--op1" -> List("v1"), "--op2" -> List("v2"), "--op3" -> List("v3"))

    val argsMap = parseParameters(args)
    argsMap should be(expArgs)
  }

  it should "ignore empty lines in parameter files" in {
    val args = appendFileParameter(createParameterFile("--foo", "bar", "", "--foo", "baz"),
      "--test" :: "true" :: Nil)

    val argsMap = parseParameters(args)
    argsMap.keys should contain only("--foo", "--test")
  }
}
