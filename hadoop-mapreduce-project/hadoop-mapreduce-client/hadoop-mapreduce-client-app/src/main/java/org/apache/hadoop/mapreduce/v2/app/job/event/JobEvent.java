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

package org.apache.hadoop.mapreduce.v2.app.job.event;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.yarn.event.AbstractEvent;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.v2.api.records.JobId;

/**
 * This class encapsulates job related events.
 *
 */
public class JobEvent extends AbstractEvent<JobEventType> implements Writable {

  private JobId jobID;

  public JobEvent(JobId jobID, JobEventType type) {
    super(type);
    this.jobID = jobID;
  }

  public JobId getJobId() {
    return jobID;
  }

public void write(DataOutput out) throws IOException {
	// TODO Auto-generated method stub
	System.out.println("JobEvent. write called");
	
}

public void readFields(DataInput in) throws IOException {
	// TODO Auto-generated method stub
	System.out.println("JobEvent. readFields called");
	
}

}
