/*
 * Copyright 2018 The Developers Team.
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

import com.github.sync.cli.FilterManager.{SyncFilterData, SyncOperationFilter}
import com.github.sync._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure

object FilterManagerSpec {
  /** Timeout when waiting for future results. */
  private val timeout = 1.second

  /**
    * Constant for an element that can be used when no element-specific checks
    * are executed.
    */
  private val Element = FsFolder("", 0)

  /**
    * Waits for the given future to complete and returns the result.
    *
    * @param future the future
    * @tparam A the type of the future
    * @return the result of the future
    */
  private def futureResult[A](future: Future[A]): A =
    Await.result(future, timeout)
}

/**
  * Test class for ''FilterManager''.
  */
class FilterManagerSpec extends FlatSpec with Matchers {

  import FilterManagerSpec._

  /**
    * Expects a failed future from a parsing operation. It is checked whether
    * the future is actually failed with an ''IllegalArgumentException'' that
    * has a specific error message.
    *
    * @param future the future to be checked
    * @param msg    text to be expected in the exception message
    */
  private def expectParsingFailure(future: Future[_], msg: String): Unit = {
    Await.ready(future, timeout)
    future.value match {
      case Some(Failure(exception)) =>
        exception shouldBe a[IllegalArgumentException]
        exception.getMessage should include(msg)
      case r =>
        fail("Unexpected result: " + r)
    }
  }

  /**
    * Checks that a given filter expression is rejected.
    *
    * @param expression the expression to be checked
    */
  private def expectInvalidFilterExpression(expression: String): Unit = {
    val futResult = FilterManager.parseFilterOption(List(expression), ActionOverride, Nil)
    expectParsingFailure(futResult, expression)
  }

  /**
    * Checks whether a filter accepts and rejects operations as expected.
    *
    * @param filter     the filter to be checked
    * @param opAccepted the operation to be accepted
    * @param opRejected the operation to be rejected
    */
  private def checkFilter(filter: SyncOperationFilter, opAccepted: => SyncOperation,
                          opRejected: => SyncOperation): Unit = {
    filter(opAccepted) shouldBe true
    filter(opRejected) shouldBe false
  }

  /**
    * Helper method to check whether a single filter expression is correctly
    * processed. The method invokes the filter manager to parse a list with the
    * given single expression. Then it invokes the result with the check
    * operations.
    *
    * @param expression    the filter expression to be parsed
    * @param opAccepted    the operation to be accepted
    * @param opRejected    the operation to be rejected
    * @param commonFilters the list with common filters
    * @return the resulting list with parsed filters
    */
  private def checkParsedFilterExpression(expression: String, opAccepted: => SyncOperation,
                                          opRejected: => SyncOperation,
                                          commonFilters: List[SyncOperationFilter] = Nil):
  List[SyncOperationFilter] = {
    val result = futureResult(FilterManager.parseFilterOption(List(expression), ActionCreate,
      commonFilters))
    val filterExpressions = result(ActionCreate)
    val filter = filterExpressions.head
    checkFilter(filter, opAccepted, opRejected)
    filterExpressions
  }

  /**
    * Helper method to check whether valid filter parameters can be parsed. The
    * method processes the given map with filter expressions. It then checks
    * whether the resulting filter data accepts and rejects the expected
    * options. It is also checked whether all filter parameters have been
    * removed from the parameters map.
    *
    * @param filterArgs  the map with filter arguments
    * @param acceptedOps options expected to be accepted
    * @param rejectedOps options expected to be rejected
    */
  private def checkParseFilterArguments(filterArgs: Map[String, Iterable[String]],
                                        acceptedOps: List[SyncOperation],
                                        rejectedOps: List[SyncOperation]): Unit = {
    val otherParam = "foo" -> List("bar")
    val args = filterArgs + otherParam
    val expNextArgs = Map(otherParam)
    val (nextArgs, filterData) = futureResult(FilterManager.parseFilters(args))
    acceptedOps foreach (op => FilterManager.applyFilter(op, filterData) shouldBe true)
    rejectedOps foreach (op => FilterManager.applyFilter(op, filterData) shouldBe false)
    nextArgs should be(expNextArgs)
  }

  "FilterManager" should "parse an empty list of filter definitions" in {
    val result = futureResult(FilterManager.parseFilterOption(Nil, ActionCreate, Nil))

    result should be(Map(ActionCreate -> Nil))
  }

  it should "return a failed future for an unknown filter expression" in {
    expectInvalidFilterExpression("not supported filter expression")
  }

