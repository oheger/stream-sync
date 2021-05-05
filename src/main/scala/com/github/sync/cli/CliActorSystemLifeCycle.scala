/*
 * Copyright 2018-2021 The Developers Team.
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

import java.util.Locale

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import com.github.scli.HelpGenerator.ParameterFilter
import com.github.scli.ParameterExtractor.{CliExtractor, switchValue}
import com.github.scli.ParameterManager.{ExtractionSpec, ProcessingContext}
import com.github.scli.ParameterModel.{AttrErrCause, AttrFallbackValue, AttrHelpText, ParameterKey}
import com.github.scli.{HelpGenerator, ParameterExtractor, ParameterManager}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

object CliActorSystemLifeCycle {
  /**
    * The name of the command line option that references a file with further
    * command line arguments.
    */
  final val FileOption = "file"

  /** Help text for the file option. */
  final val HelpFileOption =
    """Allows specifying paths to text files that contain additional command line options. \
      |The files must contain one argument per line, empty lines are ignored. The option \
      |can be repeated to include multiple parameter files. This is useful for instance if \
      |there are standard arguments (e.g. to define servers) that need to be set for
      |multiple sync processes.""".stripMargin

  /**
    * An extractor for the option that references parameter files to be
    * included during CLI processing. Note that the result of this extractor is
    * not actually evaluated; it is declared, to have the option listed in the
    * help text.
    */
  final val FileExtractor = ParameterExtractor.optionValue(FileOption, Some(HelpFileOption))

  /**
    * Invokes the ''ParameterManager'' to parse and process the command line
    * options with the correct settings.
    *
    * @param args      the sequence with command line arguments
    * @param extractor the ''CliExtractor''
    * @param helpHelp  the help text for the help option
    * @tparam A the result type of the ''CliExtractor''
    * @return an ''Either'' with the result of command line processing
    */
  def processCommandLine[A](args: Seq[String], extractor: CliExtractor[Try[A]], helpHelp: String):
  Either[ProcessingContext, A] = {
    val keyExtractor = ParameterManager.defaultKeyExtractor() andThen { opt =>
      opt.map(key => if (key.shortAlias) key else key.copy(key = key.key.toLowerCase(Locale.ROOT)))
    }
    val helpExtractor = switchValue(SyncParameterManager.HelpOption, optHelp = Some(helpHelp))
      .alias(SyncParameterManager.HelpAlias)
    val spec = ExtractionSpec(extractor, keyExtractor = keyExtractor, optHelpExtractor = Some(helpExtractor),
      fileOptions = List(ParameterKey(FileOption, shortAlias = false)))
    val classifierFunc = ParameterManager.classifierFunc(spec)
    val parseFunc = ParameterManager.parsingFuncForClassifier(spec)(classifierFunc)

    val result = for {
      processedArgs <- ParameterManager.processParameterFiles(args, spec)(classifierFunc)
      result <- ParameterManager.processCommandLineSpec(processedArgs, spec, parser = parseFunc)
    } yield result
    ParameterManager.evaluate(result)
  }
}

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
  * The command line is parsed and processed using the provided
  * ''CliExtractor''; the resulting configuration object is passed to the
  * abstract ''runApp()'' function. Errors that occurred during command line
  * processing are detected and cause a help and usage message to be printed.
  * To make this possible, a concrete implementation has to provide the
  * ''CliExtractor'' to extract its configuration.
  *
  * @tparam C the type of the configuration of the application
  */
trait CliActorSystemLifeCycle[C] {

