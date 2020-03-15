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

/**
  * A trait defining a mechanism how command line options can be queried from
  * the user.
  *
  * This mechanism is especially used for command line options that represent
  * passwords. Passwords should not be passed in plain text as command line
  * arguments when invoking the CLI. Rather, the user is prompted for them if
  * necessary.
  */
trait ConsoleReader {
  /**
    * Prompts the user to enter the value for the given key. A typical
    * implementation prints the key and then waits for the user to enter a
    * string. The ''password'' parameter controls whether the user's input
    * should be treated as secret (which is the default for the intended use
    * case): if set to '''true''', no echo should be displayed for the keys
    * typed by the user.
    *
    * @param key      the key of the option to be read
    * @param password flag whether this is a password option
    * @return the input read from the user
    */
  def readOption(key: String, password: Boolean): String
}

/**
  * A default implementation of the ''ConsoleReader'' trait that reads user
  * input via Java's ''Console'' class.
  *
  * ''java.io.Console'' offers methods for prompting the user and gathering
  * user input, with and without echo (as needed for passwords). Unfortunately,
  * the class is final which makes testing rather difficult.
  */
object DefaultConsoleReader extends ConsoleReader {
  override def readOption(key: String, password: Boolean): String = {
    val prompt = s"$key: "
    val console = System.console()
    if (password) String.valueOf(console.readPassword(prompt))
    else console.readLine(prompt)
  }
}
