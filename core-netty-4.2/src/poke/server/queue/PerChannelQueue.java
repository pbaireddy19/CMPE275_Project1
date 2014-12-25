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
package poke.server.queue;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.lang.Thread.State;
import java.net.ConnectException;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

















import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import poke.connect.database.Photo;
import poke.server.conf.NodeDesc;
import poke.server.conf.ServerConf;
import poke.server.management.client.MgmtHandler;
import poke.server.managers.ElectionManager;
import poke.server.managers.HeartbeatData;
import poke.server.managers.HeartbeatManager;
import poke.server.resources.Resource;
import poke.server.resources.ResourceFactory;
import poke.server.resources.ResourceUtil;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;

import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.lifeForce.storage.ClusterMapperManager;
import com.lifeForce.storage.ClusterMapperStorage;
import com.lifeForce.storage.DbConfigurations;

import eye.Comm.Header;
import eye.Comm.Payload;
import eye.Comm.PhotoHeader;
import eye.Comm.PhotoHeader.ResponseFlag;
import eye.Comm.PokeStatus;
import eye.Comm.Request;
import eye.Comm.Header.Routing;
import eye.Comm.PhotoRequest.RequestType;


/**
 * A server queue exists for each connection (channel). A per-channel queue
 * isolates clients. However, with a per-client model. The server is required to
 * use a master scheduler/coordinator to span all queues to enact a QoS policy.
 * 
 * How well does the per-channel work when we think about a case where 1000+
 * connections?
 * 
 * @author gash
 * 
 */
public class PerChannelQueue implements ChannelQueue {
	protected static Logger logger = LoggerFactory.getLogger("server");

	//Testing
	private EventLoopGroup group;
	private MgmtHandler handler= new MgmtHandler();

	private Channel channel;

	// The queues feed work to the inbound and outbound threads (workers). The
	// threads perform a blocking 'get' on the queue until a new event/task is
	// enqueued. This design prevents a wasteful 'spin-lock' design for the
	// threads
	private LinkedBlockingDeque<com.google.protobuf.GeneratedMessage> inbound;
	private LinkedBlockingDeque<com.google.protobuf.GeneratedMessage> outbound;
	public static final int LOGICAL_SHARD = 4;

	// This implementation uses a fixed number of threads per channel
	private OutboundWorker oworker;
	private InboundWorker iworker;

	// not the best method to ensure uniqueness
	private ThreadGroup tgroup = new ThreadGroup("ServerQueue-" + System.nanoTime());

	protected PerChannelQueue(Channel channel) {
		this.channel = channel;
		init();
	}

	protected void init() {
		inbound = new LinkedBlockingDeque<com.google.protobuf.GeneratedMessage>();
		outbound = new LinkedBlockingDeque<com.google.protobuf.GeneratedMessage>();
			
			iworker = new InboundWorker(tgroup, 1, this);
			iworker.start();
		oworker = new OutboundWorker(tgroup, 1, this);
		oworker.start();

		// let the handler manage the queue's shutdown
		// register listener to receive closing of channel
		// channel.getCloseFuture().addListener(new CloseListener(this));
	}

