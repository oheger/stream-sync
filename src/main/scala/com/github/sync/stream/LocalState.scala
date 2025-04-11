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

package com.github.sync.stream

import com.github.sync.SyncTypes
import com.github.sync.SyncTypes.{FsElement, FsFolder, SyncAction, SyncOperation}
import com.github.sync.log.{ElementSerializer, SerializationSupport, SerializerStreamHelper}
import org.apache.pekko.NotUsed
import org.apache.pekko.protobufv3.internal.DescriptorProtos.FileDescriptorSet
import org.apache.pekko.stream.scaladsl.{Broadcast, FileIO, GraphDSL, RunnableGraph, Sink, Source}
import org.apache.pekko.stream.{ClosedShape, IOResult, Materializer}
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * A module providing functionality related to the local state of sync
  * processes.
  *
  * Bidirectional sync processes require information of the local elements from
  * the last process. That way, changes on local elements can be detected,
  * which is a precondition for detecting many types of conflicts. A file with
  * the local state is one input of the sync process; depending on the
  * operations performed, the state is updated during the process.
  *
  * This module offers some type definitions and helper functions for dealing
  * with local state. This includes serialization facilities, but also
  * transformations to apply sync operations on local elements.
  */
private object LocalState:
  object LocalElementState:
    /**
      * A [[SerializationSupport]] instance providing serialization support for
      * elements in the local state.
      */
    given serState(using serElem: SerializationSupport[FsElement]): SerializationSupport[LocalElementState] with
      override def deserialize(parts: IndexedSeq[String]): Try[LocalElementState] =
        serElem.deserialize(parts) flatMap { elem =>
          Try {
            val removed = parts.last == "true"
            LocalElementState(elem, removed)
          }
        }

      extension (state: LocalElementState)
        def serialize(builder: mutable.ArrayBuilder[String]): Unit =
          state.element.serialize(builder)
          builder += state.removed.toString

  /**
    * A data class to represent an element in the local state of a sync
    * process.
    *
    * The representation consists of the element with some additional metadata.
    * Elements that have been removed are still stored in the local state, but
    * marked with a flag. This is necessary to resume interrupted sync
    * processes. (If removed elements were missing in the local state, it would
    * not be clear whether they have been processed in the interrupted process
    * or not.)
    *
    * @param element the actual element
    * @param removed flag whether this element has been removed
    */
  case class LocalElementState(element: FsElement,
                               removed: Boolean)

  /** The extension of a file storing local state information. */
  final val ExtensionLocalState = ".lst"

  /**
    * The extension of a file storing local state information while the sync
    * stream is in progress. This is a temporary file, which is renamed at the
    * end of the stream.
    */
  final val ExtensionLocalStateIP = ExtensionLocalState + ".tmp"

  /**
    * The extension of a file with local state information which is created
    * temporarily while resuming an interrupted sync process.
    */
  final val ExtensionLocalStateResuming = ExtensionLocalState + ".rsm"

  /**
    * An enumeration defining the different types of local state files and
    * their extensions that can be present in the storage folder for a sync
    * stream's local state.
    */
  enum LocalStateFile(val fileExtension: String):
    case Complete extends LocalStateFile(ExtensionLocalState)
    case Interrupted extends LocalStateFile(ExtensionLocalStateIP)
    case Resuming extends LocalStateFile(ExtensionLocalStateResuming)

    /**
      * Constructs the name of the local state file of the represented type for
      * the sync stream with the given name.
      *
      * @param streamName the name of the sync stream
      * @return the name of the file of this type for this sync stream
      */
    def fileName(streamName: String): String = streamName + fileExtension
  end LocalStateFile

  /**
    * A class with information about the storage path of the local state of a
    * specific sync stream.
    *
    * Depending on the outcome of the last sync process, the local state can be
    * stored in different files (e.g. a completed sync stream vs an interrupted
    * one). The file names are derived from the name of the sync stream. Based
    * on the specified storage path, the class allows resolving the different
    * types of local state files.
    *
    * ''Note'': The class assumes that the name of the stream contains only
    * valid characters for file names; no escaping or quoting is implemented.
    *
    * @param folder     the path to store local state files
    * @param streamName the name of the current sync stream
    */
  case class LocalStateFolder(folder: Path, streamName: String):
    /**
      * Resolves the path to the local state file of the given type.
      *
      * @param file the file type
      * @return the ''Path'' pointing to this state file for the associated
      *         sync stream
      */
    def resolve(file: LocalStateFile): Path = folder.resolve(fileName(file))

    /**
      * Resolves the path to the local state file of the given type and tests
      * whether this file exists. If this is not the case, result is ''None''.
      *
      * @param file the file type
      * @return an ''Option'' with the resolved existing state file
      */
    def resolveExisting(file: LocalStateFile): Option[Path] =
      Some(resolve(file)) filter (p => Files.isRegularFile(p))

    /**
      * Renames a local state file of one type to another type. This function
      * is typically used when an operation is completed. Then a temporary file
      * can be promoted to the complete state file.
      *
      * @param sourceFile      the type of the source file
      * @param destinationFile the type of the destination file
      * @return the path of the destination file
      */
    def rename(sourceFile: LocalStateFile, destinationFile: LocalStateFile): Path =
      val source = resolve(sourceFile)
      val destination = resolve(destinationFile)
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)

    /**
      * Renames a local state file to the name representing a completed state.
      * This is a convenience function, since typically the target of a rename
      * operation is the file for the completed state.
      *
      * @param sourceFile the type of the source file
      * @return the path to the file with the completed state
      */
    def promoteToComplete(sourceFile: LocalStateFile): Path = rename(sourceFile, LocalStateFile.Complete)

    /**
      * Makes sure that the referenced folder exists. Creates it if necessary.
      * If this fails, an ''IOException'' is thrown.
      *
      * @return a reference to this object
      */
    def ensureExisting(): LocalStateFolder =
      Files.createDirectories(folder)
      this

    /**
      * Returns the name of the state file with the given type for the
      * associated stream.
      *
      * @param fileType the file type
      * @return the name of the file with this type
      */
    private def fileName(fileType: LocalStateFile): String = streamName + fileType.fileExtension
  end LocalStateFolder

  /** A set with the action types that have an affect of the local state. */
  private val ActionsAffectingLocalState = Set(SyncAction.ActionLocalCreate, SyncAction.ActionLocalOverride,
    SyncAction.ActionLocalRemove)

  /**
    * An element that is used as fallback if no last element from an
    * interrupted stream can be obtained. This element has properties, so that
    * all elements occurring in practice are considered greater.
    */
  private val UndefinedLastElement = FsFolder("", "", 0)

  /** The logger. */
  private val log = LoggerFactory.getLogger(LocalState.getClass)

  extension (operation: SyncOperation)

    /**
      * Returns a flag whether this ''SyncOperation'' affects the local
      * element state.
      */
    def affectsLocalState: Boolean = ActionsAffectingLocalState(operation.action)

  /**
    * Constructs a ''Sink'' for the updated local element state of a sync 
    * stream. The state is stored in the given folder in a file whose name is 
    * derived from the stream name. The materialized value of the sink is a 
    * ''Future'' with the path to this file. The path components to this file
    * are created automatically if necessary. Because of that the operation can
    * fail. While the stream is ongoing, the updated local state is written to 
    * a temporary file. Only after completion of the process, this file is 
    * promoted to the new local state file. That way, it is possible to detect
    * that a stream was interrupted and to resume it at the very same position.
    *
    * @param stateFolder  refers to the folder containing state files
    * @param promoteState flag whether the temporary state file should be moved
    *                     to the final state after the stream completed
    * @param ec           the execution context
    * @return a ''Future'' with the sink to update the local element state
    */
  def constructLocalStateSink(stateFolder: LocalStateFolder, promoteState: Boolean = true)
                             (implicit ec: ExecutionContext): Future[Sink[LocalElementState, Future[Path]]] = Future {
    val targetFile = stateFolder.ensureExisting().resolve(LocalStateFile.Interrupted)
    FileIO.toPath(targetFile).contramap[LocalElementState](ElementSerializer.serialize)
      .mapMaterializedValue(_.map { _ =>
        if promoteState then stateFolder.promoteToComplete(LocalStateFile.Interrupted)
        else targetFile
      })
  }

  /**
    * Constructs a ''Source'' to load the local state of a sync process. This
    * is actually a quite tricky operation, since the function has to deal with
    * a process that has been interrupted. In this case, only a partial updated
    * state is available. The function then needs to combine the updated state
    * with the original one to come to the final input source. Therefore,
    * result is a ''Future''.
    *
    * @param stateFolder refers to the folder containing state files
    * @param ec          the execution context
    * @param mat         the object to materialize streams
    * @return a ''Future'' with the ''Source'' for the local state
    */
  def constructLocalStateSource(stateFolder: LocalStateFolder)(implicit ec: ExecutionContext, mat: Materializer):
  Future[Source[FsElement, Any]] =
    stateFolder.resolveExisting(LocalStateFile.Interrupted) match
      case None => localStateSourceFromStateFile(stateFolder)
      case Some(pathInterrupted) => localStateSourceForInterruptedStream(stateFolder, pathInterrupted)

  /**
    * Constructs a ''Source'' for the local state of a sync process if no file
    * for an interrupted process is available. In this case, the file with the
    * local state can be read directly, or - if it does not exist - result is
    * an empty source.
    *
    * @param stateFolder refers to the folder containing state files
    * @param ec          the execution context
    * @return a ''Future'' with the ''Source'' for the local state
    */
  private def localStateSourceFromStateFile(stateFolder: LocalStateFolder)(implicit ec: ExecutionContext):
  Future[Source[FsElement, Any]] = Future {
    val stateFile = stateFolder.resolve(LocalStateFile.Complete)
    if Files.isRegularFile(stateFile) then
      log.info("Reading local sync state from {}.", stateFile)
      SerializerStreamHelper.createDeserializationSource[LocalElementState](stateFile)
        .filterNot(_.removed)
        .map(_.element)
    else Source.empty
  }

  /**
    * Constructs a ''Source'' for the local state of a sync process if the last
    * process was interrupted. In this case, a file with the interrupted state
    * is available. This file needs to be merged first with the regular local
    * state before the actual source can be constructed.
    *
    * @param stateFolder     refers to the folder containing state files
    * @param pathInterrupted the path to the interrupted state file
    * @param ec              the execution context
    * @param mat             the object to materialize streams
    * @return a ''Future'' with the ''Source'' for the local state
    */
  private def localStateSourceForInterruptedStream(stateFolder: LocalStateFolder, pathInterrupted: Path)
                                                  (implicit ec: ExecutionContext, mat: Materializer):
  Future[Source[FsElement, Any]] =
    log.info("Resuming an interrupted sync process from {}.", pathInterrupted)
    val pathResuming = stateFolder.resolve(LocalStateFile.Resuming)
    for
      resumeElement <- processInterrupted(pathInterrupted, pathResuming)
      _ <- mergeLocalState(pathResuming, stateFolder.resolveExisting(LocalStateFile.Complete),
        resumeElement map (_.element))
      _ <- promoteResuming(stateFolder, pathInterrupted)
      source <- constructLocalStateSource(stateFolder)
    yield source

  /**
    * Reads the temporary state file of an interrupted stream, copies it to a
    * resuming file and extracts the last element at which the stream was
    * stopped. Result is an ''Option'', since the interrupted state file may be
    * empty.
    *
    * @param pathInterrupted the path to the interrupted state file
    * @param pathResuming    the path to the resuming state file
    * @param ec              the execution context
    * @param mat             the object to materialize streams
    * @return a ''Future'' with the optional element to resume
    */
  private def processInterrupted(pathInterrupted: Path, pathResuming: Path)
                                (implicit ec: ExecutionContext, mat: Materializer): Future[Option[LocalElementState]] =
    val source = FileIO.fromPath(pathInterrupted)
    val sinkTarget = FileIO.toPath(pathResuming)
    RunnableGraph.fromGraph(GraphDSL.createGraph(Sink.lastOption[String], sinkTarget)(lastElementToResume) {
      implicit builder =>
        (sinkLast, sinkCopy) =>
          import GraphDSL.Implicits.*
          val broadcastSink = builder.add(Broadcast[ByteString](2))
          source ~> broadcastSink ~> sinkCopy.in
          broadcastSink ~> SerializerStreamHelper.serializedElementFlow ~> sinkLast.in
          ClosedShape
    }).run()

  /**
    * Determines the last element that was processed before a sync stream was
    * interrupted. Here the materialized result for the stream that copies the
    * interrupted state to the resuming state is constructed.
    *
    * @param futLast   the future for the last serialized element
    * @param futResult the future for the write operation
    * @return the future for the deserialized last element
    */
  private def lastElementToResume(futLast: Future[Option[String]], futResult: Future[IOResult])
                                 (implicit ec: ExecutionContext): Future[Option[LocalElementState]] =
    for
      _ <- futResult
      optLastStr <- futLast
      lastElem <- deserializeLastElement(optLastStr)
    yield lastElem

  /**
    * Deserializes the string representation of the last element processed by
    * an interrupted sync stream. Handles the case that no such element has
    * been found.
    *
    * @param optLastString the optional string representation of the last
    *                      element
    * @param ec            the execution context
    * @return a ''Future'' with the optional deserialized element
    */
  private def deserializeLastElement(optLastString: Option[String])
                                    (implicit ec: ExecutionContext): Future[Option[LocalElementState]] =
    optLastString.fold(Future.successful(None)) { lastStr =>
      Future.fromTry(ElementSerializer.deserialize[LocalElementState](lastStr)) map (elem => Some(elem))
    }

  /**
    * Merges the local state with the interrupted state in a resume operation.
    * The file with the last completed local state is read, and the elements
    * after the one where processing was interrupted are appended to the file
    * with the resumed stated. (There is the corner case that the previous sync
    * stream was interrupted before any element was processed. Then the last
    * element is undefined. This means that all the elements from the last
    * completed state must be copied over.) Afterwards the resumed state
    * contains the merged local state.
    *
    * @param pathResuming   the path to the resumed state file
    * @param optPathState   the path to the local state file if existing
    * @param optLastElement the optional last element processed by the
    *                       interrupted stream
    * @param mat            the object to materialize streams
    * @return the future with the result of the IO operation
    */
  private def mergeLocalState(pathResuming: Path, optPathState: Option[Path], optLastElement: Option[FsElement])
                             (implicit mat: Materializer): Future[IOResult] =
    val lastElement = optLastElement getOrElse UndefinedLastElement
    log.debug("Resuming sync process after element {}.", lastElement.relativeUri)
    val source = optPathState.fold(Source.empty) { pathState =>
      SerializerStreamHelper.createDeserializationSource[LocalElementState](pathState)
        .filter(current => SyncTypes.compareElements(lastElement, current.element) < 0)
        .map(ElementSerializer.serialize)
    }
    val sink = FileIO.toPath(pathResuming, options = Set(StandardOpenOption.WRITE, StandardOpenOption.APPEND))
    source.runWith(sink)

  /**
    * Renames the state file created during resuming to the final local state
    * file. This function is called at the end of a resuming operation.
    *
    * @param stateFolder     refers to the folder containing state files
    * @param pathInterrupted the path to the interrupted state file
    * @param ec              the execution context
    * @return the path to the resulting local state file
    */
  private def promoteResuming(stateFolder: LocalStateFolder, pathInterrupted: Path)
                             (implicit ec: ExecutionContext): Future[Path] = Future {
    val state = stateFolder.promoteToComplete(LocalStateFile.Resuming)
    Files.delete(pathInterrupted)
    log.info("Resuming successful.")
    state
  }
