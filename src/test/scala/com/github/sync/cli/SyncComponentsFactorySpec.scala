/*
 * Copyright 2018-2019 The Developers Team.
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

import java.nio.file.Paths
import java.time.ZoneId

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.SyncComponentsFactory.{DestinationStructureType, SourceStructureType}
import com.github.sync.local.LocalFsConfig
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

object SyncComponentsFactorySpec {
  /** A timeout value which is required as implicit parameter. */
  implicit val TestTimeout: Timeout = Timeout(10.seconds)
}

/**
  * Test class for ''SyncComponentsFactory''. This class tests only a limited
  * subset of the functionality provided by the class. The remaining part is
  * tested by integration tests.
  */
class SyncComponentsFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with AsyncTestHelper {
  def this() = this(ActorSystem("SyncComponentsFactorySpec"))

  override protected def afterAll(): Unit = {
    super.afterAll()
    TestKit shutdownActorSystem system
  }

  import SyncComponentsFactorySpec._
  import system.dispatcher

  /** An object to materialize streams; needed as implicit parameter. */
  implicit val mat: ActorMaterializer = ActorMaterializer()

  /**
    * Returns the local file system config from a source factory.
    *
    * @param sourceFactory the source factory
    * @return the local FS config from this factory
    */
  private def extractLocalFsSourceConfig(sourceFactory: SourceComponentsFactory): LocalFsConfig =
    sourceFactory match {
      case localFactory: LocalFsSourceComponentsFactory => localFactory.config
      case f => fail("Unexpected source factory: " + f)
    }

  "SyncComponentsFactory" should "create a correct file system config for the source structure" in {
    val TimeZoneId = "UTC+02:00"
    val uri = "/my/sync/dir"
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      List(TimeZoneId))
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, sourceFactory) = futureResult(syncFactory.createSourceComponentsFactory(uri, args))
    processedArgs.size should be(0)
    extractLocalFsSourceConfig(sourceFactory) should be(new LocalFsConfig(Paths.get(uri), Some(ZoneId.of(TimeZoneId))))
  }

  it should "create a correct file system config for the source structure with defaults" in {
    val uri = "/my/sync/dir"
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, sourceFactory) = futureResult(syncFactory.createSourceComponentsFactory(uri, Map.empty))
    processedArgs.size should be(0)
    extractLocalFsSourceConfig(sourceFactory) should be(new LocalFsConfig(Paths.get(uri), None))
  }

  it should "generate failure messages for all invalid parameters of a local FS config" in {
    val args = Map(SourceStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      List("invalid zone ID!"))
    val syncFactory = new SyncComponentsFactory

    val exception =
      expectFailedFuture[IllegalArgumentException](syncFactory.createSourceComponentsFactory("\u0000", args))
    exception.getMessage should include(SourceStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsPath))
    exception.getMessage should include(SourceStructureType.configPropertyName(
      SyncComponentsFactory.PropLocalFsTimeZone))
  }

  it should "create a correct file system config for the destination structure" in {
    val TimeZoneId = "UTC-02:00"
    val uri = "/my/sync/target"
    val args = Map(DestinationStructureType.configPropertyName(SyncComponentsFactory.PropLocalFsTimeZone) ->
      List(TimeZoneId))
    val syncFactory = new SyncComponentsFactory

    val (processedArgs, destFactory) = futureResult(syncFactory.createDestinationComponentsFactory(uri, args))
    processedArgs.size should be(0)
    destFactory match {
      case localFactory: LocalFsDestinationComponentsFactory =>
        localFactory.config should be(new LocalFsConfig(Paths.get(uri), Some(ZoneId.of(TimeZoneId))))
      case r => fail("Unexpected result: " + r)
    }
  }
}
