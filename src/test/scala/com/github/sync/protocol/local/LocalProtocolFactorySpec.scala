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

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.protocol.config.FsStructureConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Test class for ''LocalProtocolFactory''.
  */
class LocalProtocolFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar {
  "LocalProtocolFactory" should "correctly initialize the base class" in {
    val config = mock[FsStructureConfig]
    val senderConfig = mock[HttpRequestSenderConfig]
    val timeout = Timeout(100.seconds)
    val spawner = mock[Spawner]
    val ec = mock[ExecutionContext]
    implicit val system: ActorSystem[_] = mock[ActorSystem[_]]

    val factory = new LocalProtocolFactory(config, senderConfig, timeout, spawner, ec)
    factory.config should be(config)
    factory.httpSenderConfig should be(senderConfig)
    factory.timeout should be(timeout)
    factory.creator match {
      case c: LocalProtocolCreator =>
        c.executionContext should be(ec)
      case o => fail("Unexpected protocol creator: " + o)
    }
  }
}
