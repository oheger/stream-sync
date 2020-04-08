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

import java.util.Locale

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.mutable.ListBuffer

/**
  * A module providing functionality related to the generation of help
  * information for a command line interface.
  *
  * When defining the options and input parameters supported by a CLI help
  * texts can be specified; some other meta information is collected
  * automatically. This module defines classes to collect this information and
  * to generate help or usage texts out of it.
  */
object CliHelpGenerator {
  /** The attribute representing the help text of an option. */
  final val AttrHelpText = "helpText"

  /** The attribute with a description for the fallback value. */
  final val AttrFallbackValue = "fallbackValue"

  /** The attribute defining the multiplicity of an option. */
  final val AttrMultiplicity = "multiplicity"

  /**
    * The attribute assigning a group to an option. Groups are used to handle
    * options that are valid only in certain constellations, e.g. when
    * conditional processors are involved, or if a CLI supports multiple
    * commands, each of which has its own set of options. In the help text it
    * can then be indicated that the options belonging to a group are allowed
    * only if specific conditions are fulfilled.
    */
  final val AttrGroup = "group"

  /**
    * The attribute defining the type of an option. This attribute contains the
    * information whether an option is a regular option, a switch, or an input
    * parameter.
    */
  final val AttrOptionType = "optionType"

  /** Option type indicating a plain option. */
  final val OptionTypeOption = "option"

  /** Option type indicating an input parameter. */
  final val OptionTypeInput = "input"

  /**
    * A prefix for keys for input parameters that are generated. This is used
    * if for an input parameter no key has been provided explicitly. The index
    * of the input parameter is appended.
    */
  final val KeyInput = "input"

  /** The prefix indicating a command line option. */
  final val DefaultOptionPrefix = "--"

  /**
    * A standard sort function for options that implements an alphabetic
    * ordering (which is case-insensitive).
    */
  final val AlphabeticOptionSortFunc: OptionSortFunc = _.sortWith((d1, d2) => toUpper(d1.key) < toUpper(d2.key))

  /** A standard filter function which accepts all options. */
  final val AllFilterFunc: OptionFilter = _ => true

  /**
    * A standard filter function that accepts only elements with the option
    * type ''option''.
    */
  final val OptionsFilterFunc: OptionFilter = optionTypeFilter(OptionTypeOption)

  /**
    * A standard filter function that accepts only elements with the option
    * type ''input''. This function can be used to deal only with input
    * parameters.
    */
  final val InputParamsFilterFunc: OptionFilter = optionTypeFilter(OptionTypeInput)

  /**
    * A standard filter function that accepts only elements that are not
    * assigned to any group. These are usually top-level options that do not
    * depend on a context, but are always valid.
    */
  final val UnassignedGroupFilterFunc: OptionFilter =
    data => !data.attributes.attributes.contains(AttrGroup)

  /** The default padding string to separate columns of the help text. */
  final val DefaultPadding: String = "  "

  /**
    * The platform-specific line separator. This is used as line feed character
    * between two lines of the help text.
    */
  final val CR = System.lineSeparator()

  /**
    * Default formatting symbols used to generate the overview of input
    * parameters.
    */
  final val DefaultInputParamSymbols = InputParamOverviewSymbols(keyPrefix = "<", keySuffix = ">",
    optionalPrefix = "[", optionalSuffix = "]", ellipsis = "...")

  /** The separator string between group names. */
  private final val GroupSeparator = ","

  /**
    * A data class storing information about a single command line option.
    *
    * An instance of this class contains meta data that can be used to generate
    * the help text of a CLI application. The meta data is extensible and is
    * therefore stored as a map of attributes (whose values are strings as they
    * are expected to be printed on the console).
    *
    * @param attributes a map with attributes for the associated option
    */
  case class OptionAttributes(attributes: Map[String, String])

  /**
    * A data class holding information about input parameters.
    *
    * Input parameters are values passed to an application directly and not as
    * options. They can be assigned a key (a short name) and a detailed help
    * text with a description. The attributes of input parameters are stored in
    * the same way as for command line options. This class is used to hold an
    * ordered set of references to input parameters (as the order of these
    * parameters is typically relevant) from their indices to their keys. From
    * the keys, the other information available can be retrieved.
    *
    * @param index the index of the input parameter
    * @param key   the key of the input parameter
    */
  case class InputParameterRef(index: Int, key: String) extends Ordered[InputParameterRef] {
    /**
      * @inheritdoc This implementation orders input parameter references by
      *             their index, treating negative indices in a special way, so
      *             that they appear after the positive ones. (Negative indices
      *             reference the last input parameters.)
      */
    override def compare(that: InputParameterRef): Int = {
      val sigThis = sig(index)
      val sigThat = sig(that.index)
      if (sigThis != sigThat) sigThat
      else index - that.index
    }
  }

