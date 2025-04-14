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

package com.github.sync.cli

import com.github.cloudfiles.core.http.RetryAfterExtension
import com.github.cloudfiles.core.http.RetryExtension
import com.github.cloudfiles.core.http.auth.NoAuthConfig
import com.github.cloudfiles.core.http.factory.HttpRequestSenderConfig
import com.github.sync.AsyncTestHelper
import com.github.sync.cli.SyncSetup.AuthSetupFunc
import com.github.sync.oauth.SyncNoAuth
import com.github.sync.protocol.SyncProtocol
import com.github.sync.protocol.config.FsStructureConfig
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.{KillSwitch, KillSwitches}
import org.mockito.Mockito.verify
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/**
  * Test class for ''SyncProtocolHolder''. Note that the major part of the
  * functionality provided by this class is tested by integration tests.
  */
class SyncProtocolHolderSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
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

  /**
    * Creates a [[HttpRequestSenderConfig]] with default settings and the given
    * retry configuration.
    *
    * @param optRetryConfig the optional retry configuration
    * @return the resulting [[HttpRequestSenderConfig]]
    */
  private def createHttpSenderConfigForRetryConfig(optRetryConfig: Option[SyncCliStructureConfig.SyncRetryConfig]):
  HttpRequestSenderConfig =
    val authSetupFunc: AuthSetupFunc = (_, _) => Future.successful(NoAuthConfig)
    val structureConfig = SyncCliStructureConfig.StructureSyncConfig(
      structureConfig = FsStructureConfig(None),
      authConfig = SyncNoAuth,
      optRetryConfig = optRetryConfig
    )
    val ks = mock[KillSwitch]

    given ec: ExecutionContext = testKit.internalSystem.executionContext

    futureResult(SyncProtocolHolder.createHttpSenderConfig(authSetupFunc, structureConfig, ks))

  it should "create a correct HttpRequestSenderConfig if no retry configuration is provided" in {
    val httpSenderConfig = createHttpSenderConfigForRetryConfig(None)

    httpSenderConfig.retryConfig shouldBe empty
    httpSenderConfig.retryAfterConfig shouldBe empty
  }

  it should "create a correct HttpRequestSenderConfig with retry configuration options" in {
    val retryConfig = SyncCliStructureConfig.SyncRetryConfig(
      minDelay = 800.millis,
      maxDelay = 17.seconds,
      maxRetries = 11
    )
    val httpSenderConfig = createHttpSenderConfigForRetryConfig(Some(retryConfig))

    val expRetryAfterConfig = RetryAfterExtension.RetryAfterConfig(retryConfig.minDelay)
    val expBackoffConfig = RetryExtension.BackoffConfig(
      minBackoff = retryConfig.minDelay,
      maxBackoff = retryConfig.maxDelay
    )
    httpSenderConfig.retryAfterConfig should be(Some(expRetryAfterConfig))
    httpSenderConfig.retryConfig.get.optMaxTimes should be(Some(retryConfig.maxRetries))
    httpSenderConfig.retryConfig.get.optBackoff should be(Some(expBackoffConfig))
  }
