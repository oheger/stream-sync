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

package com.github.sync.protocol.local

import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.github.cloudfiles.core.http.HttpRequestSender
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, HttpRequestSenderFactory, Spawner}
import com.github.cloudfiles.localfs.LocalFileSystem
import com.github.sync.protocol.config.FsStructureConfig
import org.mockito.ArgumentMatchers.{anyString, eq => argEq}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.nio.file.Paths
import java.time.ZoneId
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object LocalProtocolCreatorSpec {
  /** The root path of the test local file system as string. */
  private val RootPathStr = "/data/root"
}

/**
  * Test class for ''LocalProtocolCreator''.
  */
class LocalProtocolCreatorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import LocalProtocolCreatorSpec._

  /**
    * Returns a new test creator instance.
    *
    * @return the initialized test instance
    */
  private def newCreator(): LocalProtocolCreator = {
    val exec = mock[ExecutionContext]
    new LocalProtocolCreator(exec)
  }

  "LocalProtocolCreator" should "create a correct file system" in {
    val config = FsStructureConfig(None)
    val creator = newCreator()

    creator.createFileSystem(RootPathStr, config, Timeout(10.seconds)) match {
      case lfs: LocalFileSystem =>
        val lfsConfig = lfs.config
        lfsConfig.basePath should be(Paths.get(RootPathStr))
        lfsConfig.sanitizePaths shouldBe true
        lfsConfig.executionContext should be(creator.executionContext)
      case fs => fail("Unexpected file system: " + fs)
    }
  }

  it should "create a correct converter" in {
    val optZone = Some(ZoneId.of("UTC+02:00"))
    val config = FsStructureConfig(optZone)
    val creator = newCreator()

    creator.createConverter(config) match {
      case conv: LocalProtocolConverter =>
        conv.optTimeZone should be(optZone)
      case c => fail("Unexpected converter: " + c)
    }
  }

  it should "return a dummy as the HTTP sender actor" in {
    val spawner = mock[Spawner]
    val factory = mock[HttpRequestSenderFactory]
    val mockActor = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val senderConfig = mock[HttpRequestSenderConfig]
    when(factory.createRequestSender(argEq(spawner), anyString(), argEq(senderConfig))).thenReturn(mockActor)
    val creator = newCreator()

    creator.createHttpSender(spawner, factory, RootPathStr, FsStructureConfig(None), senderConfig) should be(mockActor)
  }
}
