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

import scala.collection.SortedSet

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

  /**
    * The character to be used for the multiplicity if the maximum is not
    * restricted.
    */
  final val MultiplicityUnrestricted = "*"

  /**
    * A standard sort function for options that implements an alphabetic
    * ordering (which is case-insensitive).
    */
  final val AlphabeticOptionSortFunc: OptionSortFunc = _.sortWith((d1, d2) => toUpper(d1.key) < toUpper(d2.key))

  /** A standard filter function which accepts all options. */
  final val AllFilterFunc: OptionFilter = _ => true

  /** The default padding string to separate columns of the help text. */
  final val DefaultPadding: String = "  "

  /** The separator string between group names. */
  private final val GroupSeparator = ","

  /** The multiplicity of an option if no meta data about it is available. */
  private final val DefaultMultiplicity = "0..*"

  /**
    * The platform-specific line separator. This is used as line feed character
    * between two lines of the help text.
    */
  private val CR = System.lineSeparator()

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

    val metaData = context.options.map(e => OptionMetaData(e._1, e._2))
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
  }

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
    data.map(_.length).max

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
    * Helper function to convert a string to uppercase for comparison.
    *
    * @param s the string
    * @return the string as uppercase
    */
  private def toUpper(s: String): String = s.toUpperCase(Locale.ROOT)
}
