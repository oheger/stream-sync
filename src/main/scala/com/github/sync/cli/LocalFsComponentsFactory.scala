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

package com.github.sync.cli

import java.nio.file.Paths

import akka.NotUsed
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import com.github.sync.SourceFileProvider
import com.github.sync.SyncTypes.{ElementSourceFactory, FsElement, SyncOperation}
import com.github.sync.cli.SyncComponentsFactory.{ApplyStageData, DestinationComponentsFactory, SourceComponentsFactory}
import com.github.sync.impl.FolderSortStage
import com.github.sync.local.{LocalFsConfig, LocalFsElementSource, LocalSyncOperationActor, LocalUriResolver}

import scala.concurrent.ExecutionContext

/**
  * A base trait for component factories targeting the local file system.
  *
  * Here common functionality required for both source and destination
  * components is defined.
  */
trait LocalFsComponentsFactory {

  /**
    * Creates the element source for the local file system.
    *
    * @param config         the configuration for the local file system
    * @param factory        the ''ElementSourceFactory''
    * @param startFolderUri the URI of the start folder for the iteration
    * @param ec             the execution context
    * @return the element source for the local file system
    */
  def createLocalFsElementSource(config: LocalFsConfig, factory: ElementSourceFactory,
                                 startFolderUri: String = "")
                                (implicit ec: ExecutionContext): Source[FsElement, NotUsed] =
    LocalFsElementSource(config, startDirectory = startFolderUri)(factory).via(new FolderSortStage)
}

/**
  * A special factory implementation for source components if the source
  * structure is a local file system.
  *
  * @param config the file system configuration
  * @param ec     the execution context
  */
private class LocalFsSourceComponentsFactory(val config: LocalFsConfig)(implicit ec: ExecutionContext)
  extends SourceComponentsFactory with LocalFsComponentsFactory {
  override def createSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] =
    createLocalFsElementSource(config, sourceFactory)

  override def createSourceFileProvider(): SourceFileProvider =
    new LocalUriResolver(config.rootPath)
}

object LocalFsDestinationComponentsFactory {
  /** The name of the actor for local sync operations. */
  val LocalSyncOpActorName = "localSyncOperationActor"

  /** The name of the blocking dispatcher. */
  private val BlockingDispatcherName = "blocking-dispatcher"
}

/**
  * A special factory implementation for destination components if the
  * destination structure is a local file system.
  *
  * @param config  the file system configuration
  * @param timeout a timeout for operations
  * @param ec      the execution context
  * @param system  the actor system
  */
private class LocalFsDestinationComponentsFactory(val config: LocalFsConfig, val timeout: Timeout)
                                                 (implicit ec: ExecutionContext, system: ActorSystem)
  extends DestinationComponentsFactory with LocalFsComponentsFactory {

  import LocalFsDestinationComponentsFactory._

  override def createDestinationSource(sourceFactory: ElementSourceFactory): Source[FsElement, Any] =
    createLocalFsElementSource(config, sourceFactory)

  override def createPartialSource(sourceFactory: ElementSourceFactory, startFolderUri: String):
  Source[FsElement, Any] =
    createLocalFsElementSource(config, sourceFactory, startFolderUri = startFolderUri)

  override def createApplyStage(targetUri: String, fileProvider: SourceFileProvider): ApplyStageData = {
    implicit val askTimeout: Timeout = timeout
    val configWithTargetUri = config.copy(rootPath = Paths.get(targetUri))
    val operationActor = system.actorOf(Props(classOf[LocalSyncOperationActor],
      fileProvider, configWithTargetUri, BlockingDispatcherName), LocalSyncOpActorName)
    val flow = Flow[SyncOperation].mapAsync(1) { op =>
      val futWrite = operationActor ? op
      futWrite.mapTo[SyncOperation]
    }
    val cleanUp = () => operationActor ! PoisonPill
    ApplyStageData(flow, cleanUp)
  }
}
