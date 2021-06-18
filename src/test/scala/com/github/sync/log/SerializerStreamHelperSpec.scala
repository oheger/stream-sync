/*
 * Copyright 2018-2021 The Developers Team.
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

package com.github.sync.log

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestKit
import com.github.sync.SyncTypes._
import com.github.sync._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

/**
  * Test class for ''SerializerStreamHelper''.
  */
class SerializerStreamHelperSpec(testSystem: ActorSystem) extends TestKit(testSystem) with
  AnyFlatSpecLike with BeforeAndAfterAll with Matchers with FileTestHelper with AsyncTestHelper {
  def this() = this(ActorSystem("SerializerStreamHelperSpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    tearDownTestFile()
  }

  /**
    * Executes a stream that reads from the source and writes into the sink.
    *
    * @param source the source
    * @param sink   the sink
    * @tparam Data the data type of the source
    * @tparam Mat  the materialized type of the sink
    * @return the materialized value of the sink
    */
  private def readStream[Data, Mat](source: Source[Data, Any],
                                    sink: Sink[Data, Mat]): Mat = source.runWith(sink)

  /**
    * Generates a sink that adds the stream elements to a list (in reverse
    * order).
    *
    * @tparam E the element type
    * @return the sink to populate a list
    */
  private def listSink[E]: Sink[E, Future[List[E]]] =
    Sink.fold[List[E], E](List.empty)((lst, s) => s :: lst)

  "SerializerStreamHelper" should "create a source to read log files line-wise" in {
    val Content = "Line1\nLine2  \r\n  Line3\n\r\nLine4"
    val logFile = createDataFile(Content)
    val sink = listSink[String]

    val lines = futureResult(readStream(SerializerStreamHelper.createLogFileSource(logFile), sink))
    lines should contain theSameElementsInOrderAs List("Line4", "Line3", "Line2", "Line1")
  }

  it should "create a source to read sync operations" in {
    val op1 = SyncOperation(FsFile("fileID", "/test/file.txt", 4, Instant.now(), 256), ActionOverride, 1,
      "/some/src/uri", "/some/dest/uri", dstID = "The destination ID")
    val op2 = SyncOperation(FsFolder("folderID", "/test/folder", 2), ActionRemove, 1, "/src", "/dst", dstID = "21")
    val Content = List(op1, op2).map(op => ElementSerializer.serializeOperation(op).utf8String)
      .mkString("\n")
    val logFile = createDataFile(Content)
    val sink = listSink[SyncOperation]

    val ops = futureResult(readStream(SerializerStreamHelper.createSyncOperationSource(logFile),
      sink))
    ops should contain theSameElementsInOrderAs List(op2, op1)
  }

  it should "return the lines of a log file in a set" in {
    val Content = "Line1\nLine2  \r\n  Line3\n\r\nLine4"
    val logFile = createDataFile(Content)

    val lines = futureResult(SerializerStreamHelper.readProcessedLog(logFile))
    lines should contain theSameElementsAs List("Line4", "Line3", "Line2", "Line1")
  }
}
