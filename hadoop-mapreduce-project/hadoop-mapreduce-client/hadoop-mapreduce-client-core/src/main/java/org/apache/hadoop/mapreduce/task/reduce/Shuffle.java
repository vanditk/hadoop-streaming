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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.crypto.SecretKey;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.SecureIOUtils;
import org.apache.hadoop.mapred.IndexRecord;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.MapOutputFile;
import org.apache.hadoop.mapred.RawKeyValueIterator;
import org.apache.hadoop.mapred.ReduceTask;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.ShuffleConsumerPlugin;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.Task.TaskReporter;
import org.apache.hadoop.mapred.TaskStatus;
import org.apache.hadoop.mapred.TaskUmbilicalProtocol;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.security.SecureShuffleUtils;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.util.CharsetUtil;
import org.mortbay.log.Log;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

@InterfaceAudience.LimitedPrivate({"MapReduce"})
@InterfaceStability.Unstable
@SuppressWarnings({"unchecked", "rawtypes"})
public class Shuffle<K, V> implements ShuffleConsumerPlugin<K, V>, ExceptionReporter {
  private static final int PROGRESS_FREQUENCY = 2000;
  private static final int MAX_EVENTS_TO_FETCH = 10000;
  private static final int MIN_EVENTS_TO_FETCH = 100;
  private static final int MAX_RPC_OUTSTANDING_EVENTS = 3000000;
  
  private ShuffleConsumerPlugin.Context context;

  private TaskAttemptID reduceId;
  private JobConf jobConf;
  private Reporter reporter;
  private ShuffleClientMetrics metrics;
  private TaskUmbilicalProtocol umbilical;
  
  private ShuffleSchedulerImpl<K,V> scheduler;
  private MergeManager<K, V> merger;
  private Throwable throwable = null;
  private String throwingThreadName = null;
  private Progress copyPhase;
  private TaskStatus taskStatus;
  private Task reduceTask; //Used for status updates
  private Map<TaskAttemptID, MapOutputFile> localMapFiles;
  private int windowPeriod=10;// seconds
  @Override
  public void init(ShuffleConsumerPlugin.Context context) {
    this.context = context;

    this.reduceId = context.getReduceId();
    this.jobConf = context.getJobConf();
    this.umbilical = context.getUmbilical();
    this.reporter = context.getReporter();
    this.metrics = new ShuffleClientMetrics(reduceId, jobConf);
    this.copyPhase = context.getCopyPhase();
    this.taskStatus = context.getStatus();
    this.reduceTask = context.getReduceTask();
    this.localMapFiles = context.getLocalMapFiles();
    
    scheduler = new ShuffleSchedulerImpl<K, V>(jobConf, taskStatus, reduceId,
        this, copyPhase, context.getShuffledMapsCounter(),
        context.getReduceShuffleBytes(), context.getFailedShuffleCounter());
    merger = createMergeManager(context);
       
    this.windowPeriod=this.jobConf.getInt("mapred.streaming.window.period.sec.", 10);
  }
  
  protected MergeManager<K, V> createMergeManager(
      ShuffleConsumerPlugin.Context context) {
    return new MergeManagerImpl<K, V>(reduceId, jobConf, context.getLocalFS(),
        context.getLocalDirAllocator(), reporter, context.getCodec(),
        context.getCombinerClass(), context.getCombineCollector(), 
        context.getSpilledRecordsCounter(),
        context.getReduceCombineInputCounter(),
        context.getMergedMapOutputsCounter(), this, context.getMergePhase(),
        context.getMapOutputFile());
  }
  