  /**
    * A data class containing all the information available for a CLI option.
    *
    * @param key        the key of the option
    * @param attributes a data object with the attributes of this option
    */
  case class OptionMetaData(key: String, attributes: OptionAttributes)

  /**
    * A case class defining symbols to be used when generating the overview of
    * input parameters.
    *
    * The strings defined by this class are used to decorate the keys of input
    * parameters to indicate specific properties.
    *
    * @param keyPrefix      the prefix added before parameter keys
    * @param keySuffix      the suffix added after parameter keys
    * @param optionalPrefix prefix to mark an element as optional
    * @param optionalSuffix suffix to mark an element as optional
    * @param ellipsis       string to represent a gap between parameter indices
    */
  case class InputParamOverviewSymbols(keyPrefix: String,
                                       keySuffix: String,
                                       optionalPrefix: String,
                                       optionalSuffix: String,
                                       ellipsis: String) {
    /**
      * Decorates a key with the correct symbols.
      *
      * @param key the key
      * @return the decorated key
      */
    def decorateKey(key: String): String = s"$keyPrefix$key$keySuffix"

    /**
      * Conditionally adds markers to the given string to indicate that it
      * represents an optional value.
      *
      * @param str      the string
      * @param optional the flag whether the value is optional
      * @return the resulting string
      */
    def markAsOptional(str: String, optional: Boolean): String =
      if (optional) optionalPrefix + str + optionalSuffix else str
  }

