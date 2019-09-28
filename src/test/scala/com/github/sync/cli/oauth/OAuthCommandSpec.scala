/*
 * Copyright 2018-2019 The Developers Team.
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

package com.github.sync.cli.oauth

import akka.stream.ActorMaterializer
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.ConsoleReader
import com.github.sync.cli.ParameterManager.{CliProcessor, Parameters}
import com.github.sync.crypt.Secret
import com.github.sync.webdav.oauth.{OAuthConfig, OAuthStorageConfig, OAuthStorageService, OAuthTokenData}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object OAuthCommandSpec {
  /** A test parameters object. */
  private val TestParameters = Parameters(Map("foo" -> List("v1", "v2"), "bar" -> List("v3")), Set.empty)

  /**
    * A parameters object that indicates that all command line arguments have
    * been consumed.
    */
  private val ConsumedParameters = TestParameters.copy(accessedParameters = TestParameters.parametersMap.keySet)

  /** A number simulating the configuration of the test command. */
  private val Config = 42

  /** Constant for the result message of the test command. */
  private val CommandMessage = "Executed"

  /** A successful future result for the test command. */
  private val FutureCommandMessage = Future.successful(CommandMessage)
}

/**
  * Test class for the base functionality provided by the ''OAuthCommand''
  * trait.
  */
class OAuthCommandSpec extends FlatSpec with Matchers with AsyncTestHelper with MockitoSugar {

  import OAuthCommandSpec._

  /** Mock for the implicit console reader. */
  private implicit val consoleReader: ConsoleReader = mock[ConsoleReader]

  /** Mock for an object to materialize streams. */
  private implicit val streamMat: ActorMaterializer = mock[ActorMaterializer]

  /**
    * Creates a generic test Cli processor that checks the context passed to it
    * and returns a defined result.
    *
    * @param value          the value to be returned by the processor
    * @param nextParameters the updated parameters
    * @param expReader      the expected console reader
    * @return the test processor
    */
  private def testProcessor(value: Try[Int] = Success(Config), nextParameters: Parameters = ConsumedParameters)
                           (implicit expReader: ConsoleReader): CliProcessor[Try[Int]] =
    CliProcessor(context => {
      context.parameters should be(TestParameters)
      context.reader should be(expReader)
      (value, context.update(nextParameters))
    })

  "OAuthCommand" should "fail execution if the CliProcessor fails" in {
    val exception = new IllegalArgumentException("CliProcessor exception")
    val processor = testProcessor(Failure(exception), nextParameters = TestParameters)
    val helper = new CommandTestHelper

    val ex = expectFailedFuture[Throwable](helper.runCommand(processor, FutureCommandMessage))
    ex should be(exception)
  }

  it should "run successfully" in {
    val processor = testProcessor()
    val helper = new CommandTestHelper

    val result = futureResult(helper.runCommand(processor, FutureCommandMessage))
    result should be(CommandMessage)
  }

  it should "fail execution if there are remaining command line arguments" in {
    val processor = testProcessor(nextParameters = TestParameters)
    val helper = new CommandTestHelper

    val ex = expectFailedFuture[IllegalArgumentException](helper.runCommand(processor, FutureCommandMessage))
    ex.getMessage should include(TestParameters.parametersMap.keySet.toString())
  }

  it should "fail execution if the command returns a failed future" in {
    val exception = new IllegalStateException("Command failed")
    val processor = testProcessor()
    val helper = new CommandTestHelper

    val ex = expectFailedFuture[Throwable](helper.runCommand(processor, Future.failed(exception)))
    ex should be(exception)
  }

  /**
    * A test helper class managing the dependencies required for the execution
    * of a test command and the command itself.
    */
  private class CommandTestHelper {
    /** Mock storage configuration. */
    private val mockStorageConfig = mock[OAuthStorageConfig]

    /** Mock storage service. */
    private val mockStorageService = mock[OAuthStorageService[OAuthStorageConfig, OAuthConfig,
      Secret, OAuthTokenData]]

    /**
      * Runs a test command with the given parameters and returns the result.
      *
      * @param processor the ''CliProcessor'' for the command
      * @param result    the ''Future'' result of the command
      * @return the result of the execution
      */
    def runCommand(processor: CliProcessor[Try[Int]], result: Future[String]): Future[String] = {
      val command = createCommand(processor, result)
      command.run(mockStorageConfig, mockStorageService, TestParameters)
    }

    /**
      * Creates a test command object with the given parameters.
      *
      * @param processor the ''CliProcessor''
      * @param result    the result to be returned
      * @return the test command
      */
    private def createCommand(processor: CliProcessor[Try[Int]], result: Future[String] = FutureCommandMessage):
    OAuthCommand[Int] = new OAuthCommand[Int] {
      override val cliProcessor: CliProcessor[Try[Int]] = processor

      /**
        * @inheritdoc This implementation
        */
      override protected def runCommand(storageConfig: OAuthStorageConfig,
                                        storageService: OAuthStorageService[OAuthStorageConfig, OAuthConfig,
                                          Secret, OAuthTokenData], config: Int)
                                       (implicit ec: ExecutionContext, mat: ActorMaterializer): Future[String] = {
        storageConfig should be(mockStorageConfig)
        storageService should be(mockStorageService)
        config should be(Config)
        mat should be(streamMat)
        result
      }
    }
  }

}