  @Override
  public RawKeyValueIterator run() throws IOException, InterruptedException {
	  
	  // Scale the maximum events we fetch per RPC call to mitigate OOM issues
    // on the ApplicationMaster when a thundering herd of reducers fetch events
    // TODO: This should not be necessary after HADOOP-8942
    int eventsPerReducer = Math.max(MIN_EVENTS_TO_FETCH,
        MAX_RPC_OUTSTANDING_EVENTS / jobConf.getNumReduceTasks());
    int maxEventsToFetch = Math.min(MAX_EVENTS_TO_FETCH, eventsPerReducer);

    // Start the map-completion events fetcher thread
    final EventFetcher<K,V> eventFetcher = 
      new EventFetcher<K,V>(reduceId, umbilical, scheduler, this,
          maxEventsToFetch);
    eventFetcher.start();
    
    // Start the map-output fetcher threads
    boolean isLocal = localMapFiles != null;
    final int numFetchers = isLocal ? 1 :
      jobConf.getInt(MRJobConfig.SHUFFLE_PARALLEL_COPIES, 5);
    //Modification by Navya: we need only 1 fetcher for 1 mapper case:
//    final int numFetchers =1; //commented by pratik: multiple mappers now possible!!
    Fetcher<K,V>[] fetchers = new Fetcher[numFetchers];
    if (isLocal) {
      fetchers[0] = new LocalFetcher<K, V>(jobConf, reduceId, scheduler,
          merger, reporter, metrics, this, reduceTask.getShuffleSecret(),
          localMapFiles);
      fetchers[0].start();
    } else {
      for (int i=0; i < numFetchers; ++i) {
        fetchers[i] = new Fetcher<K,V>(jobConf, reduceId, scheduler, merger, 
                                       reporter, metrics, this, 
                                       reduceTask.getShuffleSecret());
        fetchers[i].start();
      }
    }
    //pratik: time to get the windows in place 
//    for (Fetcher<K,V> fetcher : fetchers) {
    long startTime=0,endTime=0;
    int execTimeSec=0;
    	while(true){
    		RawKeyValueIterator iter = null;
    		//Log.info("navya. Shuffle Waiting for Map output");
    		//Modification by Navya: need the lock for the solo fetcher
 /*   		synchronized (fetcher){ //commented by pratik
    			fetcher.setMapOutput(null);
    			fetcher.wait();
    			iter = fetcher.getMapOutput();
    			
    		}*/
    		Log.info("Sleeping for windowPeriod:"+windowPeriod);
    		Thread.sleep(1*windowPeriod*1000 - execTimeSec); // sleep for 10 sec to get output
    		if(merger.hasInMemoryMapOutputs()){
    			startTime = System.currentTimeMillis();
	    		Log.info("start merge");
	    		// Vandit. Start merger now!
	    		iter = merger.startStreamingMerger();
	    		Log.info("Shuffle Got Map output");
	    		if(iter == null)
	    			break;
	    		ReduceTask rTask = (ReduceTask)reduceTask;
	    		try {
					rTask.runIter(jobConf,(TaskReporter)reporter,iter);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					Log.info("error occured when performing reduce.Ending the Shuffle.");
					break;
				}
	    		endTime = System.currentTimeMillis();
	    		execTimeSec =(int) (endTime-startTime);
	    		Log.info("End window.Time consumed:"+execTimeSec+" sec.");
    		}
    		else{
    			Thread.sleep(5*1000); // Vandit. Waiting for 5 secs for map started events.
    			String[] remainingMaps = scheduler.getRemainingMaps();
    			if(null == remainingMaps || remainingMaps.length == 0){
    				// Vandit. No More maps.
    				break;
    			}
    			execTimeSec=0;
    		}
    	}
    	
//      }

    // Sanity check
    synchronized (this) {
      if (throwable != null) {
        throw new ShuffleError("error in shuffle in " + throwingThreadName,
                               throwable);
      }
    }
    
//    return kvIter;
  return null;  
  }

  @Override
  public void close(){
  }

  public synchronized void reportException(Throwable t) {
    if (throwable == null) {
      throwable = t;
      throwingThreadName = Thread.currentThread().getName();
      // Notify the scheduler so that the reporting thread finds the 
      // exception immediately.
      synchronized (scheduler) {
        scheduler.notifyAll();
      }
    }
  }
  
  public static class ShuffleError extends IOException {
    private static final long serialVersionUID = 5753909320586607881L;

    ShuffleError(String msg, Throwable t) {
      super(msg, t);
    }
  }
}

