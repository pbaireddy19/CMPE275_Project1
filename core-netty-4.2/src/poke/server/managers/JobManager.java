/*
 * copyright 2014, gash
 * 
 * Gash licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package poke.server.managers;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.UnsupportedEncodingException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.connect.database.Photo;
import poke.server.conf.ServerConf;
import poke.server.management.ManagementQueue;
import poke.server.management.OutboundMgmtWorker;
import poke.server.queue.PerChannelQueue;
import eye.Comm.JobBid;
import eye.Comm.JobProposal;
import eye.Comm.Management;
import eye.Comm.MgmtHeader;
import eye.Comm.Request;
import eye.Comm.RoutingPath;

/**
 * The job manager class is used by the system to hold (enqueue) jobs and can be
 * used in conjunction to the voting manager for cooperative, de-centralized job
 * scheduling. This is used to ensure leveling of the servers take into account
 * the diversity of the network.
 * 
 * @author gash
 * 
 */
public class JobManager {
	protected static Logger logger = LoggerFactory.getLogger("job");
	protected static AtomicReference<JobManager> instance = new AtomicReference<JobManager>();

	private static ServerConf conf;

	public static JobManager initManager(ServerConf conf) {
		JobManager.conf = conf;
		instance.compareAndSet(null, new JobManager());
		return instance.get();
	}

	public static JobManager getInstance() {
		// TODO throw exception if not initialized!
		return instance.get();
	}

	public JobManager() {
	}

	/**
	 * a new job proposal has been sent out that I need to evaluate if I can run
	 * it
	 * 
	 * @param req
	 *            The proposal
	 * @throws UnsupportedEncodingException 
	 */
	public void processRequest(Management mgmt) throws UnsupportedEncodingException {
		JobProposal req = mgmt.getJobPropose();
		Request reply =null;
		if (req == null)
			return;
		
		
		
		
		if(req.getNameSpace().equals("response"))
		{
			System.out.println(" I got the response from the slave --------->>"+mgmt.getHeader().getOriginator());
			
			
			
			
			
		}
		else
		{
			System.out.println("got the reqest --------------------------------->>>>>>>>>>>>>>>>>>>>>>>");
			Photo photo=new Photo();
			
			
			
			//To do 
			//reply=photo.postMsg(UUID.fromString(req.getJobId()), req.getRequest());
		JobProposal.Builder jpb = JobProposal.newBuilder();
		jpb.setJobId(req.getJobId());
		jpb.setOwnerId(1).setNameSpace("response").setWeight(5).setRequest(req.getRequest());
		jpb.setNodeId(1);
		
		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setOriginator(1);
		mhb.setTime(System.currentTimeMillis());

		RoutingPath.Builder rpb = RoutingPath.newBuilder();
		rpb.setNodeId(1);
		rpb.setTime(mhb.getTime());
		mhb.addPath(rpb);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setJobPropose(jpb.build());
		Channel ch1=ConnectionManager.getConnection(2, true);
		ch1.write(mb.build());
		
		
		//Broadcast the msg to - do 
		
		
		System.out.println("I have sent back the reply to the master !! --->>>");
		}
	}

	/**
	 * a job bid for my job
	 * 
	 * @param req
	 *            The bid
	 */
	public void processRequest(JobBid req) {
	}
}


class NewListener implements ChannelFutureListener {

	@Override
	public void operationComplete(ChannelFuture arg0) throws Exception {
		// TODO Auto-generated method stub
System.out.println("(((((((((((((((((((((((((((9Send Message))))))))))))))))))))))");
	}
	//
}