  import CliActorSystemLifeCycle._

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
    val futResult = execute(args)
    val resultMsg = Await.result(futResult, 365.days)
    println(resultMsg)
  }

  /**
    * Executes this CLI application and returns a ''Future'' with the output
    * generated by it. This function is called by the main ''run()'' method;
    * the output is then printed to the command line.
    *
    * @param args the array with command line arguments
    * @return a ''Future'' with the output generated by this application
    */
  def execute(args: Array[String]): Future[String] = {
    processCommandLine(args.toSeq, cliExtractor, helpOptionHelp) match {
      case Right(config) =>
        system = createActorSystem()
        val runFuture = runApp(config)
        futureWithShutdown(runFuture)
      case Left(context) =>
        Future.successful(generateCliHelp(context))
    }
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
    * use the passed in configuration object (that was obtained using the
    * ''CliExtractor'' provided) and return a result. The result is simply
    * printed to the console.
    *
    * @param config the configuration extracted from the command line options
    * @return a ''Future'' with the result message of this application
    */
  protected def runApp(config: C): Future[String]

  /**
    * Returns the main ''CliExtractor'' of this application. This is needed to
    * handle errors during command line processing properly. Based on this
    * extractor, a help message is generated.
    *
    * @return the main ''CliExtractor'' of this application
    */
  protected def cliExtractor: CliExtractor[Try[C]]

  /**
    * Generates the caption for the usage message of this application, which is
    * part of the help text. Here the application name and an overview of the
    * input parameters supported should be contained.
    *
    * @param processingContext the current ''ProcessingContext''
    * @return the caption for the usage message
    */
  protected def usageCaption(processingContext: ProcessingContext): String

  /**
    * Returns the help text for the switch to request a usage message. So,
    * this is help about the help option.
    *
    * @return the help text for the help command line switch
    */
  protected def helpOptionHelp: String

  /**
    * Returns a filter function to be applied when generating the help for the
    * command line options supported.
    *
    * @param context the current ''ProcessingContext''
    * @return the filter to be used when displaying the help for options
    */
  protected def optionsGroupFilter(context: ProcessingContext): ParameterFilter =
    HelpGenerator.AllFilterFunc

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
    * Returns a ''StringBuilder'' with an error report if errors during command
    * line processing have been detected. Otherwise, the resulting builder is
    * empty and can now be populated with usage instructions.
    *
    * @param context the current ''ProcessingContext''
    * @return a ''StringBuilder'' optionally populated with an error report
    */
  private def generateCliErrorMessage(context: ProcessingContext): StringBuilder =
    context.optFailureContext map { failureContext =>
      import HelpGenerator._
      val colKey = parameterAliasColumnGenerator()
      val colErr = wrapColumnGenerator(attributeColumnGenerator(AttrErrCause), 70)
      val buf = new StringBuilder(8192)
      buf.append("Invalid command line options detected:")
        .append(CR)
        .append(CR)
        .append(generateParametersHelp(failureContext)(colKey, colErr))
        .append(CR)
        .append(CR)
      buf
    } getOrElse new StringBuilder(4096)

  /**
    * Generates a help text with instructions how this application is used. If
    * there were errors during CLI processing, these are displayed as well.
    *
    * @param processingContext the current ''ProcessingContext''
    * @return the help text
    */
  private def generateCliHelp(processingContext: ProcessingContext): String = {
    import HelpGenerator._

    val modelContext = processingContext.parameterContext.modelContext
    val keyGenerator = parameterKeyWithAliasesColumnGenerator(maxLength = 24)
    val helpGenerator = composeColumnGenerator(
      wrapColumnGenerator(attributeColumnGenerator(AttrHelpText), 70),
      prefixTextColumnGenerator(attributeColumnGenerator(AttrFallbackValue), prefixText = "Default value: ")
    )
    val tableParams = generateHelpTable(modelContext, filterFunc = InputParamsFilterFunc,
      sortFunc = inputParamSortFunc(modelContext))(keyGenerator, helpGenerator)
    val tableOptions = generateHelpTable(modelContext,
      filterFunc = optionsGroupFilter(processingContext))(keyGenerator, helpGenerator)
    val helpTexts = renderHelpTables(List(tableParams, tableOptions))

    val buf = generateCliErrorMessage(processingContext)
    buf.append(usageCaption(processingContext))
      .append(CR)
      .append(CR)
      .append(helpTexts.head)
      .append(CR)
      .append(CR)
      .append("Supported options:")
      .append(CR)
      .append(helpTexts(1))
      .toString
  }
}