  /**
    * A class for storing and updating meta information about command line
    * options that can be used to generate help or usage texts.
    *
    * An instance of this class is available in the context passed to
    * ''CliProcessor'' objects. The processors update the instance with specific
    * information, so that meta data about the options supported by the
    * application is collected.
    *
    * @param options       a map storing the data available for the single options
    * @param inputs        a set with data about input parameters
    * @param optCurrentKey a key to the option that is currently defined
    * @param groups        a list with the currently active group names
    */
  class CliHelpContext(val options: Map[String, OptionAttributes],
                       val inputs: SortedSet[InputParameterRef],
                       optCurrentKey: Option[String],
                       groups: List[String]) {

    /**
      * Adds data about another command line option to this object. This
      * function creates a new [[OptionAttributes]] instance and initializes it
      * from the parameters passed in. It returns a new ''CliHelpContext'' object
      * whose data map contains this new instance. If there is already an entry
      * for this option key, it is merged with the data passed to this function.
      *
      * @param key  the option key
      * @param text an optional help text
      * @return the updated ''CliHelpContext''
      */
    def addOption(key: String, text: Option[String]): CliHelpContext =
      contextWithOption(key, text, OptionTypeOption, inputs)

    /**
      * Adds data about an input parameter to this object. This function works
      * similar to ''addOption()'', but creates additional information to keep
      * track on the order of these parameters.
      *
      * @param index  the index of the input parameter
      * @param optKey the optional key; if it is undefined, a key is generated
      * @param text   an optional help text
      * @return the updated ''CliHelpContext''
      */
    def addInputParameter(index: Int, optKey: Option[String], text: Option[String]): CliHelpContext = {
      val key = optKey.getOrElse(KeyInput + index)
      val inputRef = InputParameterRef(index, key)
      contextWithOption(key, text, CliHelpGenerator.OptionTypeInput, inputs + inputRef)
    }

    /**
      * Adds an attribute for the current option. This function is called by
      * ''CliProcessor'' objects to add more detailed information about a
      * command line option. It refers to the last option that has been added.
      *
      * @param attrKey the key of the attribute
      * @param value   the value of the attribute
      * @return the updated ''CliHelpContext''
      */
    def addAttribute(attrKey: String, value: String): CliHelpContext =
      optCurrentKey match {
        case Some(key) =>
          val attrs = options(key)
          val newAttrs = OptionAttributes(attrs.attributes + (attrKey -> value))
          new CliHelpContext(options + (key -> newAttrs), inputs, optCurrentKey, groups)
        case None =>
          this
      }

    /**
      * Checks whether the command line option with the given key has a specific
      * attribute set. Only the presence of the attribute is checked, not the
      * concrete value.
      *
      * @param key     the key of the option
      * @param attrKey the key of the attribute
      * @return a flag whether this attribute is present for this option; if the
      *         option cannot be resolved, result is '''false'''
      */
    def hasAttribute(key: String, attrKey: String): Boolean =
      options.get(key) exists (_.attributes contains attrKey)

    /**
      * Notifies this context about the start of a new group. New options that
      * are added later are assigned to this group.
      *
      * @param groupName the name of the group
      * @return the updated ''CliHelpContext''
      */
    def startGroup(groupName: String): CliHelpContext =
      new CliHelpContext(options, inputs, optCurrentKey, groupName :: groups)

    /**
      * Notifies this context about a potential start of a new group. If the
      * group name is defined, a new group is started; otherwise, the same
      * context is returned.
      *
      * @param optGroupName the optional group name
      * @return the updated ''CliHelpContext'' or the same one
      */
    def startGroupConditionally(optGroupName: Option[String]): CliHelpContext =
      optGroupName.fold(this)(startGroup)

    /**
      * Notifies this context that a group has been processed. The name of the
      * current group is removed.
      *
      * @return the updated ''CliHelpContext''
      */
    def endGroup(): CliHelpContext =
      new CliHelpContext(options, inputs, optCurrentKey, groups.tail)

    /**
      * Notifies this context that a group has potentially been processed. If
      * the given ''Option'' with the group name is defined, the name of the
      * current group is removed; otherwise, this context is returned
      * unchanged.
      *
      * @param optGroupName the ''Option'' with the group name
      * @return the updated ''CliHelpContext'' or the same one
      */
    def endGroupConditionally(optGroupName: Option[String]): CliHelpContext =
      optGroupName.fold(this)(_ => endGroup())

    /**
      * Returns an ''Iterable'' with ''OptionMetaData'' objects for the options
      * stored in this context.
      *
      * @return an iterable with meta data about all options in this context
      */
    def optionMetaData: Iterable[OptionMetaData] = options.map(e => OptionMetaData(e._1, e._2))

    /**
      * Creates a new ''CliHelpContext'' with an additional option as defined by
      * the parameters.
      *
      * @param key        the option key
      * @param text       the help text for the option
      * @param optionType the type of the option
      * @param inputRefs  the input data for the new context
      * @return the updated ''CliHelpContext''
      */
    private def contextWithOption(key: String, text: Option[String], optionType: String,
                                  inputRefs: SortedSet[InputParameterRef]): CliHelpContext = {
      val existingAttrs = options.get(key).map(_.attributes) getOrElse Map.empty
      val existingGroups = existingAttrs.getOrElse(AttrGroup, "")
      val attrs = addOptionalAttribute(
        addOptionalAttribute(Map.empty, AttrHelpText, text),
        AttrGroup, groupAttribute.map(existingGroups + _)) + (AttrOptionType -> optionType)
      val help = OptionAttributes(existingAttrs ++ attrs)
      new CliHelpContext(options + (key -> help), inputRefs, optCurrentKey = Some(key), groups)
    }

    /**
      * Returns a string with the concatenated names of all groups that are
      * currently active.
      *
      * @return a string with the names of the active groups
      */
    private def activeGroupNames: String = groups.mkString(GroupSeparator) + GroupSeparator

    /**
      * Returns an ''Option'' with the current value of the group attribute. If
      * there are active groups, the ''Option'' contains their names;
      * otherwise, it is empty.
      *
      * @return an ''Option'' with the names of the currently active groups
      */
    private def groupAttribute: Option[String] =
      activeGroupNames match {
        case GroupSeparator => None
        case s => Some(s)
      }
  }

  /**
    * Type definition of a function that performs a modification of a
    * ''CliHelpContext''. The current context is passed in as argument, the
    * modified one is the result.
    */
  type HelpContextUpdater = CliHelpContext => CliHelpContext

