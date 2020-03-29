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

  /** The attribute indicating that an option has only a single value. */
  final val AttrSingleValue = "singleValued"

  /** The attribute indicating that an option is mandatory. */
  final val AttrMandatory = "mandatory"

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
    * A prefix for keys for input parameters that are generated. This is used
    * if for an input parameter no key has been provided explicitly. The index
    * of the input parameter is appended.
    */
  final val KeyInput = "input"

  /** A value used for boolean attributes. */
  private final val ValueBoolean = "true"

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
      contextWithOption(key, text, inputs)

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
      contextWithOption(key, text, inputs + inputRef)
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
      * Sets a boolean attribute for the current option. For such attributes, the
      * concrete value is irrelevant; it only matters whether the attribute is
      * present or not.
      *
      * @param attrKey the key of the attribute
      * @return the updated ''CliHelpContext''
      */
    def setAttribute(attrKey: String): CliHelpContext =
      addAttribute(attrKey, ValueBoolean)

    /**
      * Checks whether the command line option with the given key has a specific
      * attribute set. Only the presence of the attribute is checked, not the
      * concrete value. This is appropriate for boolean attributes.
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
      * @param key       the option key
      * @param text      the help text for the option
      * @param inputRefs the input data for the new context
      * @return the updated ''CliHelpContext''
      */
    private def contextWithOption(key: String, text: Option[String], inputRefs: SortedSet[InputParameterRef]):
    CliHelpContext = {
      val existingAttrs = options.get(key).map(_.attributes) getOrElse Map.empty
      val existingGroups = existingAttrs.getOrElse(AttrGroup, "")
      val attrs = addOptionalAttribute(
        addOptionalAttribute(Map.empty, AttrHelpText, text),
        AttrGroup, groupAttribute.map(existingGroups + _))
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
    * Returns a ''HelpContextUpdater'' that sets a boolean attribute in the
    * [[CliHelpContext]].
    *
    * @param attrKey the key of the attribute to be set
    * @return the updater that sets exactly this attribute
    */
  def setAttributeUpdater(attrKey: String): HelpContextUpdater =
    context => context setAttribute attrKey

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
    * A function to determine the signum of an index which can be either
    * positive or negative.
    *
    * @param i the input number
    * @return the signum of this number
    */
  private def sig(i: Int): Int =
    if (i < 0) -1 else 1

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
}
