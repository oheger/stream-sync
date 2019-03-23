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

package com.github.sync.impl

import com.github.sync.SourceFileProvider
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Test class for ''CryptAwareSourceFileProvider''. This class only tests a
  * subset of the functionality implemented by this class. The remaining part
  * is handled by integration tests.
  */
class CryptAwareSourceFileProviderSpec extends FlatSpec with Matchers with MockitoSugar {
  "A CryptAwareSourceFileProver" should "delegate the shutdown() method" in {
    val wrapped = mock[SourceFileProvider]
    val provider = new CryptAwareSourceFileProvider(wrapped, None, None)

    provider.shutdown()
    verify(wrapped).shutdown()
  }
}