  /**
    * Returns a ''HelpContextUpdater'' that adds an attribute with its key and
    * value to the [[CliHelpContext]].
    *
    * @param attrKey   the key of the attribute to be added
    * @param attrValue the value of the attribute
    * @return the updater that sets exactly this attribute
    */
  def addAttributeUpdater(attrKey: String, attrValue: String): HelpContextUpdater =
    context => context.addAttribute(attrKey, attrValue)

  /**
    * Checks whether the option whose attributes are provided belongs to the
    * given group.
    *
    * @param attrs the ''OptionAttributes''
    * @param group the name of the group
    * @return a flag whether this option belongs to this group
    */
  def isInGroup(attrs: OptionAttributes, group: String): Boolean =
    attrs.attributes.get(AttrGroup) exists (_ contains group + GroupSeparator)

  /**
    * Returns a set with the names of all groups the option whose attributes
    * are provided belongs to.
    *
    * @param attrs the ''OptionAttributes''
    * @return a set with the names of all groups
    */
  def groups(attrs: OptionAttributes): Set[String] =
    attrs.attributes.get(AttrGroup).map(_.split(GroupSeparator).toSet) getOrElse Set.empty

  /**
    * Type definition of a function that sorts the list of options in the
    * generated help text.
    */
  type OptionSortFunc = Seq[OptionMetaData] => Seq[OptionMetaData]

  /**
    * Type definition for a predicate to filter options from a
    * [[CliHelpContext]].
    */
  type OptionFilter = OptionMetaData => Boolean

  /**
    * Type definition of a function that generates a column of a help text of
    * an option. The column can consist of multiple lines of text hence, the
    * result is a list of strings). For each option, multiple columns can be
    * generated that are defined by specifying the corresponding generator
    * functions.
    */
  type ColumnGenerator = OptionMetaData => List[String]

  /**
    * Generates a tabular help text for the command line options of an
    * application. For each option, a number of columns is displayed that are
    * defined by a sequence of ''ColumnGenerator'' functions. The table is
    * converted to a string that can be directly printed to the console. By
    * passing in additional parameters, the output can be customized.
    *
    * @param context    the ''CliHelpContext'' with all meta data about options
    * @param sortFunc   a function to sort the list of options; per default,
    *                   options are sorted alphabetically ignoring case
    * @param filterFunc a function to filter the options to be displayed; per
    *                   default, all options are shown
    * @param padding    a padding string inserted between columns
    * @param columns    the functions to generate the single columns
    * @return a string with the help for command line options
    */
  def generateOptionsHelp(context: CliHelpContext,
                          sortFunc: OptionSortFunc = AlphabeticOptionSortFunc,
                          filterFunc: OptionFilter = AllFilterFunc,
                          padding: String = DefaultPadding)
                         (columns: ColumnGenerator*): String = {

    // generates the columns of an option by applying the column generators
    def generateColumns(data: OptionMetaData): Seq[List[String]] =
      columns.map(_.apply(data))

    val metaData = context.optionMetaData
      .filter(filterFunc)
      .toSeq
    val rows = sortFunc(metaData)
      .map(generateColumns)
    val widths = rows map columnWidths
    val maxWidths = widths.transpose.map(_.max)
    val spaces = paddingString(maxWidths)

    // generates the row for an option that can consist of multiple lines;
    // the lines have to be correctly aligned and padded
    def generateRow(columns: Seq[List[String]]): Seq[String] = {
      val maxLineCount = columns.map(_.size).max
      val emptyList = List.fill(maxLineCount)("")
      val filledColumns = columns.map(list => growList(list, maxLineCount, emptyList))

      filledColumns.transpose.map(_.zip(maxWidths))
        .map { line =>
          line.map { t =>
            val cell = t._1
            cell + spaces.substring(0, t._2 - cell.length)
          }.mkString(padding)
        }
    }

    rows.flatMap(generateRow)
      .mkString(CR)
  }

  /**
    * A special ''OptionSortFunc'' that handles input parameters. The
    * parameters are sorted based on their expected order in the command line.
    * The function expects the list of options to be ordered has been filtered
    * to contain input parameters only.
    *
    * @param helpContext the ''CliHelpContext''
    * @return the function to sort input parameter options
    */
  def inputParamSortFunc(helpContext: CliHelpContext): OptionSortFunc = {
    def paramIndex(key: String): Int =
      helpContext.inputs.find(_.key == key).map(_.index) getOrElse helpContext.inputs.size

    options =>
      options.map(data => (data, paramIndex(data.key)))
        .sortWith(_._2 < _._2)
        .map(_._1)
  }

