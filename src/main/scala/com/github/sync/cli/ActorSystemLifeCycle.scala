/*
 * Copyright 2018-2020 The Developers Team.
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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import com.github.sync.cli.CliHelpGenerator.CliHelpContext
import com.github.sync.cli.ParameterManager.{CliProcessor, ParameterContext, ParameterExtractionException}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
  * A trait that supports managing an actor system and some related objects for
  * command line applications.
  *
  * This trait provides an actor system in implicit scope and defines a
  * ''run()'' function that is invoked with current command line arguments.
  * After the ''run()'' function returns, its result message is printed, and
  * the actor system is properly shutdown.
  *
  * The trait also has some support for the handling of command line parsing:
  * errors that occurred during command line processing are detected and cause
  * a help and usage message to be printed. To make this possible, a concrete
  * implementation has to provide the ''CliProcessor'' to extract its
  * configuration.
  *
  * @tparam C the type of the configuration of the application
  */
trait ActorSystemLifeCycle[C] {
  /**
    * A name for the application implementing this trait. This name is also
    * used for the managed actor system.
    */
  val name: String

  /** The field that stores the managed actor system. */
  private var system: ActorSystem = _

  /**
    * Returns the actor system managed by this trait.
    *
    * @return the managed actor system
    */
  implicit def actorSystem: ActorSystem = system

  /**
    * Returns an execution context for concurrent operations.
    *
    * @return an execution context
    */
  implicit def ec: ExecutionContext = system.dispatcher

  /**
    * The main ''run()'' method. This method executes the whole logic of the
    * application implementing this trait. It delegates to ''runApp()'' for the
    * actual execution and then releases all resources and terminates the actor
    * system. If the application logic returns a failed future, a message is
    * generated based on the exception and logged to the console instead of the
    * application's result message.
    *
    * @param args the array with command line arguments
    */
  def run(args: Array[String]): Unit = {
    system = createActorSystem()
    val runFuture = runApp(args)
      .recover {
        case e: ParameterExtractionException =>
          generateCliErrorMessage(e)
      }
    val futResult = futureWithShutdown(runFuture)
    val resultMsg = Await.result(futResult, 365.days)
    println(resultMsg)
  }

  /**
    * Creates the actor system managed by this object. This method is called
    * before invoking the ''runApp()'' method. This base implementation creates
    * a standard actor system with a name defined by the ''name'' property.
    *
    * @return the newly created actor system
    */
  protected def createActorSystem(): ActorSystem = ActorSystem(name)

  /**
    * Executes the logic of this application. A concrete implementation can
    * process the passed in command line arguments and return a result. The
    * result is simply printed to the console.
    *
    * @param args the array with command line arguments
    * @return a ''Future'' with the result message of this application
    */
  protected def runApp(args: Array[String]): Future[String]

  /**
    * Returns the main ''CliProcessor'' of this application. This is needed to
    * handle errors during command line processing properly. Based on this
    * processor, a help message is generated.
    *
    * @return the main ''CliProcessor'' of this application
    */
  protected def cliProcessor: CliProcessor[Try[C]]

  /**
    * Generates the caption for the usage message of this application, which is
    * part of the help text. Here the application name and an overview of the
    * input parameters supported should be contained.
    *
    * @param helpContext the current help context
    * @return the caption for the usage message
    */
  protected def usageCaption(helpContext: CliHelpContext): String

  /**
    * Returns a filter function to be applied when generating the help for the
    * command line options supported.
    *
    * @param context the current ''ParameterContext''
    * @return the filter to be used when displaying the help for options
    */
  protected def optionsGroupFilter(context: ParameterContext): CliHelpGenerator.OptionFilter =
    CliHelpGenerator.AllFilterFunc

  /**
    * Returns a ''Future'' that is completed after all resources used by this
    * application have been released. The future contains either the result
    * message of the application or - in cause the application logic returned a
    * failure - a message derived from the corresponding exception.
    *
    * @param resultFuture the ''Future'' with the result of the application
    * @return a ''Future'' with the application result, including shutdown
    *         handling
    */
  private def futureWithShutdown(resultFuture: Future[String]): Future[String] = {
    val fallback = resultFuture recover {
      case ex => errorMessage(ex)
    } // this is guaranteed to succeed

    for {msg <- fallback
         _ <- Http().shutdownAllConnectionPools()
         _ <- system.terminate()
         } yield msg
  }

  /**
    * Returns an error message from the given exception.
    *
    * @param ex the exception
    * @return the error message derived from this exception
    */
  private def errorMessage(ex: Throwable): String =
    s"[${ex.getClass.getName}]: ${ex.getMessage}"

  /**
    * Generates a string with an error message if invalid parameters have been
    * provided. The text contains a detailed error message and usage
    * instructions.
    *
    * @param exception the original CLI exception
    * @return the error and usage text
    */
  private def generateCliErrorMessage(exception: ParameterExtractionException): String =
    "Invalid command line options detected:" + CliHelpGenerator.CR + CliHelpGenerator.CR +
      generateErrorMessageFromFailures(exception) + CliHelpGenerator.CR + CliHelpGenerator.CR +
      generateCliHelp(exception.parameterContext)

  /**
    * Generates a formatted string for all the failures that occurred during
    * command line processing.
    *
    * @param exception the exception with all extraction failures
    * @return a string with formatted error messages
    */
  private def generateErrorMessageFromFailures(exception: ParameterExtractionException): String = {
    import CliHelpGenerator._
    val helpContext = ParameterManager.addFailuresToHelpContext(exception.parameterContext.helpContext,
      exception.failures)
    val errorGenerator = wrapColumnGenerator(attributeColumnGenerator(AttrErrorMessage), 70)
    val optionsFilter = attributeFilterFunc(AttrErrorMessage)
    generateOptionsHelp(helpContext, filterFunc = optionsFilter)(optionNameColumnGenerator(optionPrefix = ""),
      errorGenerator)
  }

  /**
    * Generates a help text with instructions how this application is used.
    *
    * @param parameterContext the current ''ParameterContext''
    * @return the help text
    */
  private def generateCliHelp(parameterContext: ParameterContext): String = {
    val (_, context) = ParameterManager.runProcessor(cliProcessor, parameterContext.parameters)(DummyConsoleReader)
    val helpContext = context.helpContext

    import CliHelpGenerator._
    val helpGenerator = composeColumnGenerator(
      wrapColumnGenerator(attributeColumnGenerator(AttrHelpText), 70),
      prefixColumnGenerator(attributeColumnGenerator(AttrFallbackValue), prefixText = Some("Default value: "))
    )

    val buf = new java.lang.StringBuilder
    buf.append(usageCaption(helpContext))
      .append(CR)
      .append(CR)
      .append(generateOptionsHelp(helpContext, sortFunc = inputParamSortFunc(helpContext),
        filterFunc = InputParamsFilterFunc)(optionNameColumnGenerator(optionPrefix = ""), helpGenerator))
      .append(CR)
      .append(CR)
      .append("Supported options:")
      .append(CR)
      .append(generateOptionsHelp(helpContext,
        filterFunc = optionsGroupFilter(context))(optionNameColumnGenerator(optionPrefix = "--"), helpGenerator))
      .toString
  }
}
