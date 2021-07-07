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

package com.github.sync.impl

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.pattern.StatusReply
import akka.actor.typed.scaladsl.AskPattern._
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.github.cloudfiles.core.http.factory.Spawner
import com.github.sync.SyncTypes.{ActionCreate, ActionRemove, FsFolder, SyncOperation, SyncOperationResult}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * A module that produces a flow stage in the sync stream that applies sync
  * operations against the destination structure based on a
  * [[ProtocolOperationHandler]].
  *
  * The stage expects ''SyncOperation'' objects as input. It passes these
  * objects to its associated [[ProtocolOperationHandler]]. It then returns a
  * [[SyncOperationResult]] with the result of the operation.
  *
  * This stage also implements error handling. If the execution of the sync
  * operation fails, the failed future is mapped to a result that contains the
  * original exception.
  *
  * Sync operations are executed in parallel; the degree of parallelism is
  * determined by the size of the HTTP connection pool (as each operation
  * typically requires an HTTP request). It is, however, not possible to run
  * arbitrary operations in parallel. For instance, if there are operations to
  * create a folder and to upload files to this new folder, the uploads can
  * only be performed after the folder creation is complete. Or an operation to
  * delete a folder must be delayed until other operations that delete elements
  * in this folder are done. (The order in which operations arrive at this
  * stage adheres to those constraints; but when executing operations in
  * parallel, there can be race conditions.) Therefore, this stage keeps track
  * on the operations that are currently executed and delays some operations if
  * they depend on the completion of others. To manage the dynamic state
  * required for this book-keeping, an actor is created temporarily.
  */
object ProtocolOperationHandlerStage {
  /** The configuration property for the size of the connection pool. */
  private val PropMaxConnections = "akka.http.host-connection-pool.max-connections"

  /**
    * A base trait for the hierarchy of commands supported by the internal
    * actor to execute sync operations.
    */
  private[impl] sealed trait OperationHandlerCommand

  /**
    * A command to the internal actor that triggers the execution of a sync
    * operation.
    *
    * @param op      the operation to execute
    * @param replyTo the reference to send the response to
    */
  private case class ExecuteOperationCommand(op: SyncOperation, replyTo: ActorRef[StatusReply[SyncOperation]])
    extends OperationHandlerCommand

  /**
    * A command to tell the execution handler actor that a sync operation has
    * been completed.
    *
    * @param op the operation affected
    */
  private case class OperationCompletedCommand(op: SyncOperation) extends OperationHandlerCommand

  /**
    * A command to tell the execution handler actor that it should stop itself.
    */
  private case object CloseActorCommand extends OperationHandlerCommand

  /**
    * A data class to represent the state of the operation handler actor.
    *
    * It stores the actual [[ProtocolOperationHandler]] and the (critical)
    * operations that are currently executed that might block other operations.
    *
    * @param handler       the object to handle operations
    * @param opsInProgress the operations currently in progress
    * @param pending       the operations waiting for their execution
    */
  private case class ExecutionState(handler: ProtocolOperationHandler,
                                    opsInProgress: Set[SyncOperation],
                                    pending: List[ExecuteOperationCommand]) {
    /**
      * Returns a new instance that has the given operation in the set of
      * operations in progress.
      *
      * @param op the operation that is in progress
      * @return the updated instance
      */
    def operationStarted(op: SyncOperation): ExecutionState =
      copy(opsInProgress = opsInProgress + op)

    /**
      * Returns a tuple with a new instance that does not longer list the
      * specified operation as in progress and all the operations that have
      * been pending.
      *
      * @param op the operation that is completed
      * @return the updated instance
      */
    def operationCompleted(op: SyncOperation): (ExecutionState, List[ExecuteOperationCommand]) =
      (copy(opsInProgress = opsInProgress - op, pending = Nil), pending)

    /**
      * Returns a new instance that lists the given command as pending.
      *
      * @param command the pending command
      * @return the updated instance
      */
    def operationPending(command: ExecuteOperationCommand): ExecutionState =
      copy(pending = command :: pending)
  }

  /**
    * Returns a flow stage that processes [[SyncOperation]] objects using the
    * specified ''ProtocolOperationHandler''. The parallelism for asynchronous
    * operations is set to the size of the HTTP connection pool, as each
    * operation requires an HTTP request. An internal actor is used to forward
    * parallel sync operations to the handler in a controlled way.
    *
    * @param handler             the ''ProtocolOperationHandler''
    * @param spawner             the object to create the internal actor
    * @param optHandlerActorName an optional name for the internal actor
    * @param system              the actor system
    * @param timeout             the timeout for operation executions
    * @return the flow stage
    */
  def apply(handler: ProtocolOperationHandler, spawner: Spawner, optHandlerActorName: Option[String] = None)
           (implicit system: ActorSystem[_], timeout: Timeout): Flow[SyncOperation, SyncOperationResult, NotUsed] = {
    implicit val ec: ExecutionContext = system.executionContext
    val parallelism = system.settings.config.getInt(PropMaxConnections)
    val handlerActor = spawner.spawn(operationHandlerActor(ExecutionState(handler, Set.empty, Nil)),
      optHandlerActorName)

    Flow[SyncOperation].mapAsync(parallelism) { op =>
      handlerActor.askWithStatus(ref => ExecuteOperationCommand(op, ref))
        .map {
          SyncOperationResult(_, None)
        } recover {
        case e => SyncOperationResult(op, Some(e))
      }
    } via new CleanupStage[SyncOperationResult](() => handlerActor ! CloseActorCommand)
  }

