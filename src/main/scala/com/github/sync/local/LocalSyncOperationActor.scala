/*
 * Copyright 2018-2020 The Developers Team.
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

package com.github.sync.local

import java.nio.file.{Files, Path}
import java.time.ZoneId

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.scaladsl.FileIO
import com.github.sync.SyncTypes._
import com.github.sync._

import scala.util.{Failure, Success}

/**
  * An actor implementation that interprets ''SyncOperation'' objects by
  * applying the corresponding changes to the local file system.
  *
  * The actor is initialized with the root paths of both the source and the
  * destination structure. It is then sent the single ''SyncOperation'' objects
  * as messages. The operations are executed, and - in case of success - sent
  * back to the caller.
  *
  * @param sourceFileProvider     the provider for files
  * @param config                 the configuration for this actor
  * @param blockingDispatcherName name of a dispatcher for blocking operations
  */
class LocalSyncOperationActor(sourceFileProvider: SourceFileProvider, config: LocalFsConfig,
                              blockingDispatcherName: String)
  extends Actor with ActorLogging {
  /** Child actor responsible for executing sync operations. */
  private var executorActor: ActorRef = _

  override def preStart(): Unit = {
    val destinationResolver = new LocalUriResolver(config.rootPath)
    executorActor = context.actorOf(Props(classOf[OperationExecutorActor], sourceFileProvider,
      destinationResolver, config.optTimeZone).withDispatcher(blockingDispatcherName))
  }

  override def receive: Receive = {
    case op: SyncOperation =>
      executorActor forward op
      log.info("OP {}", op)
  }
}

/**
  * An internally used actor that is responsible for the execution of
  * operations that might fail.
  *
  * This additional level of indirection is added to implement supervision
  * accordingly. [[LocalSyncOperationActor]] creates this actor as child and
  * delegates dangerous operations to it. When an operation fails, the actor is
  * restarted (by the default supervision strategy).
  *
  * @param sourceFileProvider  the provider for source files
  * @param destinationResolver the resolver for the destination structure
  * @param optTimeZone         an optional time zone to be applied to file timestamps
  */
class OperationExecutorActor(sourceFileProvider: SourceFileProvider,
                             destinationResolver: LocalUriResolver,
                             optTimeZone: Option[ZoneId]) extends Actor with
  ActorLogging {
  /** The actor system in implicit scope. */
  private implicit val system: ActorSystem = context.system

  import context.dispatcher

  override def receive: Receive = {
    case op@SyncOperation(file: FsFile, action, _, srcUri, dstUri)
      if action == ActionCreate || action == ActionOverride =>
      val futSource = sourceFileProvider fileSource srcUri
      val destPath = resolveInDestination(dstUri)
      val sink = FileIO.toPath(destPath)
      val client = sender()
      futSource.flatMap(_.runWith(sink)) map { _ =>
        FileTimeUtils.setLastModifiedTimeInTimeZone(destPath, file.lastModified, optTimeZone)
      } onComplete {
        case Success(_) =>
          client ! op
        case Failure(exception) =>
          log.error("Failed copy operation {}!", exception)
      }

    case op@SyncOperation(_, ActionCreate, _, _, dstUri) =>
      Files.createDirectory(resolveInDestination(dstUri))
      sender() ! op

    case op@SyncOperation(_, ActionRemove, _, _, dstUri) =>
      Files delete resolveInDestination(dstUri)
      sender() ! op
  }

  /**
    * Convenience method to resolve the given element URI in the destination
    * structure.
    *
    * @param elementUri the element to be resolved
    * @return the destination path for this element
    */
  private def resolveInDestination(elementUri: String): Path =
    resolveElement(destinationResolver, elementUri)

  /**
    * Resolves the given element URI using the specified resolver. Note that
    * the ''Try'' instance from the [[LocalUriResolver]] is directly queried
    * via ''get()''. If it failed, this will cause an exception causing the
    * current operation to fail and this actor to be restarted.
    *
    * @param resolver   the resolver
    * @param elementUri the URI of the element to be resolved
    * @return the destination path for this element
    */
  private def resolveElement(resolver: LocalUriResolver, elementUri: String): Path =
    resolver.resolve(elementUri).get
}
