package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.io.Writable;

@InterfaceAudience.Public
@InterfaceStability.Stable
public class TaskStartedEventContent implements Writable{
	
	private String hostURL = new String();
	private TaskAttemptID taskAttemptId= new TaskAttemptID();

	public void setHostURL(String hostUrl){
		this.hostURL = hostUrl; 
		
	}
	public String getHostUrl(){
		return hostURL;
	}
	public void setTaskAttemptId(TaskAttemptID attemptId){
		this.taskAttemptId = attemptId;
		
	}
	public TaskAttemptID getTaskAttemptId(){
		return taskAttemptId;
	}
	
	
	@Override
	public void write(DataOutput out) throws IOException {
		// TODO Auto-generated method stub
		out.writeUTF(hostURL);
		taskAttemptId.write(out);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		// TODO Auto-generated method stub
		hostURL = in.readUTF();
		taskAttemptId.readFields(in);
	}
	
}




