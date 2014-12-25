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
package poke.server.election;

import io.netty.channel.Channel;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.monitor.HeartMonitor;
import poke.server.conf.ServerConf;
import poke.server.management.ManagementQueue;
import poke.server.managers.ConnectionManager;
import poke.server.managers.ElectionManager;
import poke.server.managers.HeartbeatManager;
import poke.server.managers.HeartbeatPusher;
import eye.Comm.LeaderElection;
import eye.Comm.LeaderElection.ElectAction;
import eye.Comm.Management;
import eye.Comm.MgmtHeader;
import eye.Comm.RoutingPath;

/**
 * Flood Max (FM) algo is useful for cases where a ring is not formed (e.g.,
 * tree) and the organization is not ordered as in algorithms such as HS or LCR.
 * However, the diameter of the network is required to ensure deterministic
 * results.
 * 
 * Limitations: This is rather a simple (naive) implementation as it 1) assumes
 * a simple network, and 2) does not support the notion of diameter of the graph
 * (therefore, is not deterministic!). What this means is the choice of maxHops
 * cannot ensure we reach agreement.
 * 
 * Typically, a FM uses the diameter of the network to determine how many
 * iterations are needed to cover the graph. This approach can be shortened if a
 * cheat list is used to know the nodes of the graph. A lookup (cheat) list for
 * small networks is acceptable so long as the membership of nodes is relatively
 * static. For large communities, use of super-nodes can reduce propagation of
 * messages and reduce election time.
 * 
 * Alternate choices can include building a spanning tree of the network (each
 * node know must know this and for arbitrarily large networks, this is not
 * feasible) to know when a round has completed. Another choice is to wait for
 * child nodes to reply before broadcasting the next round. This waiting is in
 * effect a blocking (sync) communication. Therefore, does not really give us
 * true asynchronous behavior.
 * 
 * Is best-effort, non-deterministic behavior the best we can achieve?
 * 
 * @author gash
 * 
 */
public class FloodMaxElection implements Election
{
	protected static Logger logger = LoggerFactory.getLogger("floodmax");

	private Integer nodeId;
	private ElectionState current;
	private int maxHops = -1; // unlimited
	private ElectionListener listener;
	
	private ServerConf conf;

	
	// A list of ballot IDs received during election
		private ConcurrentLinkedQueue<Integer> electIdList = new ConcurrentLinkedQueue<Integer>();

		
		// A count for nominations received by the leader
		private int voteCount = 0;
		
		private int firstTime = 2;
	
		// Maximum ballot number in the ballot list
		private int maxBallot;
		
		// 
		// Which node id has this node currently nominated as the leader
		private int leaderNomination;
		
		// 
		// Flag - is there is leader in the cluster
		private boolean leaderFlag = false;
		
		// 
		// Which node id is the leader in this cluster
		private int leaderNode = -1;
		
		// 
		// Flag - am I the leader of the cluster
		private boolean iAmLeader = false;

		/** @brief the number of votes this server can cast */
		private int votes = 1;
	
	
	
	
	
	
	public FloodMaxElection()
	{

	}