  /**
    * Returns a filter function that accepts only options belonging to the
    * given group.
    *
    * @param group the name of the group
    * @return the function that filters for this group
    */
  def groupFilterFunc(group: String): OptionFilter =
    data => isInGroup(data.attributes, group)

  /**
    * Returns a filter function implementing AND logic. The resulting filter
    * accepts an element if and only if all of the filters provided accept it.
    *
    * @param filters the filters to be combined
    * @return a combined filter function with AND semantics
    */
  def andFilter(filters: OptionFilter*): OptionFilter =
    data => filters.forall(f => f(data))

  /**
    * Returns a filter function implementing OR logic. The resulting filter
    * accepts an element as soon as one of the filters provided accepts it.
    *
    * @param filters the filters to be combined
    * @return a combined filter function with OR semantics
    */
  def orFilter(filters: OptionFilter*): OptionFilter =
    data => filters.exists(f => f(data))

  /**
    * Returns a filter that yields the opposite result of the filter provided.
    * This filter can be used for instance when exclusion logic is needed.
    *
    * @param filter the original filter
    * @return the negated filter
    */
  def negate(filter: OptionFilter): OptionFilter =
    data => !filter(data)

  /**
    * Returns a ''ColumnGenerator'' function that produces a single text line
    * from the value of the attribute specified. If the attribute is not
    * present, result is an empty list.
    *
    * @param attrKey the key of the attribute to be read
    * @return the ''ColumnGenerator'' reading this attribute
    */
  def attributeColumnGenerator(attrKey: String): ColumnGenerator = data =>
    data.attributes.attributes.get(attrKey) map (List(_)) getOrElse List.empty

  /**
    * Returns a ''ColumnGenerator'' function that generates the name of the
    * current command line option. This can be used for instance in the first
    * column to display the option the following information is about.
    *
    * @param optionPrefix a prefix added to the option name
    * @return the ''ColumnGenerator'' generating the option name
    */
  def optionNameColumnGenerator(optionPrefix: String = DefaultOptionPrefix): ColumnGenerator =
    data => List(optionPrefix + data.key)

  /**
    * Returns a ''ColumnGenerator'' that renders the multiplicity attribute.
    * This is a convenience function that correctly shows the default
    * multiplicity if none is provided.
    *
    * @return the ''ColumnGenerator'' producing the options' multiplicity
    */
  def multiplicityColumnGenerator: ColumnGenerator =
    defaultValueColumnGenerator(attributeColumnGenerator(AttrMultiplicity), Multiplicity.UnspecifiedMultiplicityString)

  /**
    * Returns a ''ColumnGenerator'' function that applies a default value to
    * another generator function. The passed in function is invoked first. If
    * it does not yield any values, the default values are returned.
    *
    * @param generator     the generator function to decorate
    * @param defaultValues the default values
    * @return the ''ColumnGenerator'' applying default values
    */
  def defaultValueColumnGenerator(generator: ColumnGenerator, defaultValues: String*): ColumnGenerator = {
    val defaultList = defaultValues.toList
    data =>
      generator(data) match {
        case l@_ :: _ => l
        case _ => defaultList
      }
  }

  /**
    * Returns a ''ColumnGenerator'' function that allows adding prefix lines to
    * or a prefix text to all lines of another generator function. This is
    * useful for instance to add a caption to an attribute value. The prefix is
    * added only if the decorated generator function yields a value; otherwise,
    * this generator returns an empty list, too.
    *
    * @param generator   the generator function to decorate
    * @param prefixLines a list with lines to add as prefix to the generator
    * @param prefixText  a text to add as prefix to each original line
    * @return the ''CliHelpGenerator'' generating a prefix
    */
  def prefixColumnGenerator(generator: ColumnGenerator, prefixLines: List[String] = Nil,
                            prefixText: Option[String] = None): ColumnGenerator =
    data =>
      generator(data) match {
        case l@_ :: _ =>
          val lines = prefixText.fold(generator(data))(pref => l.map(pref + _))
          prefixLines ++ lines
        case l => l
      }

