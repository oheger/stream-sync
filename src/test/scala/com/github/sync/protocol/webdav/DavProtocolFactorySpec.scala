/*
 * Copyright 2018-2025 The Developers Team.
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

package com.github.sync.protocol.webdav

import com.github.cloudfiles.core.http.factory.{HttpRequestSenderConfig, Spawner}
import com.github.sync.protocol.config.DavStructureConfig
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.Timeout
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration.*

/**
  * Test class for ''DavProtocolFactory''.
  */
class DavProtocolFactorySpec extends AnyFlatSpec with Matchers with MockitoSugar:
  "DavProtocolFactory" should "correctly initialize the base class" in {
    val config = mock[DavStructureConfig]
    val senderConfig = mock[HttpRequestSenderConfig]
    val timeout = Timeout(100.seconds)
    val spawner = mock[Spawner]
    implicit val system: ActorSystem[?] = mock[ActorSystem[?]]

    val factory = new DavProtocolFactory(config, senderConfig, timeout, spawner)
    factory.config should be(config)
    factory.timeout should be(timeout)
    factory.httpSenderConfig should be(senderConfig)
    factory.creator should be(DavProtocolCreator)
  }