  it should "add the common filters to the result when parsing filter expressions" in {
    val commonFilters = List[SyncOperationFilter](_ => true, op => op.level == 0)
    val result = FilterManager.parseFilterOption(Nil, ActionRemove, commonFilters)

    futureResult(result) should be(Map(ActionRemove -> commonFilters))
  }

  it should "parse a min-level filter expression" in {
    val Expression = "min-level:1"
    val opAccepted = SyncOperation(Element, ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opAccepted.copy(level = 0))
  }

  it should "support = as expression separator" in {
    val Expression = "min-level=2"
    val opAccepted = SyncOperation(Element, ActionCreate, 2)

    checkParsedFilterExpression(Expression, opAccepted, opAccepted.copy(level = 1))
  }

  it should "only accept a numeric min-level" in {
    expectInvalidFilterExpression("min-level:nonNumber")
  }

  it should "add the common filters to parsed filter expressions" in {
    val Expression = "min-level:1"
    val filter1: SyncOperationFilter = _ => true
    val filter2: SyncOperationFilter = op => op.level == 0
    val commonFilters = List[SyncOperationFilter](filter1, filter2)
    val opAccepted = SyncOperation(Element, ActionCreate, 1)

    val allFilters = checkParsedFilterExpression(Expression, opAccepted,
      opAccepted.copy(level = 0), commonFilters)
    allFilters should contain allOf(filter1, filter2)
  }

  it should "parse a max-level filter expression" in {
    val Expression = "max-level:5"
    val opAccepted = SyncOperation(Element, ActionRemove, 5)
    val opRejected = SyncOperation(Element, ActionRemove, 6)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "parse all filter expressions" in {
    val commonFilter: SyncOperationFilter = op => op.element.relativeUri == "/foo"
    val Expression1 = "min-level:1"
    val Expression2 = "max-level:4"
    val opAcceptedMin = SyncOperation(Element, ActionRemove, 1)
    val opRejectedMin = SyncOperation(Element, ActionRemove, 0)
    val opAcceptedMax = SyncOperation(Element, ActionRemove, 4)
    val opRejectedMax = SyncOperation(Element, ActionRemove, 5)

    val allFilters = futureResult(FilterManager.parseFilterOption(List(Expression1, Expression2),
      ActionRemove, List(commonFilter)))(ActionRemove)
    checkFilter(allFilters(1), opAcceptedMin, opRejectedMin)
    checkFilter(allFilters.head, opAcceptedMax, opRejectedMax)
    allFilters should contain(commonFilter)
  }

  it should "accept operations if no filters are defined" in {
    val op = SyncOperation(Element, ActionCreate, 0)

    FilterManager.applyFilter(op, SyncFilterData(Map.empty)) shouldBe true
  }

  it should "filter out a sync op based on the filter configuration" in {
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(
      List("min-level:3"), ActionOverride, Nil)))
    val op = SyncOperation(Element, ActionOverride, 2)