	protected Channel getChannel() {
		return channel;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.ChannelQueue#shutdown(boolean)
	 */
	@Override
	public void shutdown(boolean hard) {
		logger.info("server is shutting down");

		channel = null;

		if (hard) {
			// drain queues, don't allow graceful completion
			inbound.clear();
			outbound.clear();
		}

		if (iworker != null) {
			iworker.forever = false;
			if (iworker.getState() == State.BLOCKED || iworker.getState() == State.WAITING)
				iworker.interrupt();
			iworker = null;
		}

		if (oworker != null) {
			oworker.forever = false;
			if (oworker.getState() == State.BLOCKED || oworker.getState() == State.WAITING)
				oworker.interrupt();
			oworker = null;
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.ChannelQueue#enqueueRequest(eye.Comm.Finger)
	 */
	@Override
	public void enqueueRequest(Request req, Channel notused) {
		try {
			inbound.put(req);
		} catch (InterruptedException e) {
			logger.error("message not enqueued for processing", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see poke.server.ChannelQueue#enqueueResponse(eye.Comm.Response)
	 */
	@Override
	public void enqueueResponse(Request reply, Channel notused) {
		if (reply == null)
			return;

		try {
			System.out.println("In per channel queue :--putting in outbound queue");
			outbound.put(reply);
			System.out.println("Done putting in the queue !! ");
		} catch (InterruptedException e) {
			logger.error("message not enqueued for reply", e);
		}
	}

	protected class OutboundWorker extends Thread {
		int workerId;
		PerChannelQueue sq;
		PerChannelQueue sq1;
		boolean forever = true;

		public OutboundWorker(ThreadGroup tgrp, int workerId, PerChannelQueue sq) {
			super(tgrp, "outbound-" + workerId);
			this.workerId = workerId;
			this.sq = sq;

			if (outbound == null)
				throw new RuntimeException("connection worker detected null queue");
		}

		@Override
		public void run() {
			Channel conn = sq.channel;
			if (conn == null || !conn.isOpen()) {
				PerChannelQueue.logger.error("connection missing, no outbound communication");
				return;
			}

			while (true) {
				if (!forever && sq.outbound.size() == 0)
					break;

				try {
					// block until a message is enqueued
					GeneratedMessage msg = sq.outbound.take();
					if (conn.isWritable()) {
						boolean rtn = false;
						if (channel != null && channel.isOpen() && channel.isWritable()) {
							ChannelFuture cf = channel.writeAndFlush(msg);

							// blocks on write - use listener to be async
							cf.awaitUninterruptibly();
							rtn = cf.isSuccess();
							if (!rtn)
								sq.outbound.putFirst(msg);
						}

					} else
						sq.outbound.putFirst(msg);
				} catch (InterruptedException ie) {
					break;
				} catch (Exception e) {
					PerChannelQueue.logger.error("Unexpected communcation failure", e);
					break;
				}
			}

			if (!forever) {
				PerChannelQueue.logger.info("connection queue closing");
			}
		}
	}

	protected class InboundWorker extends Thread {
		int workerId;
		PerChannelQueue sq;
		boolean forever = true;

		public InboundWorker(ThreadGroup tgrp, int workerId, PerChannelQueue sq) {
			super(tgrp, "inbound-" + workerId);
			this.workerId = workerId;
			this.sq = sq;

			if (outbound == null)
				throw new RuntimeException("connection worker detected null queue");
		}

		@Override
		public void run() {
			Channel conn = sq.channel;
			if (conn == null || !conn.isOpen()) {
				PerChannelQueue.logger.error("connection missing, no inbound communication");
				return;
			}

			while (true) {
				if (!forever && sq.inbound.size() == 0)
					break;

				try {
					ServerConf conf = HeartbeatManager.getInstance().getConf();
					// block until a message is enqueued
					GeneratedMessage msg = sq.inbound.take();

					// process request and enqueue response
					if (msg instanceof Request) {
						 final Request req = ((Request) msg);
						// do we need to route the request?

						// handle it locally
						Resource rsc = ResourceFactory.getInstance().resourceInstance(req.getHeader());

						Request reply = null;
						if (rsc == null) {
							logger.error("failed to obtain resource for ");
							reply = ResourceUtil.buildError(req.getHeader(), PokeStatus.NORESOURCE,
									"Request not processed");
						} 
						Request.Builder reqBuild = Request.newBuilder();
						Header.Builder headerBuild= Header.newBuilder();
						PhotoHeader.Builder photoHeaderBuilder = PhotoHeader.newBuilder();
						Payload.Builder payloderbuilder = Payload.newBuilder();
						if (!req.getHeader().getPhotoHeader().hasEntryNode()) {
								photoHeaderBuilder.setEntryNode(String.valueOf(DbConfigurations.getClusterId()));

								} else {
								// append your cluster id in the entry node so
								// you can know you have seen this request

								String visitedNode = req.getHeader()
								.getPhotoHeader().getEntryNode();
								visitedNode += ","
								+ String.valueOf(DbConfigurations
								.getClusterId());
								}
						
						
						//check if the request is write
							if(req.getHeader().getPhotoHeader().getRequestType() == eye.Comm.PhotoHeader.RequestType.write)
							{
								//Checking if it is the leader
								if(ElectionManager.getInstance().whoIsTheLeader()== conf.getServer().getNodeId())
								{
									System.out.println("Leader Received the Request from Client");
									UUID uuid=UUID.randomUUID();
									String key=uuid.toString();
									int bucket = Hashing.consistentHash(Hashing.md5().hashString(key), LOGICAL_SHARD);
									System.out.println(" the value of the bucket is :--------> "+ bucket);
									if(bucket == conf.getServer().getNodeId())
									{
										//Save to local of the master
										Photo ph=new Photo();
										reply=ph.postMsg(uuid,req);
										sq.enqueueResponse(reply,channel);
									}
									else
									{
										//Forwarding to the worker node
										Bootstrap b = new Bootstrap();
										b.group(new NioEventLoopGroup()).channel(NioSocketChannel.class)
										.handler(new ChannelInitializer<SocketChannel>() {
											@Override
											protected void initChannel(SocketChannel ch)
													throws Exception {
												ChannelPipeline pipeline = ch.pipeline();
												pipeline.addLast("frameDecoder",
														new LengthFieldBasedFrameDecoder(
																67108864, 0, 4, 0, 4));
												pipeline.addLast(
														"protobufDecoder",
														new ProtobufDecoder(eye.Comm.Request
																.getDefaultInstance()));
												pipeline.addLast("frameEncoder",
														new LengthFieldPrepender(4));
												pipeline.addLast("protobufEncoder",
														new ProtobufEncoder());
												pipeline.addLast(new SimpleChannelInboundHandler<eye.Comm.Request>() {
													protected void channelRead0(
															ChannelHandlerContext ctx,
															eye.Comm.Request msg)
																	throws Exception {
														System.out
														.println("Channel Read CommHandler"
																);
														System.out
																.println("REceived the response !!!"+msg);
														sq.enqueueResponse(msg, null);
													}
												});
											}
										});
										b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
										b.option(ChannelOption.TCP_NODELAY, true);
										b.option(ChannelOption.SO_KEEPALIVE, true);

										ConcurrentHashMap<Integer, HeartbeatData> incomingHB = HeartbeatManager.getInstance().getIncomingHB();
										HeartbeatData data = null;
										TreeMap<Integer, NodeDesc>  adjacentNodes = conf.getAdjacent().getAdjacentNodes();
										System.out.println("printing nodes");
										String forwardHost="";
										int forwardPort = 0;
										for (Integer key1 : adjacentNodes.keySet()) {
											if(bucket == adjacentNodes.get(key1).getNodeId())
											{
												forwardHost=adjacentNodes.get(key1).getHost();
												forwardPort=adjacentNodes.get(key1).getPort();
												System.out.println("Host for forwarding the request --> "+forwardHost);
												System.out.println("Port for forwarding the request --> "+forwardPort);
											}
										}

										ChannelFuture channel = b.connect(forwardHost, forwardPort).syncUninterruptibly();
										channel.awaitUninterruptibly(5000l);
										channel.channel().closeFuture().addListener(new NewListener());
										Request.Builder r = Request.newBuilder();
										Header.Builder r1= Header.newBuilder(); 
										Payload.Builder paybuilder = Payload.newBuilder();
										System.out.println("the uuid i am setting is  :"+uuid.toString());
										r1.getPhotoHeaderBuilder()
										.setResponseFlag(eye.Comm.PhotoHeader.ResponseFlag.success)
										.setRequestType(eye.Comm.PhotoHeader.RequestType.read)					
										;
										paybuilder.getPhotoPayloadBuilder().setData(req.getBody().getPhotoPayload().getData()).setName(req.getBody().getPhotoPayload().getName()).setUuid(uuid.toString());
								r.setBody(paybuilder);
								r.setHeader(r1.setRoutingId(Routing.JOBS).setOriginator(2));
										System.out.println("Message Sent Successfully on Channel");
										channel.channel().writeAndFlush(r.build());
									}

								}

							}

							//Check if the request is a read 
							else if (req.getHeader().getPhotoHeader().getRequestType() == eye.Comm.PhotoHeader.RequestType.read)
							{
								System.out.println("I am going to read the request !!-------->>");
								UUID uuid=UUID.fromString(req.getBody().getPhotoPayload().getUuid());
								String key=uuid.toString();
								int bucket = Hashing.consistentHash(Hashing.md5().hashString(key), LOGICAL_SHARD);
								System.out.println(" the value of the bucket is : "+ bucket);
								//check node is that of master
								if(bucket == conf.getServer().getNodeId())
								{
									//save to local 
									Photo ph=new Photo();
									reply=ph.getMsg(bucket,uuid,req);
									sq.enqueueResponse(reply,channel);
								}
								else
								{
									//forwarding request to worker 
									Bootstrap b = new Bootstrap();
									b.group(new NioEventLoopGroup()).channel(NioSocketChannel.class)
									.handler(new ChannelInitializer<SocketChannel>() {
										@Override
										protected void initChannel(SocketChannel ch)
												throws Exception {
											ChannelPipeline pipeline = ch.pipeline();
											pipeline.addLast("frameDecoder",
													new LengthFieldBasedFrameDecoder(
															67108864, 0, 4, 0, 4));
											pipeline.addLast(
													"protobufDecoder",
													new ProtobufDecoder(eye.Comm.Request
															.getDefaultInstance()));
											pipeline.addLast("frameEncoder",
													new LengthFieldPrepender(4));
											pipeline.addLast("protobufEncoder",
													new ProtobufEncoder());
											pipeline.addLast(new SimpleChannelInboundHandler<eye.Comm.Request>() {
												protected void channelRead0(
														ChannelHandlerContext ctx,
														eye.Comm.Request msg)
																throws Exception {
													System.out
													.println("Channel Read CommHandler"+msg
															);
													
													//checking if the request is not found then forwarding it
													Request requestReveived = ((Request) msg);
													if(requestReveived.getHeader().getPhotoHeader().getResponseFlag()==ResponseFlag.failure)
													{
														
														ClusterMapperStorage nextClusterLeader = ClusterMapperManager
																.getClusterDetails(req.getHeader()
																.getPhotoHeader()
																.getEntryNode());
																if (nextClusterLeader != null) {
																// you can still forward this request 
																// to other leaders in the cluster   
																//TODO : logic to forward the request
																} else {
																// all leaders have seen the request
																// - set failure is done - just
																// enqueue the response
																sq.enqueueResponse(requestReveived, null);

																}
													}
													else{
														//The request received from worker is proper and sending response back to client
													sq.enqueueResponse(msg, null);}
												}
											});
										}
									});
									b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
									b.option(ChannelOption.TCP_NODELAY, true);
									b.option(ChannelOption.SO_KEEPALIVE, true);

									ConcurrentHashMap<Integer, HeartbeatData> incomingHB = HeartbeatManager.getInstance().getIncomingHB();
									TreeMap<Integer, NodeDesc>  adjacentNodes = conf.getAdjacent().getAdjacentNodes();
									HeartbeatData data = null;
									String forwardHost="";
									int forwardPort = 0;
									for (Integer key1 : adjacentNodes.keySet()) {
										if(bucket == adjacentNodes.get(key1).getNodeId())
										{
											forwardHost=adjacentNodes.get(key1).getHost();
											forwardPort=adjacentNodes.get(key1).getPort();
											System.out.println("Host for forwarding the request --> "+forwardHost);
											System.out.println("Port for forwarding the request --> "+forwardPort);
										}
									}

									ChannelFuture channel = b.connect(forwardHost, forwardPort).syncUninterruptibly();
									channel.awaitUninterruptibly(5000l);
									channel.channel().closeFuture().addListener(new NewListener());
									channel.channel().writeAndFlush(msg);
									System.out.println("Message Forwarding on my local network Successfully on Channel");
								}
							}
							
							else if (req.getHeader().getPhotoHeader().getRequestType() == eye.Comm.PhotoHeader.RequestType.delete)
							{
								System.out.println("I am going to delete the request !!-------->>");
								UUID uuid=UUID.fromString(req.getBody().getPhotoPayload().getUuid());
								String key=uuid.toString();
								int bucket = Hashing.consistentHash(Hashing.md5().hashString(key), LOGICAL_SHARD);
								System.out.println(" the value of the bucket is : "+ bucket);
								//check node is that of master
								if(bucket == conf.getServer().getNodeId())
								{
									//save to local 
									Photo ph=new Photo();
									reply=ph.deleteMsg(bucket,uuid,req);
									sq.enqueueResponse(reply,channel);
								}
								else
								{
									//forwarding request to worker 
									Bootstrap b = new Bootstrap();
									b.group(new NioEventLoopGroup()).channel(NioSocketChannel.class)
									.handler(new ChannelInitializer<SocketChannel>() {
										@Override
										protected void initChannel(SocketChannel ch)
												throws Exception {
											ChannelPipeline pipeline = ch.pipeline();
											pipeline.addLast("frameDecoder",
													new LengthFieldBasedFrameDecoder(
															67108864, 0, 4, 0, 4));
											pipeline.addLast(
													"protobufDecoder",
													new ProtobufDecoder(eye.Comm.Request
															.getDefaultInstance()));
											pipeline.addLast("frameEncoder",
													new LengthFieldPrepender(4));
											pipeline.addLast("protobufEncoder",
													new ProtobufEncoder());
											pipeline.addLast(new SimpleChannelInboundHandler<eye.Comm.Request>() {
												protected void channelRead0(
														ChannelHandlerContext ctx,
														eye.Comm.Request msg)
																throws Exception {
													System.out
													.println("Channel Read CommHandler"+msg
															);
													sq.enqueueResponse(msg, null);
												}
											});
										}
									});
									b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
									b.option(ChannelOption.TCP_NODELAY, true);
									b.option(ChannelOption.SO_KEEPALIVE, true);

									ConcurrentHashMap<Integer, HeartbeatData> incomingHB = HeartbeatManager.getInstance().getIncomingHB();
									TreeMap<Integer, NodeDesc>  adjacentNodes = conf.getAdjacent().getAdjacentNodes();
									HeartbeatData data = null;
									String forwardHost="";
									int forwardPort = 0;
									for (Integer key1 : adjacentNodes.keySet()) {
										if(bucket == adjacentNodes.get(key1).getNodeId())
										{
											forwardHost=adjacentNodes.get(key1).getHost();
											forwardPort=adjacentNodes.get(key1).getPort();
											System.out.println("Host for forwarding the request --> "+forwardHost);
											System.out.println("Port for forwarding the request --> "+forwardPort);
										}
									}

									ChannelFuture channel = b.connect(forwardHost, forwardPort).syncUninterruptibly();
									channel.awaitUninterruptibly(5000l);
									channel.channel().closeFuture().addListener(new NewListener());
									channel.channel().writeAndFlush(msg);
									System.out.println("Message Forwarding on my local network Successfully on Channel");
								}
							}
							
							
							
							
							

							
					}
					
					else 
					{
						
						System.out.println(" NOT THE INSTANCE OF REQUEST ");
						 
					}

				} 
				
				
				catch (InterruptedException ie) {
					break;}
				 catch (Exception e) {
					PerChannelQueue.logger.error("Unexpected processing failure", e);
					break;
				}
			}

			if (!forever) {
				PerChannelQueue.logger.info("connection queue closing");
			}
		}
	}

	public class CloseListener implements ChannelFutureListener {
		private ChannelQueue sq;

		public CloseListener(ChannelQueue sq) {
			this.sq = sq;
		}

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			sq.shutdown(true);
		}
	}


	public class NewListener implements ChannelFutureListener {
		
		@Override
		public void operationComplete(ChannelFuture arg0) throws Exception {
			logger.info("RECEIVED RESPONSE FROM SLAVE ************");
		}
	}

}