  /**
    * Returns a ''ColumnGenerator'' function that wraps the text lines returned
    * by another generator. The text lines returned by the decorated generator
    * are split at newline characters. The resulting lines are wrapped at the
    * given line length.
    *
    * @param generator  the generator function to decorate
    * @param lineLength the line length for wrapping
    * @return the ''ColumnGenerator'' doing wrapping
    */
  def wrapColumnGenerator(generator: ColumnGenerator, lineLength: Int): ColumnGenerator =
    data =>
      generator(data)
        .flatMap(splitLines)
        .flatMap(wrapLine(_, lineLength))

  /**
    * Returns a ''ColumnGenerator'' function that composes the results of all
    * the passed in generators. Using this function, the cells of the tabular
    * option help can contain the data of multiple generators.
    *
    * @param generators the sequence of generator functions to combine
    * @return the ''ColumnGenerator'' that composes these generators
    */
  def composeColumnGenerator(generators: ColumnGenerator*): ColumnGenerator =
    data => generators.flatMap(g => g(data)).toList

  /**
    * Generates an overview over the input parameters of an application. The
    * function generates a list where each element represents an input
    * parameter. The information about a single parameter is a visual
    * representation of the key and its multiplicity. Having this information
    * available as a list of strings allows the caller to integrate it into
    * other output; e.g. by doing a ''mkstring(" ")'', it can be displayed on a
    * single line.
    *
    * @param helpContext the help context
    * @param symbols     defines the symbols to indicate certain parameter
    *                    properties
    * @return a list with the information about all input parameters
    */
  def generateInputParamsOverview(helpContext: CliHelpContext,
                                  symbols: InputParamOverviewSymbols = DefaultInputParamSymbols): List[String] =
    helpContext.inputs.toList
      .map(inputParameterOverview(helpContext, _, symbols))

  /**
    * A function to determine the signum of an index which can be either
    * positive or negative.
    *
    * @param i the input number
    * @return the signum of this number
    */
  private def sig(i: Int): Int =
    if (i < 0) -1 else 1

  /**
    * Calculates the width of all the columns of a single row in a table of
    * option data.
    *
    * @param row the row (consisting of multiple columns)
    * @return a sequence with the widths of the columns
    */
  private def columnWidths(row: Seq[List[String]]): Seq[Int] =
    row map cellWidth

  /**
    * Calculates the maximum width of a cell in a table of option data. The
    * cell can consist of multiple lines. The maximum line length is returned.
    *
    * @param data the data in the cell
    * @return the length of this cell
    */
  private def cellWidth(data: List[String]): Int =
    if (data.nonEmpty) data.map(_.length).max
    else 0

  /**
    * Makes sure that a list has the given size by appending elements of a list
    * with empty elements.
    *
    * @param list      the list to be manipulated
    * @param toSize    the desired target size
    * @param emptyList a list containing empty elements
    * @return the list with the target size
    */
  private def growList(list: List[String], toSize: Int, emptyList: List[String]): List[String] =
    if (list.size >= toSize) list
    else list ++ emptyList.slice(0, toSize - list.size)

  /**
    * Generates a string with a number of spaces that is used to pad the cells
    * of a table to a certain length. The length of the string is determined
    * from the maximum column width.
    *
    * @param colWidths the column widths
    * @return the padding string
    */
  private def paddingString(colWidths: Seq[Int]): String = {
    val maxSpaces = colWidths.max
    " " * maxSpaces
  }

  /**
    * Splits a string at newline characters and returns a list with the single
    * lines.
    *
    * @param s the string to be split
    * @return a list with the lines in the string
    */
  private def splitLines(s: String): List[String] =
    s.split("\n").toList

  /**
    * Determines the position where to wrap a string to adhere to the given
    * line length and the start position of the next line. This function tries
    * to find a space character at or before the line length. The space itself
    * is skipped. If no space character can be found in the line, the wrap
    * position is set to the maximum length. This function expects that the
    * input string is longer than the line length.
    *
    * @param s      the string
    * @param length the maximum line length
    * @return the positions where to break the string and to start the next
    *         line
    */
  private def wrapPositions(s: String, length: Int): (Int, Int) = {
    val posSpace = s.lastIndexOf(" ", length)
    if (posSpace <= 0) (length, length) else (posSpace, posSpace + 1)
  }