	/**
	 * init with whoami
	 * 
	 * @param nodeId
	 */
	public FloodMaxElection(Integer nodeId)
	{
		this.nodeId = nodeId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.election.Election#setListener(poke.server.election.
	 * ElectionListener)
	 */
	@Override
	public void setListener(ElectionListener listener)
	{
		this.listener = listener;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.election.Election#process(eye.Comm.LeaderElection)
	 * 
	 * @return The Management instance returned represents the message to send
	 * on. If null, no action is required. Not a great choice to convey to the
	 * caller; can be improved upon easily.
	 */
	@Override
	public void process(Management mgmt) 
	{
		if (!mgmt.hasElection())
			return;

		LeaderElection req = mgmt.getElection();
		if (req.getExpires() <= System.currentTimeMillis()) {
			// election has expired without a conclusion?
		}

		Management rtn = null;

		if (req.getAction().getNumber() == ElectAction.DECLAREELECTION_VALUE)
		{
			// an election is declared!
			
			System.out.println("\n\n*********************************************************");
			System.out.println(" FLOOD MAX ELECTION: Election declared");
			System.out.println("   Election ID:  " + req.getElectId());
			System.out.println("   Rcv from:     Node " + mgmt.getHeader().getOriginator());
			System.out.println("   Expires:      " + new Date(req.getExpires()));
			System.out.println("   Nominates:    Node " + req.getCandidateId());
			System.out.println("   Desc:         " + req.getDesc());
			System.out.println("*********************************************************\n\n");
			
			
			// check if this is a legal election message
			// it is illegal, if an earlier leader who recovers has sent an initial election message
			// on recovering from failure, the earlier leader has to accept the current leader
			// hence we have to suppress this election by abstaining from voting
			if (req.getDesc().equals("Initial Election") && leaderFlag!=false)
			{
				Management abstainElection = getAbstainMessage();
				// Sending abstain message to the leader
				for (HeartMonitor hm : HeartbeatPusher.getInstance().getMonitors())
				{
					if (hm.getNodeId() == req.getCandidateId())
					{
						// Getting channel from Heart Monitor for a neighbor node
						Channel channel = hm.getChannel();
						// Adding message to outbound queue which will allow us to send it
						ManagementQueue.enqueueRequest(abstainElection,channel);
					}
				}
			}
			// Legal election, so process normally
			else
			{
				// add to our ballot list
				electIdList.add(Integer.valueOf(req.getElectId()));
				boolean isNew = updateCurrent(mgmt.getElection());
			}
		} else if (req.getAction().getNumber() == ElectAction.DECLAREVOID_VALUE) {
			// no one was elected, I am dropping into standby mode`
		} else if (req.getAction().getNumber() == ElectAction.DECLAREWINNER_VALUE) {
			// some node declared themself the leader
			// double check if the node who has declared itself the leader is what
			// this node had nominated in this election round
			System.out.println("DECLARE WINNER");
			System.out.println("------------CANID: " + req.getCandidateId() + "--------------");
			System.out.println("++++++++++++++++leaderNOmination:" + leaderNomination + "++++++++++");
			if (req.getCandidateId() == (leaderNomination)){
				leaderFlag = true;
				leaderNode = req.getCandidateId();
				logger.info("The leader of this cluster is node: " + req.getCandidateId());
				
				notify(true, req.getCandidateId());
				
				LeaderElection.Builder elb = LeaderElection.newBuilder();
				elb.setElectId(Integer.toString(conf.getServer().getMgmtPort() %100));
				elb.setAction(ElectAction.THELEADERIS);
				elb.setDesc("Node " + this.leaderNode + " is the leader");
				elb.setCandidateId(this.leaderNode);
				elb.setExpires(-1);
				
				MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
				mhb.setOriginator(conf.getServer().getNodeId());
				mhb.setTime(System.currentTimeMillis());

				RoutingPath.Builder rpb = RoutingPath.newBuilder();
				rpb.setNodeId(conf.getServer().getNodeId());
				rpb.setTime(mhb.getTime());
				mhb.addPath(rpb);

				

				Management.Builder mb = Management.newBuilder();
				mb.setHeader(mhb.build());
				mb.setElection(elb.build());
				
				System.out.println("BROADCASTING");
			ConnectionManager.broadcast(mb.build());
				
				

			}
			else
			{
				logger.info("Illegal DECLAREWINNER sent by node: " + req.getCandidateId());
			}
			boolean isNew = updateCurrent(mgmt.getElection());
		}
		else if (req.getAction().getNumber() == ElectAction.ABSTAIN_VALUE)
		{
			// for some reason, I decline to vote
		
			// abstain is only received by a former leader who started an illegal election
			// on receiving, the former leader sets the election related data structures to be consistent 
			// with the rest of the cluster
			leaderFlag = true;
			leaderNode = Integer.parseInt(req.getDesc());
			logger.info("I accept " + leaderNode + " as the leader of the cluster");
			
		} else if (req.getAction().getNumber() == ElectAction.NOMINATE_VALUE) {
			// Leader received nominations from everyone, then it declares itself the leader
			// After that, the leader clears its ballot list
			
			System.out.println("RECEIVED NOMINATION");
			voteCount++;
			System.out.println("**MAXBALLOT**: " + electIdList.size());
			System.out.println("*****voteCOunt:***" + voteCount);
			if (voteCount == electIdList.size()) {
				Management leaderDeclaration = getDeclareWinnerMessage();
				
				for (HeartMonitor hm : HeartbeatPusher.getInstance().getMonitors()) {
					if (hm.getPort()%100 > maxBallot) {
						// do nothingelectIdList
						}
					else {
						//ConnectionManager.getConnection(hm.getNodeId(), true).write(leaderDeclaration);
						//ConnectionManager.broadcast(leaderDeclaration);
						ManagementQueue.enqueueRequest(leaderDeclaration,ConnectionManager.getConnection(hm.getNodeId(), true));
					}
				}
				iAmLeader = true;
				leaderFlag = true;
				leaderNode = conf.getServer().getNodeId();
				electIdList.clear();
				notify(true, leaderNode);
				logger.info("I am the leader of this cluster");
			}
			boolean isNew = updateCurrent(mgmt.getElection());
		}
		
		// If the number of election messages received are equal to the 
		// number of incoming HB neighbors -> that means you have received election 
		// message from everyone, so now we can compare ballots
		System.out.println("BALLOT SIZE:" + electIdList.size());
		System.out.println("INCOMING HB SIZE: " + ConnectionManager.getNumMgmtConnections());
		if (electIdList.size() == ConnectionManager.getNumMgmtConnections()) {
			maxBallot = Collections.max(electIdList);
			if (Integer.valueOf(conf.getServer().getMgmtPort())%100 > maxBallot) {
				leaderNomination = conf.getServer().getNodeId();
				logger.info(conf.getServer().getNodeId() + ": Waiting for approval as leader");
			}
			else 
			{
			logger.info(conf.getServer().getMgmtPort()+ ": Leader identified, nominating...");
			Management identifiedLeader = getNominationMessage();
			// Sending nomination to identified leader and clearing ballot list
			// Thus, ballot list will be cleared here only on non-leader nodes
			
			
			
			
			
			for (HeartMonitor hm : HeartbeatPusher.getInstance().getMonitors()) {
				System.out.println("MAXBALLOT: " + maxBallot);
				System.out.println("BALL: " + (hm.getPort()%100));
				System.out.println("&&&&&&&&&&&: " + hm.getNodeId() + "&&&&&&&&&");
				if (hm.getPort()%100 == maxBallot) {
					System.out.println("GOT MAXIMUM BALLOT");
					// saving the nomination locally
					leaderNomination = hm.getNodeId();
					System.out.println("SAVING LEADER LOCALLY: " + leaderNomination);
					// sending the nomination to the leader
					
					//Channel ch = ConnectionManager.getConnection(hm.getNodeId(), true);
					notify(true, leaderNomination);
					//hm.getChannel().write(identifiedLeader);
					ConnectionManager.broadcast(identifiedLeader);
					//ManagementQueue.enqueueRequest(identifiedLeader,ConnectionManager.getConnection(hm.getNodeId(), true));
					electIdList.clear();
					}
			}
			
			}
		}	

		//return rtn;
	}

	public Management getNominationMessage(){
 		// Building nomination message
 		LeaderElection.Builder elb = LeaderElection.newBuilder();
 		elb.setCandidateId(conf.getServer().getNodeId());
 		elb.setElectId(String.valueOf(Integer.parseInt(conf.getServer().getProperty("port.mgmt"))%100));
 		elb.setDesc("Identified leader");
 		elb.setAction(ElectAction.NOMINATE);

 		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setOriginator(conf.getServer().getNodeId());
		mhb.setTime(System.currentTimeMillis());

		RoutingPath.Builder rpb = RoutingPath.newBuilder();
		rpb.setNodeId(conf.getServer().getNodeId());
		rpb.setTime(mhb.getTime());
		mhb.addPath(rpb);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());
 		return mb.build();
 	}

