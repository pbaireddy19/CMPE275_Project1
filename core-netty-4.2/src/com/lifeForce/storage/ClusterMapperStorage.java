/**
 * 
 */
package com.lifeForce.storage;

public class ClusterMapperStorage {
	private int clusterID;
	private String leaderHostAddress;
	private int port;

	public ClusterMapperStorage() {

	}

	public ClusterMapperStorage(String host, int port) {
		this.clusterID = DbConfigurations.getClusterId();
		this.leaderHostAddress = host;
		this.port = port;
	}
	
	public int getClusterID() {
		return clusterID;
	}

	public void setClusterID(int clusterID) {
		this.clusterID = clusterID;
	}

	public String getLeaderHostAddress() {
		return leaderHostAddress;
	}

	public void setLeaderHostAddress(String leaderHostAddress) {
		this.leaderHostAddress = leaderHostAddress;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

}