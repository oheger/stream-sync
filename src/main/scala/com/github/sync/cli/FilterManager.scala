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
import com.github.sync.cli.ParameterExtractor.{CliExtractor, OptionValue}

import scala.annotation.tailrec
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
  final val ArgCreateFilter = "filter-create"

  /** Help text for the create filter option. */
  final val HelpCreateFilter =
    """Like the filter option, but the filter expressions defined by this option are evaluated \
      |only when creating files or folders.""".stripMargin

  /** Command line option to define a filter for override actions. */
  final val ArgOverrideFilter = "filter-override"

  /** Help text for the override filter option. */
  final val HelpOverrideFilter =
    """Like the filter option, but the filter expressions defined by this option are evaluated \
      |only when files are overridden in the target structure.""".stripMargin

  /** Command line option to define a filter for remove actions. */
  final val ArgRemoveFilter = "filter-remove"

  /** Help text for the remove filter option. */
  final val HelpRemoveFilter =
    """Like the filter option, but the filter expressions defined by this option are evaluated \
      |only when removing files or folders.""".stripMargin

  /** Command line option to define a filter for all actions. */
  final val ArgCommonFilter = "filter"

  /** Help text for the common filter option. */
  final val HelpCommonFilter =
    """Defines filter conditions that are applied to all kinds of actions. Only actions that match \
      |all filter criteria are executed during the sync process. This option can be repeated an \
      |arbitrary number of times to define multiple filter criteria. Option values must be valid \
      |filter expressions. The following expressions can be used:
      |  minlevel:<n> - Only files and folders with a level equal or higher than this value are \
      |processed. (Elements in the root folder have level 0, direct sub folders \
      |of the root folder have level 1 and so on.) Example: minlevel:2
      |  maxlevel:<n> - Like minlevel, but defines a maximum level for the elements to be \
      |processed; e.g. maxlevel:7
      |  exclude:<glob> - Defines glob expressions for files and folder paths. The matched \
      |paths are excluded from the sync process. Globs can contain the placeholders '?' for a \
      |single character and '*' for an arbitrary number of characters. Example: exclude:*.tmp
      |  include:<glob> - Like exclude, but with this option the paths to be included in the \
      |sync process can be specified; e.g. include:project1/*
      |  date-after:<time> - Allows selecting only files whose date of last modification is \
      |equal or after a given reference date. The reference date is specified in ISO format \
      |with an optional time portion, as in date-after:2018-09-01T22:00:00 or \
      |date-after:2020-01-01.
      |  date-before<time> - Like date-after, but only selects files with a date of last \
      |modification that is before a reference date; e.g. date-before:2020-01-01""".stripMargin

  /** Command line option for the filter to disable specific sync actions. */
  final val ArgActionFilter = "actions"

  /** Help text for the actions filter option. */
  final val HelpActionFilter =
    """With this option it is possible to execute only specific types of actions during a sync \
      |process. The option can be repeated, its value is either a single action type or a \
      |comma-delimited list of action types. Possible action types are actionCreate, \
      |actionOverride, and actionRemove (ignoring case). For instance, if no elements should \
      |be removed, specify only the other action types: --actions actionCreate,actionOverride""".stripMargin

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
    * Returns a ''CliExtractor'' that extracts all command line options related
    * to filters and constructs a ''SyncFilterData'' object based on this.
    *
    * @return the ''CliExtractor'' for filter options
    */
  def filterDataExtractor: CliExtractor[Try[SyncFilterData]] =
    for {
      exprCommon <- filterExpressionProcessor(ArgCommonFilter, HelpCommonFilter)
      exprCreate <- filterExpressionProcessor(ArgCreateFilter, HelpCreateFilter)
      exprOverride <- filterExpressionProcessor(ArgOverrideFilter, HelpOverrideFilter)
      exprRemove <- filterExpressionProcessor(ArgRemoveFilter, HelpRemoveFilter)
      enabledActions <- actionFilterProcessor
    } yield createSyncFilterData(exprCommon, exprCreate, exprOverride, exprRemove, enabledActions)

  /**
    * Returns a ''CliExtractor'' that extracts the filter expressions for a
    * specific action type.
    *
    * @param key  the key of the action type
    * @param help the help text for this option
    * @return the extractor that extracts the filter expressions for this type
    */
  private def filterExpressionProcessor(key: String, help: String): CliExtractor[OptionValue[SyncOperationFilter]] =
    ParameterExtractor.optionValue(key, help = Some(help))
      .mapTo(parseExpression)

  /**
    * Returns a ''CliExtractor'' that processes the action types filter. It
    * returns a set with the types of the actions that are enabled.
    *
    * @return the extractor to extract the enabled action types
    */
  private def actionFilterProcessor: CliExtractor[Try[Set[SyncAction]]] =
    ParameterExtractor.optionValue(ArgActionFilter, help = Some(HelpActionFilter))
      .mapTo(parseActionNames)
      .fallback(ParameterExtractor.constantExtractor(Success(List(ActionTypeNameMapping.values.toSet))))
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
    ParameterExtractor.createRepresentation(triedCommonFilters, triedCreateFilters, triedOverrideFilters,
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
