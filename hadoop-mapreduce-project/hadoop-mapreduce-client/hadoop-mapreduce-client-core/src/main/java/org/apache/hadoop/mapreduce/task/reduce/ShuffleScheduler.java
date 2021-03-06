/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapreduce.task.reduce;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.mapred.TaskCompletionEvent;
import org.apache.hadoop.mapred.TaskStartedEventContent;


@InterfaceAudience.Private
@InterfaceStability.Unstable
public interface ShuffleScheduler<K,V> {

  /**
   * Wait until the shuffle finishes or until the timeout.
   * @param millis maximum wait time
   * @return true if the shuffle is done
   * @throws InterruptedException
   */
  public boolean waitUntilDone(int millis) throws InterruptedException;

  /**
   * Interpret a {@link TaskCompletionEvent} from the event stream.
   * @param tce Intermediate output metadata
   */
  public void resolve(TaskCompletionEvent tce)
    throws IOException, InterruptedException;

  
  public void close() throws InterruptedException;

  //Vandit. Added capability to resolve Started events.
  public void resolve(TaskStartedEventContent tsec);

}
