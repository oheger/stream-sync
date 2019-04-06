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

package com.github.sync.cli

import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.temporal.TemporalQuery
import java.time.{LocalDateTime, ZoneId}
import java.util.Locale
import java.util.regex.Pattern

import com.github.sync.SyncTypes._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
  * A service that supports filtering of sync operations.
  *
  * When calling the [[Sync]] CLI a bunch of parameters for filtering sync
  * operations can be specified. This service offers functionality for parsing
  * such parameters and evaluating filters during a sync process.
  */
object FilterManager {
  /** Definition of a predicate function to filter sync operations. */
  type SyncOperationFilter = SyncOperation => Boolean

  /**
    * Type definition for the filters to be applied for single sync actions.
    */
  type ActionFilters = Map[SyncAction, List[SyncOperationFilter]]

  /** Command line option to define a filter for create actions. */
  val ArgCreateFilter = "--filter-create"

  /** Command line option to define a filter for override actions. */
  val ArgOverrideFilter = "--filter-override"

  /** Command line option to define a filter for remove actions. */
  val ArgRemoveFilter = "--filter-remove"

  /** Command line option to define a filter for all actions. */
  val ArgCommonFilter = "--filter"

  /** Command line option for the filter to disable specific sync actions. */
  val ArgActionFilter = "--actions"

  /**
    * Data class defining the filtering during a sync process.
    *
    * Separate filters can be defined for the different action types. For each
    * action type multiple filter functions can be set; a [[SyncOperation]] is
    * only accepted if it is accepted by all filter functions for that action
    * type.
    *
    * @param filters a map with data about the filters per action type
    */
  case class SyncFilterData(filters: ActionFilters)

  /** Mapping from filter parameters to action types. */
  private val ActionFilterParameters = List((ArgCreateFilter, ActionCreate),
    (ArgOverrideFilter, ActionOverride), (ArgRemoveFilter, ActionRemove))

  /** A set with the names of all parameters supported by this service. */
  private val AllFilterParameters =
    Set(ArgCreateFilter, ArgOverrideFilter, ArgRemoveFilter, ArgCommonFilter, ArgActionFilter)

  /**
    * A map assigning names of action types to the corresponding type objects.
    * This is used to deactivate specific actions based on filter options.
    */
  private val ActionTypeNameMapping: Map[String, SyncAction] = Map("actioncreate" -> ActionCreate,
    "actionoverride" -> ActionOverride, "actionremove" -> ActionRemove)

  /** A list with all defined action type names. */
  private lazy val ActionTypeList = ActionTypeNameMapping.keys.toList

  /** Expression string to parse a numeric filter value. */
  private val DataTypeNumber =
    """\d+"""

  /** Expression string to parse a string filter value. */
  private val DataTypeString =
    """.+"""

  /** Expression string to parse a date filter value. */
  private val DataTypeDate =
    """(\d{4}-\d{2}-\d{2})T?(\d{2}:\d{2}:\d{2})?"""

  /** RegEx to parse a min level filter. */
  private val RegMinLevel = filterExpressionRegEx("min-level", DataTypeNumber)

  /** RegEx to parse a max level filter. */
  private val RegMaxLevel = filterExpressionRegEx("max-level", DataTypeNumber)

  /** RegEx to parse an exclusion filter. */
  private val RegExclude = filterExpressionRegEx("exclude", DataTypeString)

  /** RegEx to parse an inclusion filter. */
  private val RegInclude = filterExpressionRegEx("include", DataTypeString)

  /** RegEx to parse a date-after filter. */
  private val RegDateAfter = filterExpressionRegEx("date-after", DataTypeDate)

  /** RegEx to parse a date-before filter. */
  private val RegDateBefore = filterExpressionRegEx("date-before", DataTypeDate)

  /** A special filter that rejects all sync operations. */
  private val RejectFilter: SyncOperationFilter = _ => false

  /** Constant for the separator used by the action type filter. */
  private val ActionTypeSeparator = ","

  /**
    * Extracts filtering information from the map with command line arguments.
    *
    * The passed in map contains all command line arguments provided to the
    * CLI keyed by command line options (already converted to uppercase). As
    * options can be repeated, the values of the map are lists. The function
    * tries to filter out all options that are related to filtering and removes
    * them from the arguments map. The single options are processed and
    * converted into ''SyncOperationFilter'' filters.
    *
    * The resulting future contains a map with filtering options removed and
    * the produced ''SyncFilterData''. The updated map with command line
    * arguments can be used for further checks of options. If one of the
    * filtering parameters was invalid and could not be parsed, the resulting
    * future is failed.
    *
    * @param arguments a map with information about command line arguments
    * @param ec        the execution context
    * @return a future with an updated map with arguments and the extracted
    *         ''SyncFilterData''
    */
  def parseFilters(arguments: Map[String, Iterable[String]])(implicit ec: ExecutionContext):
  Future[(Map[String, Iterable[String]], SyncFilterData)] = {
    val futCleanedMap = removeFilterParameters(arguments)
    val futCommonFilters =
      parseExpressionsOfFilterOption(arguments.getOrElse(ArgCommonFilter, Nil), Nil)
    for {cleanedMap <- futCleanedMap
         commonFilters <- futCommonFilters
         filterData1 <- parseFiltersPerActionType(arguments, commonFilters)
         filterData <- parseActionFilter(arguments, filterData1)
    } yield (cleanedMap, SyncFilterData(filterData))
  }