	@Override
	public String getElectionId() {
		// TODO Auto-generated method stub
		if (current == null)
			return null;
		return current.electionID;
	}

	/**
	 * whoami
	 * 
	 * @return
	 */
	public Integer getNodeId() {
		return nodeId;
	}

	@Override
	public void setNodeId(int nodeId) {
		this.nodeId = nodeId;
	}

	@Override
	public void clear() {
		current = null;
	}

	@Override
	public boolean isElectionInprogress() {
		return current != null;
	}

	private void notify(boolean success, Integer leader) {
		if (listener != null)
			listener.concludeWith(success, leader);
	}

	private boolean updateCurrent(LeaderElection req) {
		boolean isNew = false;

		if (current == null) {
			current = new ElectionState();
			isNew = true;
		}

		current.electionID = req.getElectId();
		current.candidate = req.getCandidateId();
		current.desc = req.getDesc();
		current.maxDuration = req.getExpires();
		current.startedOn = System.currentTimeMillis();
		current.state = req.getAction();
		current.id = -1; // TODO me or sender?
		current.active = true;

		return isNew;
	}

	@Override
	public String createElectionID() {
		return "FM." + this.nodeId + "." + ElectionIDGenerator.nextID();
	}

	/**
	 * cast a vote based on what I know (my ID) and where the message has
	 * traveled.
	 * 
	 * This is not a pretty piece of code, nor is the problem as we cannot
	 * ensure consistent behavior.
	 * 
	 * @param mgmt
	 * @param isNew
	 * @return
	 */
	private Management castVote(Management mgmt, boolean isNew) {
		if (!mgmt.hasElection())
			return null;

		LeaderElection req = mgmt.getElection();
		if (req.getExpires() <= System.currentTimeMillis()) {
			logger.info("Node " + this.nodeId + " says election expired - not voting");
			return null;
		}

		// DANGER! If we return because this node ID is in the list, we have a
		// high chance an election will not converge as the maxHops determines
		// if the graph has been traversed!
		boolean allowCycles = true;

		if (!allowCycles) {
			List<RoutingPath> rtes = mgmt.getHeader().getPathList();
			for (RoutingPath rp : rtes) {
				if (rp.getNodeId() == this.nodeId) {
					// logger.info("Node " + this.nodeId +
					// " already in the routing path - not voting");
					return null;
				}
			}
		}

		// okay, the message is new (to me) so I want to determine if I should
		// nominate
		// myself

		LeaderElection.Builder elb = LeaderElection.newBuilder();
		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
		mhb.setTime(System.currentTimeMillis());

		// reversing path. If I'm the farthest a message can travel, reverse the
		// sending
		if (elb.getHops() == 0)
			mhb.clearPath();
		else
			mhb.addAllPath(mgmt.getHeader().getPathList());

		mhb.setOriginator(mgmt.getHeader().getOriginator());

		elb.setElectId(req.getElectId());
		elb.setAction(ElectAction.NOMINATE);
		elb.setDesc(req.getDesc());
		elb.setExpires(req.getExpires());
		elb.setCandidateId(req.getCandidateId());

		// my vote
		if (req.getCandidateId() == this.nodeId) {
			// if I am not in the list and the candidate is myself, I can
			// declare myself to be the leader.
			//
			// this is non-deterministic as it assumes the message has
			// reached all nodes in the network (because we know the
			// diameter or the number of nodes).
			//
			// can end up with a partitioned graph of leaders if hops <
			// diameter!

			notify(true, this.nodeId);
			elb.setAction(ElectAction.DECLAREWINNER);
			elb.setHops(mgmt.getHeader().getPathCount());
			logger.info("Node " + this.nodeId + " is declaring itself the leader");
		} else {
			if (req.getCandidateId() < this.nodeId)
				elb.setCandidateId(this.nodeId);
			
			if (req.getHops() == -1)
				elb.setHops(-1);
			else
				elb.setHops(req.getHops() - 1);

			if (elb.getHops() == 0) {
				// reverse travel of the message to ensure it gets back to
				// the originator
				elb.setHops(mgmt.getHeader().getPathCount());

				// no clear winner, send back the candidate with the highest
				// known ID. So, if a candidate sees itself, it will
				// declare itself to be the winner (see above).
			} else {
				// forwarding the message on so, keep the history where the
				// message has been
				mhb.addAllPath(mgmt.getHeader().getPathList());
			}
		}

		// add myself (may allow duplicate entries, if cycling is allowed)
		RoutingPath.Builder rpb = RoutingPath.newBuilder();
		rpb.setNodeId(this.nodeId);
		rpb.setTime(System.currentTimeMillis());
		mhb.addPath(rpb);

		Management.Builder mb = Management.newBuilder();
		mb.setHeader(mhb.build());
		mb.setElection(elb.build());

		return mb.build();
	}

