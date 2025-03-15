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

import com.github.sync.SyncTypes.FsFile
import com.github.sync.stream.IgnoreTimeDelta.extractSeconds

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

object IgnoreTimeDelta:
  /**
    * Constant for an instance that forces an exact comparison of file
    * timestamps. The delta to be ignored is set to 0.
    */
  final val Zero: IgnoreTimeDelta = new IgnoreTimeDelta(0)

  /**
    * Creates a new ''IgnoreTimeDelta'' class with the provided duration as
    * delta. Note that only seconds are accepted as delta, so the passed in
    * value is rounded downwards to seconds.
    *
    * @param delta the duration to accept as a delta
    * @return the new ''IgnoreTimeDelta'' instance with this delta
    */
  def apply(delta: FiniteDuration): IgnoreTimeDelta =
    require(delta.toSeconds >= 0, "Delta to ignore must be >= 0, but was " + delta)
    new IgnoreTimeDelta(delta.toSeconds.toInt)

  /**
    * Extracts the seconds to be compared from the given ''Instant''.
    *
    * @param time the time to process
    * @return the seconds of this time to be compared
    */
  private def extractSeconds(time: Instant): Long = time.getEpochSecond

/**
  * A data class representing a time delta that is to be ignored when comparing
  * the timestamps of two elements.
  *
  * Not all file systems store modification times with the same granularity. So
  * when comparing elements from different file systems, it could be the case
  * that the elements are considered different because of a small delta in
  * their timestamps due to the different resolution used by these file
  * systems. To fix this, small deltas in timestamps - typically in the range
  * of 1 to 2 seconds - can be ignored by sync processes. This class represents
  * the concrete delta to be ignored.
  *
  * @param deltaSec the delta in seconds to be ignored
  */
class IgnoreTimeDelta private(val deltaSec: Int) extends AnyVal:
  /**
    * Compares the passed in timestamps and returns a flag whether they are
    * different, taking the allowed delta into account.
    *
    * @param time1 timestamp 1
    * @param time2 timestamp 2
    * @return a flag whether these timestamps are considered different
    */
  def isDifferentTimes(time1: Instant, time2: Instant): Boolean =
    math.abs(extractSeconds(time1) - extractSeconds(time2)) > deltaSec

  /**
    * Compares the timestamps of the passed in files and returns a flag whether
    * they are different, taking the allowed delta into account.
    *
    * @param file1 file 1
    * @param file2 file 2
    * @return a flag whether these files have timestamps considered as
    *         different
    */
  def isDifferentFileTimes(file1: FsFile, file2: FsFile): Boolean =
    isDifferentTimes(file1.lastModified, file2.lastModified)