    FilterManager.applyFilter(op, filterData) shouldBe false
  }

  it should "accept a sync op that matches all filter criteria" in {
    val Expressions = List("min-level:1", "max-level:2")
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(Expressions,
      ActionOverride, Nil)))
    val op = SyncOperation(Element, ActionOverride, 2)

    FilterManager.applyFilter(op, filterData) shouldBe true
  }

  it should "only accept a sync condition if all filter criteria are fulfilled" in {
    val Expressions = List("min-level:1", "max-level:2")
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(Expressions,
      ActionOverride, Nil)))
    val op = SyncOperation(Element, ActionOverride, 3)

    FilterManager.applyFilter(op, filterData) shouldBe false
  }

  it should "take the correct action into account when accepting sync operations" in {
    val Expressions = List("min-level:1", "max-level:2")
    val filterData = SyncFilterData(futureResult(FilterManager.parseFilterOption(Expressions,
      ActionOverride, Nil)))
    val op = SyncOperation(Element, ActionCreate, 3)

    FilterManager.applyFilter(op, filterData) shouldBe true
  }

  it should "parse arguments without filter expressions" in {
    val expData = SyncFilterData(Map(ActionCreate -> Nil, ActionOverride -> Nil,
      ActionRemove -> Nil))
    val (_, filterData) = futureResult(FilterManager.parseFilters(Map.empty))

    filterData should be(expData)
  }

  it should "parse arguments with valid filter expressions" in {
    val args = Map(FilterManager.ArgCreateFilter -> List("min-level:1", "max-level:2"),
      FilterManager.ArgOverrideFilter -> List("min-level:2", "max-level:4"),
      FilterManager.ArgRemoveFilter -> List("min-level:3"))
    val acceptedOps = List(SyncOperation(Element, ActionCreate, 1),
      SyncOperation(Element, ActionOverride, 4),
      SyncOperation(Element, ActionRemove, 3))
    val rejectedOps = List(SyncOperation(Element, ActionCreate, 0),
      SyncOperation(Element, ActionCreate, 3),
      SyncOperation(Element, ActionOverride, 5),
      SyncOperation(Element, ActionRemove, 2))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "parse the argument with common filter expressions" in {
    val args = Map(FilterManager.ArgCommonFilter -> List("min-level:2", "max-level:4"),
      FilterManager.ArgRemoveFilter -> List("min-level:3", "max-level=3"))
    val acceptedOps = List(SyncOperation(Element, ActionCreate, 2),
      SyncOperation(Element, ActionOverride, 4),
      SyncOperation(Element, ActionRemove, 3))
    val rejectedOps = List(SyncOperation(Element, ActionCreate, 1),
      SyncOperation(Element, ActionOverride, 5),
      SyncOperation(Element, ActionRemove, 2))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "detect invalid expressions when parsing filter parameters" in {
    val InvalidExpression = "in-valid-filter:true"
    val args = Map(FilterManager.ArgCommonFilter -> List("min-level:2", "max-level:4"),
      FilterManager.ArgRemoveFilter -> List(InvalidExpression, "max-level=3"))

    val futParsed = FilterManager.parseFilters(args)
    expectParsingFailure(futParsed, InvalidExpression)
  }

  it should "support a filter to disable specific action types" in {
    val args = Map(FilterManager.ArgActionFilter -> List("actioncreate", "actionoverride"))
    val acceptedOps = List(SyncOperation(Element, ActionCreate, 1),
      SyncOperation(Element, ActionOverride, 0))
    val rejectedOps = List(SyncOperation(Element, ActionRemove, 0))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "ignore case for action type filters" in {
    val args = Map(FilterManager.ArgActionFilter -> List("actionCreate", "ActionREMOVE"))
    val acceptedOps = List(SyncOperation(Element, ActionCreate, 1),
      SyncOperation(Element, ActionRemove, 0))
    val rejectedOps = List(SyncOperation(Element, ActionOverride, 0))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "support comma-separated action type filters" in {
    val args = Map(FilterManager.ArgActionFilter -> List("actionCreate , ActionOverride"))
    val acceptedOps = List(SyncOperation(Element, ActionCreate, 1),
      SyncOperation(Element, ActionOverride, 0))
    val rejectedOps = List(SyncOperation(Element, ActionRemove, 0))

    checkParseFilterArguments(args, acceptedOps, rejectedOps)
  }

  it should "handle invalid action type names in the action type filter" in {
    val invalidTypes = List("unknown_type", "other_unknown_type")
    val args = Map(FilterManager.ArgActionFilter -> ("actionCreate" :: invalidTypes))

    expectParsingFailure(FilterManager.parseFilters(args), invalidTypes.mkString(","))
  }

  it should "parse a simple exclude filter expression" in {
    val Expression = "exclude:/foo"
    val opAccepted = SyncOperation(FsFolder("/bar", 0), ActionCreate, 1)
    val opRejected = SyncOperation(FsFolder("/foo", 0), ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support ? characters in exclusion filters" in {
    val Expression = "exclude:/?oo"
    val opAccepted = SyncOperation(FsFolder("/fof", 0), ActionCreate, 1)
    val opRejected = SyncOperation(FsFolder("/hoo", 0), ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support * characters in exclusion filters" in {
    val Expression = "exclude:*/target/*"
    val opAccepted = SyncOperation(FsFolder("/target-folder/test", 0), ActionCreate, 1)
    val opRejected = SyncOperation(FsFolder("/test/target/sub", 0), ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "quote regular glob patterns in filter expressions" in {
    val Expression = "exclude:/foo(1)-(2)"
    val opAccepted = SyncOperation(FsFolder("/foo(1)-(3)", 0), ActionCreate, 1)
    val opRejected = SyncOperation(FsFolder("/foo(1)-(2)", 0), ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "match exclusions case insensitive" in {
    val Expression = "exclude:*/target/*"
    val opAccepted = SyncOperation(FsFolder("/test/targetTest/test", 0), ActionCreate, 1)
    val opRejected = SyncOperation(FsFolder("/test/TARGET/test", 0), ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }

  it should "support inclusion filters" in {
    val Expression = "include:*/tar?et/*-bak"
    val opAccepted = SyncOperation(FsFolder("/foo/TARSET/more/test-bak", 0), ActionCreate, 1)
    val opRejected = SyncOperation(FsFolder("/hoo/targetDir/test-bak", 0), ActionCreate, 1)

    checkParsedFilterExpression(Expression, opAccepted, opRejected)
  }
}