  /**
    * Parses the filter definitions for a single action type. This function
    * evaluates the supported filter conditions and creates corresponding
    * filter functions. The results are combined with the common filters and
    * added to a map keyed by the action type. The parameter with common
    * filters is useful if the user has provided filters that should be applied
    * to all action types.
    *
    * @param expressions   the filter expressions for the current action type
    *                      from the command line
    * @param action        the current action type
    * @param commonFilters a sequence with common filter definitions
    * @param ec            the execution context
    * @return a future with parsed filter definitions
    */
  def parseFilterOption(expressions: Iterable[String], action: SyncAction,
                        commonFilters: List[SyncOperationFilter] = Nil)
                       (implicit ec: ExecutionContext): Future[ActionFilters] =
    parseExpressionsOfFilterOption(expressions, commonFilters)(ec) map (lst => Map(action -> lst))

  /**
    * Applies the given filter data to the specified ''SyncOperation'' and
    * returns a flag whether the operation is accepted by the filter. This
    * method can be used during a sync process to filter out operations based
    * on the current filter configuration.
    *
    * @param op         the ''SyncOperation'' in question
    * @param filterData data about the filter configuration
    * @return a flag whether this operation is accepted by the filter data
    */
  def applyFilter(op: SyncOperation, filterData: SyncFilterData): Boolean = {
    @tailrec def doApplyFilter(filters: List[SyncOperationFilter]): Boolean =
      filters match {
        case h :: t => h(op) && doApplyFilter(t)
        case _ => true
      }

    doApplyFilter(filterData.filters.getOrElse(op.action, Nil))
  }

  /**
    * Tries to transform the given expression string into a filter function. If
    * this fails, an ''IllegalArgumentException'' is thrown.
    *
    * @param expr the expression to be parsed
    * @return the filter function
    * @throws IllegalArgumentException if the expression cannot be parsed
    */
  private def parseExpression(expr: String): SyncOperationFilter = expr match {
    case RegMinLevel(level) =>
      op => op.level >= level.toInt
    case RegMaxLevel(level) =>
      op => op.level <= level.toInt
    case RegExclude(pattern) =>
      exclusionFilter(pattern)
    case RegInclude(pattern) =>
      inclusionFilter(pattern)
    case RegDateAfter(_, date, time) =>
      dateFilter(expr, date, time)(_ <= 0)
    case RegDateBefore(_, date, time) =>
      dateFilter(expr, date, time)(_ > 0)
    case _ => throw new IllegalArgumentException(expr)
  }

  /**
    * Returns a ''SyncOperationFilter'' that filters for a file's last-modified
    * time. The given date and (optional) time components are combined and
    * parsed as an ''Instant''. Then a filter is constructed that compares a
    * file's time against this reference ''Instant'' using the provided
    * comparison function.
    *
    * @param date the date component
    * @param time the time component (may be '''null''')
    * @param comp the comparison function
    * @return a filter for a file date
    * @throws IllegalArgumentException if the date cannot be parsed
    */
  private def dateFilter(expr: String, date: String, time: String)(comp: Int => Boolean):
  SyncOperationFilter = {
    val dtStr = date + "T" + (if (time == null) "00:00:00" else time)
    val query: TemporalQuery[LocalDateTime] = LocalDateTime.from _
    try {
      val localDate = DateTimeFormatter.ISO_DATE_TIME.parse(dtStr, query)
      val instant = localDate.atZone(ZoneId.systemDefault()).toInstant
      op =>
        op.element match {
          case FsFile(_, _, modTime, _, _) =>
            comp(instant.compareTo(modTime))
          case _ => true
        }
    } catch {
      case e: DateTimeParseException =>
        throw new IllegalArgumentException("Could not parse date in filter expression: " + expr, e)
    }
  }

  /**
    * Generates a filter that excludes all elements that match a given regular
    * glob expression.
    *
    * @param pattern the glob expression
    * @return a filter to exclude matching elements
    */
  private def exclusionFilter(pattern: String): SyncOperationFilter = {
    val regex = generateGlobRexEx(pattern)
    op =>
      op.element.relativeUri match {
        case regex(_*) => false
        case _ => true
      }
  }

