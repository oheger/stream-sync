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

object Multiplicity {
  /** A standard ''Multiplicity'' representing a single, mandatory value. */
  final val SingleValue = Multiplicity(1, 1)

  /** A standard ''Multiplicity'' representing a single, optional value. */
  final val SingleOptional = Multiplicity(0, 1)

  /**
    * A standard ''Multiplicity'' representing an arbitrary number of values.
    */
  final val Unbounded = Multiplicity(0, -1)

  /**
    * Constant for a string representation of an unspecified multiplicity.
    * This string represents a ''Multiplicity'' that accepts an arbitrary
    * number of values.
    */
  final val UnspecifiedMultiplicityString = "0..*"

  /** A regular expression to parse a multiplicity string. */
  private val RegMultiplicity = raw"""(\d+)\.\.(\d+|\*)""".r

  /**
    * Creates a ''Multiplicity'' object from the given string representation.
    * The string must have a format as produced by the ''toString()'' method.
    * If it cannot be parsed, an ''IllegalArgumentException'' exception is
    * thrown.
    *
    * @param s the string to be parsed
    * @return the resulting ''Multiplicity''
    */
  @throws(classOf[IllegalArgumentException])
  def parse(s: String): Multiplicity =
    s match {
      case RegMultiplicity(low, up) if up == "*" =>
        Multiplicity(low.toInt, -1)
      case RegMultiplicity(low, up) =>
        Multiplicity(low.toInt, up.toInt)
      case _ =>
        throw new IllegalArgumentException("Invalid multiplicity string: " + s)
    }
}

/**
  * A data class representing the multiplicity of a command line option.
  *
  * This class provides some functionality to interpret the multiplicity and to
  * do conversions from or to strings.
  *
  * @param lower the lower bound
  * @param upper the upper bound (negative values mean unrestricted)
  */
case class Multiplicity(lower: Int, upper: Int) {
  /**
    * Returns a flag whether this ''Multiplicity'' has an unrestricted upper
    * bound. This means that an option with this multiplicity can have an
    * arbitrary number of values.
    *
    * @return a flag whether the upper bound is unrestricted
    */
  def unbounded: Boolean = upper < 0

  /**
    * Returns a flag whether this is an optional multiplicity. This means that
    * the lower bound is 0; no values are required.
    *
    * @return a flag whether the lower bound is 0
    */
  def optional: Boolean = lower == 0

  /**
    * Returns a flag whether this is a mandatory multiplicity. This means that
    * the lower bound is greater than 0; values must be present.
    *
    * @return a flag whether values must be provided
    */
  def mandatory: Boolean = !optional

  /**
    * Returns a string representation of this ''Multiplicity''. The string is
    * of the form ''lower''..''upper'', where ''upper'' may be replaced by a
    * star (*) for an unbounded multiplicity.
    *
    * @return a string representation of this object
    */
  override def toString: String = {
    val upperStr = if (unbounded) "*" else upper.toString
    s"$lower..$upperStr"
  }
}
