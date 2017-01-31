package com.projectswg.networking.client;

import com.projectswg.networking.client.ClientServerSocket.ClientServer;

public class ClientData {
	
	private int connectionId;
	private int communicationPort;
	private short rxSequence;
	private short txSequence;
	private short ackSequence;
	private short oooSequence;
	private ClientServer server;
	
	public ClientData() {
		reset();
	}
	
	public void reset() {
		setConnectionId(-1);
		setCommunicationPort(0);
		setRxSequence((short) -1);
		setTxSequence((short) 0);
		setAckSequence((short) 0);
		setOOOSequence((short) 0);
		setClientServer(ClientServer.LOGIN);
	}
	
	public int getConnectionId() {
		return connectionId;
	}
	
	public int getCommunicationPort() {
		return communicationPort;
	}
	
	public short getRxSequence() {
		return rxSequence;
	}
	
	public short getTxSequence() {
		return txSequence;
	}
	
	public short getAckSequence() {
		return ackSequence;
	}
	
	public short getOOOSequence() {
		return oooSequence;
	}
	
	public ClientServer getClientServer() {
		return server;
	}
	
	public void setConnectionId(int connectionId) {
		this.connectionId = connectionId;
	}
	
	public void setCommunicationPort(int communicationPort) {
		this.communicationPort = communicationPort;
	}
	
	public void setRxSequence(short rxSequence) {
		this.rxSequence = rxSequence;
	}
	
	public void setTxSequence(short txSequence) {
		this.txSequence = txSequence;
	}
	
	public void setAckSequence(short ackSequence) {
		this.ackSequence = ackSequence;
	}
	
	public void setOOOSequence(short oooSequence) {
		this.oooSequence = oooSequence;
	}
	
	public void setClientServer(ClientServer server) {
		this.server = server;
	}
	
	public short getAndIncrementRxSequence() {
		return rxSequence++;
	}
	
	public short getAndIncrementTxSequence() {
		return txSequence++;
	}
	
}
