/*
 * Copyright 2018-2023 The Developers Team.
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

import com.github.sync.protocol.SyncProtocol
import com.github.sync.{ActorTestKitSupport, AsyncTestHelper}
import org.apache.pekko.stream.KillSwitches
import org.mockito.Mockito.verify
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Promise

/**
  * Test class for ''SyncProtocolHolder''. Note that the major part of the
  * functionality provided by this class is tested by integration tests.
  */
class SyncProtocolHolderSpec extends AnyFlatSpec with ActorTestKitSupport with Matchers with MockitoSugar
  with AsyncTestHelper:
  "SyncProtocolHandler" should "register a handler that closes protocols on a successful completion" in {
    val srcProtocol = mock[SyncProtocol]
    val dstProtocol = mock[SyncProtocol]
    val promise = Promise[Int]()
    val ks = KillSwitches.shared("mockKs1")
    val holder = new SyncProtocolHolder(srcProtocol, dstProtocol, ks)

    val futClose = holder.registerCloseHandler(promise.future)
    promise.success(42)
    futureResult(futClose) should be(42)
    verify(srcProtocol).close()
    verify(dstProtocol).close()
  }

  it should "register a handler that closes protocols on a failed completion" in {
    val exception = new IllegalStateException("Test Exception: Failed future.")
    val srcProtocol = mock[SyncProtocol]
    val dstProtocol = mock[SyncProtocol]
    val promise = Promise[Int]()
    val ks = KillSwitches.shared("mockKs2")
    val holder = new SyncProtocolHolder(srcProtocol, dstProtocol, ks)

    val futClose = holder.registerCloseHandler(promise.future)
    promise.failure(exception)
    expectFailedFuture[IllegalStateException](futClose) should be(exception)
    verify(srcProtocol).close()
    verify(dstProtocol).close()
  }