  /**
    * The handler function of an internal actor that takes care of the
    * execution of sync operations in a controlled way. This actor deals with
    * the dependencies between operations and makes sure that an operation is
    * only executed after all the operations it depends on are completed.
    *
    * @param state the current state of this actor
    * @return the behavior of the actor
    */
  private def operationHandlerActor(state: ExecutionState): Behavior[OperationHandlerCommand] =
    Behaviors.receivePartial {
      case (ctx, cmd: ExecuteOperationCommand) =>
        operationHandlerActor(handleExecutionCommand(ctx, state, cmd))

      case (ctx, OperationCompletedCommand(operation)) =>
        val (updateState, pending) = state.operationCompleted(operation)
        val nextState = pending.foldRight(updateState) { (cmd, state) =>
          handleExecutionCommand(ctx, state, cmd)
        }
        operationHandlerActor(nextState)

      case (_, CloseActorCommand) =>
        Behaviors.stopped
    }

  /**
    * Handles a command to execute an operation. Based on the current execution
    * state, it is checked whether the operation can be executed immediately or
    * needs to wait until a blocking operation is completed.
    *
    * @param ctx   the actor context
    * @param state the current execution state
    * @param cmd   the command to execute an operation
    * @return the updated execution state
    */
  private def handleExecutionCommand(ctx: ActorContext[OperationHandlerCommand], state: ExecutionState,
                                     cmd: ExecuteOperationCommand): ExecutionState =
    cmd.op match {
      case SyncOperation(elem, ActionCreate, level, _, _, _) =>
        handleIfPossible(ctx, state, cmd, notifyOnComplete = elem.isInstanceOf[FsFolder]) {
          case SyncOperation(_: FsFolder, ActionCreate, otherLevel, _, _, _) if otherLevel < level => true
          case _ => false
        }

      case SyncOperation(_: FsFolder, ActionRemove, level, _, _, _) =>
        handleIfPossible(ctx, state, cmd, notifyOnComplete = true) {
          case SyncOperation(_, ActionRemove, otherLevel, _, _, _) if otherLevel > level => true
          case _ => false
        }

      case SyncOperation(_, ActionRemove, _, _, _, _) =>
        handleOperation(ctx, state, cmd, notifyOnComplete = true)

      case _ => handleOperation(ctx, state, cmd, notifyOnComplete = false)
    }

  /**
    * Handles the given operation. This means that the operation is passed to
    * the handler, and, based on the outcome, a result is sent to the caller.
    * Optionally, a message is sent to this actor when the operation is
    * complete. This is used to check whether there are pending operations that
    * can now be executed.
    *
    * @param ctx              the actor context
    * @param state            the current execution state
    * @param command          the command to execute an operation
    * @param notifyOnComplete flag whether a notification on completion is
    *                         desired
    * @return the updated state
    */
  private def handleOperation(ctx: ActorContext[OperationHandlerCommand], state: ExecutionState,
                              command: ExecuteOperationCommand, notifyOnComplete: Boolean):
  ExecutionState = {
    implicit val ec: ExecutionContext = ctx.executionContext
    val futExecute = state.handler.execute(command.op)
    val (futComplete, next) = if (notifyOnComplete)
      (futExecute.andThen(_ => ctx.self ! OperationCompletedCommand(command.op)), state.operationStarted(command.op))
    else (futExecute, state)

    futComplete onComplete {
      case Success(_) => command.replyTo ! StatusReply.success(command.op)
      case Failure(exception) => command.replyTo ! StatusReply.error(exception)
    }

    next
  }

  /**
    * Checks whether the given operation can now be handled based on the check
    * function provided. If this is not the case, the operation is added to the
    * list of pending operations.
    *
    * @param ctx              the actor context
    * @param state            the current execution state
    * @param command          the command to execute an operation
    * @param notifyOnComplete flag whether a notification on completion is
    *                         desired
    * @param check            the function to check for blocking operations
    * @return the updated state
    */
  private def handleIfPossible(ctx: ActorContext[OperationHandlerCommand], state: ExecutionState,
                               command: ExecuteOperationCommand, notifyOnComplete: Boolean)
                              (check: SyncOperation => Boolean): ExecutionState =
    state.opsInProgress.find(check) match {
      case Some(_) => state.operationPending(command)
      case None => handleOperation(ctx, state, command, notifyOnComplete)
    }
}