	@Override
	public Integer getWinner() {
		if (current == null)
			return null;
		else if (current.state.getNumber() == ElectAction.DECLAREELECTION_VALUE)
			return current.candidate;
		else
			return null;
	}

	public void setMaxHops(int maxHops) {
		this.maxHops = maxHops;
	}
	
	public void setConf(ServerConf conf)
	{
		this.conf = conf;
	}
	
	// Message generators
 	
		 	public Management getElectionMessage(String desc){
		 		// Building election message
		 		LeaderElection.Builder elb = LeaderElection.newBuilder();
		 		elb.setCandidateId(conf.getServer().getNodeId());
		 		elb.setElectId(String.valueOf(Integer.parseInt(conf.getServer().getProperty("port.mgmt"))%100));
		 		elb.setDesc(desc);
		 		elb.setAction(ElectAction.DECLAREELECTION);
		 		
		 		
		 		
		 		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
				mhb.setOriginator(conf.getServer().getNodeId());
				mhb.setTime(System.currentTimeMillis());

				RoutingPath.Builder rpb = RoutingPath.newBuilder();
				rpb.setNodeId(conf.getServer().getNodeId());
				rpb.setTime(mhb.getTime());
				mhb.addPath(rpb);

				Management.Builder mb = Management.newBuilder();
				mb.setHeader(mhb.build());
				mb.setElection(elb.build());
		 		return mb.build();
		 	}
		 	
