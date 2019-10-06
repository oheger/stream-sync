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

package com.github.sync.cli.oauth

import java.awt.Desktop
import java.io.IOException
import java.net.URI

import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

object BrowserHandlerSpec {
  /** A test URI as string. */
  private val TestUriStr = "https://my-test-idp.org/authorize"

  /** A test URI in parsed form. */
  private val TestUri = URI.create(TestUriStr)
}

/**
  * Test class for ''BrowserHandler''.
  */
class BrowserHandlerSpec extends FlatSpec with Matchers with MockitoSugar {

  import BrowserHandlerSpec._

  "BrowserHandler" should "obtain the platform-specific Desktop if supported" in {
    val handler = BrowserHandler()

    handler.optDesktop.isDefined shouldBe Desktop.isDesktopSupported
  }

  it should "open the browser if supported" in {
    val desktop = mock[Desktop]
    when(desktop.isSupported(Desktop.Action.BROWSE)).thenReturn(true)
    val handler = new BrowserHandler(Some(desktop))

    handler.openBrowser(TestUriStr) shouldBe true
    verify(desktop).browse(TestUri)
  }

  it should "handle the case that no Desktop is available" in {
    val handler = new BrowserHandler(None)

    handler.openBrowser(TestUriStr) shouldBe false
  }

  it should "handle the case that the Browse action is not supported" in {
    val desktop = mock[Desktop]
    when(desktop.isSupported(Desktop.Action.BROWSE)).thenReturn(false)
    val handler = new BrowserHandler(Some(desktop))

    handler.openBrowser(TestUriStr) shouldBe false
    verify(desktop, never()).browse(TestUri)
  }

  it should "catch an exception when opening the browser" in {
    val desktop = mock[Desktop]
    when(desktop.isSupported(Desktop.Action.BROWSE)).thenReturn(true)
    when(desktop.browse(TestUri)).thenThrow(new IOException("Browser crashed"))
    val handler = new BrowserHandler(Some(desktop))

    handler.openBrowser(TestUriStr) shouldBe false
  }
}
