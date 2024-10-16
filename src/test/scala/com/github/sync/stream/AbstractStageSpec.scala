/*
 * Copyright 2018-2024 The Developers Team.
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

package com.github.sync.stream

import com.github.cloudfiles.core.http.UriEncodingHelper
import com.github.sync.AsyncTestHelper
import com.github.sync.SyncTypes.{FsElement, FsFile, FsFolder, SyncElementResult}
import com.github.sync.stream.LocalStateStage.ElementWithDelta
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.stage.GraphStage
import org.apache.pekko.stream.{ClosedShape, FanInShape2}
import org.apache.pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.Future

object AbstractStageSpec:
  /** A timestamp used for test files. */
  final val FileTime = Instant.parse("2021-11-11T11:11:11Z")

  /** Constant for the default ID type name. */
  final val IdTypeDefault = "_"

  /**
    * Generates the ID of a test element based on the given index. It is
    * possible to specify a string for the ID type. This can be used for
    * instance to distinguish between local or remote elements.
    *
    * @param index  the element index
    * @param idType the ID type
    * @return the ID for this test element
    */
  def elementID(index: Int, idType: String = IdTypeDefault): String = s"id$idType$index"

  /**
    * Generates the relative URI of a test element based on the given index.
    *
    * @param index     the element index
    * @param optParent an optional parent element; if defined the resulting
    *                  URI uses the parent URI as prefix
    * @return the URI for this test element
    */
  def elementUri(index: Int, optParent: Option[FsElement] = None): String =
    val path = f"/element$index%02d"
    optParent.fold(s"/data$path") { elem =>
      UriEncodingHelper.removeTrailingSeparator(elem.relativeUri) + path
    }

  /**
    * Creates a test folder based on the given index.
    *
    * @param index     the index
    * @param idType    the ID type
    * @param optParent an optional parent element
    * @return the test folder with this index
    */
  def createFolder(index: Int, idType: String = IdTypeDefault, optParent: Option[FsElement] = None): FsFolder =
    FsFolder(elementID(index), elementUri(index, optParent), level(optParent))

  /**
    * Creates a test file based on the given index. If a delta for the time is
    * specified, the generated file time is updated accordingly. This can be
    * used to simulate that a file has been changed.
    *
    * @param index     the index
    * @param idType    the ID type
    * @param deltaTime the time delta (in seconds)
    * @return the test file with this index
    */
  def createFile(index: Int, idType: String = IdTypeDefault, deltaTime: Int = 0, optParent: Option[FsElement] = None):
  FsFile =
    FsFile(elementID(index, idType), elementUri(index, optParent), level(optParent),
      FileTime.plusSeconds(index + deltaTime),
      (index + 1) * 100)

  /**
    * Returns a ''fold'' sink that produces a list with all elements received
    * from upstream.
    *
    * @tparam T the element type of the sink
    * @return the ''Sink'' collecting the elements from the stream
    */
  def foldSink[T]: Sink[T, Future[List[T]]] =
    Sink.fold[List[T], T](List.empty[T]) { (lst, e) => e :: lst }

  /**
    * Returns the level of a test element based on the presence of a parent
    * element.
    *
    * @param optParent the optional parent element
    * @return the level of the new test element
    */
  private def level(optParent: Option[FsElement]): Int = optParent map (_.level + 1) getOrElse 1

/**
  * A base test class for custom stage implementations. The class extends the
  * various test and helper traits and provides functionality for running
  * streams with stages under test.
  *
  * @param testSystem the test actor system
  */
class AbstractStageSpec(testSystem: ActorSystem) extends TestKit(testSystem), AnyFlatSpecLike, BeforeAndAfterAll,
  Matchers, AsyncTestHelper :

  override protected def afterAll(): Unit =
    TestKit shutdownActorSystem system
    super.afterAll()

  /**
    * Runs the test stage with the sources specified and returns the resulting
    * sequence of output elements.
    *
    * @param stage   the stage to be tested
    * @param source1 the source for local elements
    * @param source2 the source for remote elements
    * @tparam IN1 the type of the first source
    * @tparam IN2 the type of the second source
    * @tparam OUT the output type of the stage
    * @return the sequence with the output generated by the test stage
    */
  protected def runStage[IN1, IN2, OUT](stage: GraphStage[FanInShape2[IN1, IN2, OUT]],
                                        source1: Source[IN1, Any],
                                        source2: Source[IN2, Any]): Seq[OUT] =
    val g = RunnableGraph.fromGraph(GraphDSL.createGraph(AbstractStageSpec.foldSink[OUT]) { implicit builder =>
      sink =>
        import GraphDSL.Implicits.*
        val syncStage = builder.add(stage)
        source1 ~> syncStage.in0
        source2 ~> syncStage.in1
        syncStage.out ~> sink
        ClosedShape
    })

    futureResult(g.run()).reverse

  /**
    * Runs the test stage with the input elements specified and returns the
    * resulting sequence of output elements
    *
    * @param stage     the stage to be tested
    * @param elements1 the elements for the first input source
    * @param elements2 the elements for the second input source
    * @tparam IN1 the type of the first source
    * @tparam IN2 the type of the second source
    * @tparam OUT the output type of the stage
    * @return the sequence with the output generated by the test stage
    */
  protected def runStage[IN1, IN2, OUT](stage: GraphStage[FanInShape2[IN1, IN2, OUT]],
                                        elements1: List[IN1],
                                        elements2: List[IN2]): Seq[OUT] =
    runStage(stage, Source(elements1), Source(elements2))