  /**
    * Generates a filter that includes all elements that match a given regular
    * glob expression. The idea here is that this filter is just the negation
    * of an exclusion filter.
    *
    * @param pattern the glob expression
    * @return a filter that includes matching elements
    */
  private def inclusionFilter(pattern: String): SyncOperationFilter = {
    val exFilter = exclusionFilter(pattern)
    op => !exFilter(op)
  }

  /**
    * Generates a RegEx from a glob expression. The characters '*' and '?' are
    * transformed to equivalent matchers of regular expressions.
    *
    * @param pattern the pattern to be converted
    * @return the resulting regular expression
    */
  private def generateGlobRexEx(pattern: String): Regex =
    ("(?i)" + Pattern.quote(pattern).replace("?", "\\E.\\Q")
      .replace("*", "\\E.*\\Q")).r

  /**
    * Parses the filter parameters for all action types and returns a map with
    * all filters per action.
    *
    * @param args          the map with all arguments
    * @param commonFilters a sequence with common filter definitions
    * @param ec            the execution context
    * @return a future with the parsed parameters for action filters
    */
  private def parseFiltersPerActionType(args: Map[String, Iterable[String]],
                                        commonFilters: List[SyncOperationFilter])
                                       (implicit ec: ExecutionContext): Future[ActionFilters] = {
    Future.sequence(ActionFilterParameters map { t =>
      parseFilterOption(args.getOrElse(t._1, Nil), t._2, commonFilters)
    }) map (lst => lst.reduce(_ ++ _))
  }

  /**
    * Parses the given list of filter expressions and converts it to a list of
    * filter functions.
    *
    * @param expressions   the filter expressions to be parsed
    * @param commonFilters a sequence with common filter definitions
    * @param ec            the execution context
    * @return a future with the parsed filter functions
    */
  private def parseExpressionsOfFilterOption(expressions: Iterable[String],
                                             commonFilters: List[SyncOperationFilter])
                                            (implicit ec: ExecutionContext):
  Future[List[SyncOperationFilter]] = Future {
    expressions.foldLeft(commonFilters) { (filters, expr) =>
      parseExpression(expr) :: filters
    }
  }

  /**
    * Evaluates filter options to disable/enable specific actions. For all
    * action types that are disabled a condition is added to their list of
    * filters that always rejects sync operations.
    *
    * @param args    the map with command line arguments
    * @param filters the action filters constructed so far
    * @return the updated map of action filters
    */
  private def parseActionFilter(args: Map[String, Iterable[String]],
                                filters: ActionFilters)
                               (implicit ec: ExecutionContext): Future[ActionFilters] = Future {
    val enabledActionTypes = extractEnabledActionTypes(args)
    filters.map { e =>
      e._1 ->
        (if (!enabledActionTypes.contains(e._1)) RejectFilter :: e._2
        else e._2)
    }
  }

  /**
    * Extracts the action types that are enabled for the current sync process.
    * A validation is performed as well; unsupported action types cause an
    * exception (causing the caller's future to fail).
    *
    * @param args the map with command line arguments
    * @return a set with all enabled action types
    */
  private def extractEnabledActionTypes(args: Map[String, Iterable[String]]): Set[SyncAction] = {
    val actionTypeNames = args.getOrElse(ArgActionFilter, ActionTypeList)
      .flatMap(_.split(ActionTypeSeparator))
      .map(_.trim.toLowerCase(Locale.ROOT))
    val invalidActionTypes = actionTypeNames filterNot ActionTypeNameMapping.contains
    if (invalidActionTypes.nonEmpty) {
      throw new IllegalArgumentException("Invalid action types: " +
        invalidActionTypes.mkString(ActionTypeSeparator))
    }
    actionTypeNames.map(ActionTypeNameMapping(_))
      .toSet
  }

  /**
    * Removes all parameters supported by the filter manager from the given
    * map with arguments.
    *
    * @param args the map with arguments
    * @return a future with the map with filter arguments removed
    */
  private def removeFilterParameters(args: Map[String, Iterable[String]])
                                    (implicit ec: ExecutionContext):
  Future[Map[String, Iterable[String]]] = Future {
    args filterNot (AllFilterParameters contains _._1)
  }

  /**
    * Generates a regular expression to parse a filter expression. The
    * expression consists of a property name and an expression to parse the
    * data type with some boiler-plate structure.
    *
    * @param property the name of the property
    * @param dataType the data type
    * @return the RegEx to parse this filter expression
    */
  private def filterExpressionRegEx(property: String, dataType: String): Regex =
    raw"$property[:=]($dataType)".r
}
