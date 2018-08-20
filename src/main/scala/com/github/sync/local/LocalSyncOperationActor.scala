/*
 * Copyright 2018 The Developers Team.
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

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
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
  * @param srcPath                the path to the source structure
  * @param dstPath                the path to the destination structure
  * @param blockingDispatcherName name of a dispatcher for blocking operations
  */
class LocalSyncOperationActor(srcPath: Path, dstPath: Path, blockingDispatcherName: String)
  extends Actor with ActorLogging {
  /** Child actor responsible for executing sync operations. */
  private var executorActor: ActorRef = _

  override def preStart(): Unit = {
    val sourceResolver = new LocalUriResolver(srcPath)
    val destinationResolver = new LocalUriResolver(dstPath)
    executorActor = context.actorOf(Props(classOf[OperationExecutorActor], sourceResolver,
      destinationResolver).withDispatcher(blockingDispatcherName))
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
  * @param sourceResolver      the resolver for the source structure
  * @param destinationResolver the resolver for the destination structure
  */
class OperationExecutorActor(sourceResolver: LocalUriResolver,
                             destinationResolver: LocalUriResolver) extends Actor with
  ActorLogging {
  /** The object to materialize streams. */
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  import context.dispatcher

  override def receive: Receive = {
    case op@SyncOperation(file: FsFile, action, _)
      if action == ActionCreate || action == ActionOverride =>
      val source = FileIO.fromPath(resolveInSource(file))
      val destPath = resolveInDestination(file)
      val sink = FileIO.toPath(destPath)
      val client = sender()
      source.runWith(sink) map { _ =>
        Files.setLastModifiedTime(destPath, FileTime from file.lastModified)
      } onComplete {
        case Success(_) =>
          client ! op
        case Failure(exception) =>
          log.error("Failed copy operation {}!", exception)
      }

    case op@SyncOperation(element, ActionCreate, _) =>
      Files.createDirectory(resolveInDestination(element))
      sender ! op

    case op@SyncOperation(element, ActionRemove, _) =>
      Files delete resolveInDestination(element)
      sender ! op
  }

  /**
    * Convenience method to resolve the given element in the source
    * structure.
    *
    * @param element the element to be resolved
    * @return the source path for this element
    */
  private def resolveInSource(element: FsElement): Path =
    resolveElement(sourceResolver, element)

  /**
    * Convenience method to resolve the given element in the destination
    * structure.
    *
    * @param element the element to be resolved
    * @return the destination path for this element
    */
  private def resolveInDestination(element: FsElement): Path =
    resolveElement(destinationResolver, element)

  /**
    * Resolves the given element using the specified resolver. Note that the
    * ''Try'' instance from the [[LocalUriResolver]] is directly queried via
    * ''get()''. If it failed, this will cause an exception causing the current
    * operation to fail and this actor to be restarted.
    *
    * @param resolver the resolver
    * @param element  the element to be resolved
    * @return the destination path for this element
    */
  private def resolveElement(resolver: LocalUriResolver, element: FsElement): Path =
    resolver.resolve(element).get
}
