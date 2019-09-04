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

package com.github.sync

import akka.stream.scaladsl.Source
import com.github.sync.SyncTypes._
import com.github.sync.cli.SyncComponentsFactory.SourceComponentsFactory

/**
  * A special test implementation of ''SourceComponentsFactory'' that
  * simply delegates to another factory instance.
  *
  * This is useful in tests to only override specific methods of the factory
  * while keeping the default behavior of other methods.
  *
  * @param delegate the instance to delegate method calls to
  */
class DelegateSourceComponentsFactory(delegate: SourceComponentsFactory) extends SourceComponentsFactory {
  override def createSource(sourceFactory: SyncTypes.ElementSourceFactory): Source[FsElement, Any] =
    delegate.createSource(sourceFactory)

  override def createSourceFileProvider(): SourceFileProvider =
    delegate.createSourceFileProvider()
}
