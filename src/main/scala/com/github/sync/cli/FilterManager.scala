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

import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.time.temporal.TemporalQuery
import java.time.{LocalDateTime, ZoneId}
import java.util.Locale
import java.util.regex.Pattern

import com.github.sync.SyncTypes._
import com.github.sync.cli.ParameterManager.{CliProcessor, OptionValue, ParameterContext}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Success, Try}

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

  /**
    * A map assigning names of action types to the corresponding type objects.
    * This is used to deactivate specific actions based on filter options.
    */
  private val ActionTypeNameMapping: Map[String, SyncAction] = Map("actioncreate" -> ActionCreate,
    "actionoverride" -> ActionOverride, "actionremove" -> ActionRemove)

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
    * Extracts filtering information from the parameter context.
    *
    * The passed in context contains all command line arguments provided to the
    * CLI keyed by command line options (already converted to uppercase). As
    * options can be repeated, the values of the map are lists. The function
    * tries to filter out all options that are related to filtering and removes
    * them from the arguments map. The single options are processed and
    * converted into ''SyncOperationFilter'' filters.
    *
    * The resulting future contains the produced ''SyncFilterData'' and an
    * updated ''ParameterContext'' object. If one of the filtering parameters
    * was invalid and could not be parsed, the resulting future is failed.
    *
    * @param paramCtx the ''ParameterContext'' allowing access to CLI options
    * @param ec       the execution context
    * @return a future with the extracted ''SyncFilterData'' and the updated
    *         ''ParameterContext''
    */
  //TODO Remove after all dependencies have been reworked
  def parseFilters(paramCtx: ParameterContext)(implicit ec: ExecutionContext):
  Future[(SyncFilterData, ParameterContext)] =
    Future.successful((SyncFilterData(Map.empty), paramCtx))

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
    * Returns a ''CliProcessor'' that extracts all command line options related
    * to filters and constructs a ''SyncFilterData'' object based on this.
    *
    * @return the ''CliProcessor'' for filter options
    */
  def filterDataProcessor: CliProcessor[Try[SyncFilterData]] =
    for {
      exprCommon <- filterExpressionProcessor(ArgCommonFilter)
      exprCreate <- filterExpressionProcessor(ArgCreateFilter)
      exprOverride <- filterExpressionProcessor(ArgOverrideFilter)
      exprRemove <- filterExpressionProcessor(ArgRemoveFilter)
      enabledActions <- actionFilterProcessor
    } yield createSyncFilterData(exprCommon, exprCreate, exprOverride, exprRemove, enabledActions)

  /**
    * Returns a ''CliProcessor'' that extracts the filter expressions for a
    * specific action type.
    *
    * @param key the key of the action type
    * @return the processor that extracts the filter expressions for this type
    */
  private def filterExpressionProcessor(key: String): CliProcessor[OptionValue[SyncOperationFilter]] =
    ParameterManager.optionValue(key)
      .mapTo(parseExpression)

  /**
    * Returns a ''CliProcessor'' that processes the action types filter. It
    * returns a set with the types of the actions that are enabled.
    *
    * @return the processor to extract the enabled action types
    */
  private def actionFilterProcessor: CliProcessor[Try[Set[SyncAction]]] =
    ParameterManager.optionValue(ArgActionFilter)
      .mapTo(parseActionNames)
      .fallback(ParameterManager.constantProcessor(Success(List(ActionTypeNameMapping.values.toSet))))
      .map { triedSets => triedSets.map(s => s.flatten.toSet) }

  /**
    * Tries to create a ''SyncFilterData'' object from the passed in
    * components.
    *
    * @param triedCommonFilters   the common filters component
    * @param triedCreateFilters   the create filters component
    * @param triedOverrideFilters the override filters component
    * @param triedRemoveFilters   the remove filters component
    * @param triedActionFilter    the action filter component
    * @return a ''Try'' with the ''SyncFilterData''
    */
  private def createSyncFilterData(triedCommonFilters: OptionValue[SyncOperationFilter],
                                   triedCreateFilters: OptionValue[SyncOperationFilter],
                                   triedOverrideFilters: OptionValue[SyncOperationFilter],
                                   triedRemoveFilters: OptionValue[SyncOperationFilter],
                                   triedActionFilter: Try[Set[SyncAction]]): Try[SyncFilterData] =
    ParameterManager.createRepresentation(triedCommonFilters, triedCreateFilters, triedOverrideFilters,
      triedRemoveFilters, triedActionFilter) {
      (commonFilters, createFilters, overrideFilters, removeFilters, actionFilter) =>
        val commonsList = commonFilters.toList

        def createMapping(action: SyncAction, filters: Iterable[SyncOperationFilter]):
        (SyncAction, List[SyncOperationFilter]) = {
          val resFilters = filters.toList ::: commonsList
          action -> resFilters
        }

        val filtersMap = Map(createMapping(ActionCreate, createFilters),
          createMapping(ActionOverride, overrideFilters),
          createMapping(ActionRemove, removeFilters))
        SyncFilterData(applyActionFilter(actionFilter, filtersMap))
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
    * Adds the filter for enabled/disabled action types to the map with
    * filters. For each action type that is not contained in the set of enabled
    * actions, a reject filter is put in front of the filters list.
    *
    * @param enabledActionTypes a set with action types that are enabled
    * @param filters            the current map with filters per action type
    * @return the resulting map with action filters
    */
  private def applyActionFilter(enabledActionTypes: Set[SyncAction], filters: ActionFilters): ActionFilters =
    filters.map { e =>
      e._1 ->
        (if (!enabledActionTypes.contains(e._1)) RejectFilter :: e._2
        else e._2)
    }

  /**
    * Converts a string with action filters to a corresponding set with the
    * action types that are enabled.
    *
    * @param actions the string value of the action filter
    * @return the set with enabled action types
    */
  private def parseActionNames(actions: String): Set[SyncAction] = {
    val actionTypeNames = actions.split(ActionTypeSeparator)
      .map(_.trim.toLowerCase(Locale.ROOT))
    val invalidActionTypes = actionTypeNames filterNot ActionTypeNameMapping.contains
    if (invalidActionTypes.nonEmpty) {
      val plural = if (invalidActionTypes.length > 1) "s" else ""
      throw new IllegalArgumentException(
        s"Invalid action type$plural: ${invalidActionTypes.mkString(ActionTypeSeparator)}")
    }
    actionTypeNames.map(ActionTypeNameMapping(_))
      .toSet
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