		 	public Management getAbstainMessage(){
		 		// Building abstain message
		 		LeaderElection.Builder elb = LeaderElection.newBuilder();
		 		elb.setCandidateId((conf.getServer().getNodeId()));
		 		elb.setElectId(String.valueOf(Integer.parseInt(conf.getServer().getProperty("port.mgmt"))%100));
		 		// sending the leaderNode in description so that the former leader can know
		 		// who is the current leader in the cluster
		 		elb.setDesc(Integer.toString(leaderNode));
		 		elb.setAction(ElectAction.ABSTAIN);

		 		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
				mhb.setOriginator(conf.getServer().getNodeId());
				mhb.setTime(System.currentTimeMillis());

				RoutingPath.Builder rpb = RoutingPath.newBuilder();
				rpb.setNodeId(conf.getServer().getNodeId());
				rpb.setTime(mhb.getTime());
				mhb.addPath(rpb);

				Management.Builder mb = Management.newBuilder();
				mb.setHeader(mhb.build());
				mb.setElection(elb.build());
		 		return mb.build();
		 	}
		 	
		 	public Management getDeclareWinnerMessage(){
		 		// Building declare winner message
		 		LeaderElection.Builder elb = LeaderElection.newBuilder();
		 		elb.setCandidateId(conf.getServer().getNodeId());
		 		elb.setElectId(String.valueOf(Integer.parseInt(conf.getServer().getProperty("port.mgmt"))%100));
		 		elb.setDesc("Leader's declaration");
		 		elb.setAction(ElectAction.DECLAREWINNER);

		 		MgmtHeader.Builder mhb = MgmtHeader.newBuilder();
				mhb.setOriginator(conf.getServer().getNodeId());
				mhb.setTime(System.currentTimeMillis());

				RoutingPath.Builder rpb = RoutingPath.newBuilder();
				rpb.setNodeId(conf.getServer().getNodeId());
				rpb.setTime(mhb.getTime());
				mhb.addPath(rpb);

				Management.Builder mb = Management.newBuilder();
				mb.setHeader(mhb.build());
				mb.setElection(elb.build());
		 		return mb.build();
		 	}
		 	
		 
}