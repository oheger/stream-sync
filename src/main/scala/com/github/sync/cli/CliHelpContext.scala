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

import com.github.sync.cli.CliHelpContext.OptionAttributes

object CliHelpContext {
  /** The attribute representing the help text of an option. */
  final val AttrHelpText = "helpText"

  /** The attribute with a description for the fallback value. */
  final val AttrFallbackValue = "fallbackValue"

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
  * @param optCurrentKey a key to the option that is currently defined
  */
class CliHelpContext(val options: Map[String, OptionAttributes],
                     optCurrentKey: Option[String]) {

  import CliHelpContext._

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
  def addOption(key: String, text: Option[String]): CliHelpContext = {
    val attrs = text.map(t => Map(AttrHelpText -> t)) getOrElse Map.empty
    val help = OptionAttributes(attrs)
    new CliHelpContext(options + (key -> help), optCurrentKey = Some(key))
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
        new CliHelpContext(options + (key -> newAttrs), optCurrentKey)
      case None =>
        this
    }
}