  /**
    * Wraps the given string, so that lines are no longer than the line length
    * specified. The lines are broken at space characters if possible.
    *
    * @param s      the string to be wrapped
    * @param length the maximum line length
    * @return the single wrapped lines of the string
    */
  private def wrapLine(s: String, length: Int): List[String] = {
    @tailrec def doWrap(line: String, builder: ListBuffer[String]): List[String] =
      if (line.length < length) {
        builder += line
        builder.toList
      } else {
        val (wrapPos, nextStart) = wrapPositions(line, length)
        builder += line.substring(0, wrapPos)
        doWrap(line.substring(nextStart), builder)
      }

    doWrap(s, ListBuffer.empty)
  }

  /**
    * Returns a filter function that filters for options of the given type.
    *
    * @param wantedType the option type to filter for
    * @return the filter function for this type
    */
  private def optionTypeFilter(wantedType: String): OptionFilter =
    data => data.attributes.attributes.get(AttrOptionType) contains wantedType

  /**
    * Adds an attribute and its value to the given map of attributes only if
    * the value is defined.
    *
    * @param attributes the map with attributes
    * @param key        the attribute key
    * @param optValue   the optional value of the attribute
    * @return the modified map with attributes if a change was necessary
    */
  private def addOptionalAttribute(attributes: Map[String, String], key: String, optValue: Option[String]):
  Map[String, String] =
    optValue map (value => attributes + (key -> value)) getOrElse attributes

  /**
    * Generates the part of the overview of an input parameter that is
    * determined by the lower bound of the multiplicity. These values are all
    * mandatory. Depending on the lower bound either a single key or multiple
    * keys need to be displayed.
    *
    * @param key          the key of the input parameter
    * @param multiplicity the multiplicity
    * @param symbols      the formatting symbols
    * @return the lower part of the overview
    */
  private def inputParameterOverviewLowerPart(key: String, multiplicity: Multiplicity,
                                              symbols: InputParamOverviewSymbols): String = {
    if (multiplicity.lower == 2) s"${symbols.decorateKey(key + "1")} ${symbols.decorateKey(key + "2")}"
    else if (multiplicity.lower > 1) s"${symbols.decorateKey(key + "1")}${symbols.ellipsis}" +
      symbols.decorateKey(key + multiplicity.lower)
    else {
      val index = if (!multiplicity.unbounded && multiplicity.upper > multiplicity.lower) "1" else ""
      symbols.decorateKey(key + index)
    }
  }

  /**
    * Generates the part of the overview of an input parameter that is
    * determined by the upper bound of the multiplicity. The upper part is
    * defined only if the upper bound is greater than the lower bound; these
    * values are optional.
    *
    * @param key          the key of the input parameter
    * @param multiplicity the multiplicity
    * @param symbols      the formatting symbols
    * @return the upper part of the overview
    */
  private def inputParameterOverviewUpperPart(key: String, multiplicity: Multiplicity,
                                              symbols: InputParamOverviewSymbols): String =
    if (!multiplicity.unbounded && multiplicity.upper <= multiplicity.lower) ""
    else {
      val result = if (multiplicity.unbounded) symbols.ellipsis
      else {
        val upperKey = s"${symbols.decorateKey(key + multiplicity.upper)}"
        if (multiplicity.upper > multiplicity.lower + 1) symbols.ellipsis + upperKey
        else upperKey
      }
      symbols.markAsOptional(result, multiplicity.mandatory)
    }

  /**
    * Generates the overview of an input parameter based on its key and
    * multiplicity.
    *
    * @param helpContext  the help context
    * @param parameterRef the reference to the parameter in question
    * @param symbols      the formatting symbols
    * @return a string with information about this input parameter
    */
  private def inputParameterOverview(helpContext: CliHelpContext, parameterRef: InputParameterRef,
                                     symbols: InputParamOverviewSymbols): String = {
    val key = parameterRef.key
    val multiplicity = helpContext.options(key).attributes.get(AttrMultiplicity)
      .map(Multiplicity.parse) getOrElse Multiplicity.Unbounded
    val lowerPart = inputParameterOverviewLowerPart(key, multiplicity, symbols)
    val upperPart = inputParameterOverviewUpperPart(key, multiplicity, symbols)
    val result = if (upperPart.nonEmpty) lowerPart + " " + upperPart else lowerPart
    symbols.markAsOptional(result, multiplicity.optional)
  }

  /**
    * Helper function to convert a string to uppercase for comparison.
    *
    * @param s the string
    * @return the string as uppercase
    */
  private def toUpper(s: String): String = s.toUpperCase(Locale.ROOT)
}
