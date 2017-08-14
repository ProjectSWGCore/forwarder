package com.projectswg.networking.client;

import com.projectswg.networking.client.ClientServerSocket.ClientServer;
import com.projectswg.resources.ClientConnectionStatus;

public class ClientData {
	
	private int connectionId;
	private int communicationPort;
	private short rxSequence;
	private short txSequence;
	private short ackSequence;
	private short oooSequence;
	private long lastAcknowledgement;
	private boolean zoning;
	private ClientServer server;
	private ClientConnectionStatus status;
	
	public ClientData() {
		reset(ClientConnectionStatus.DISCONNECTED);
	}
	
	public void resetConnectionInfo() {
		setConnectionId(-1);
		setCommunicationPort(0);
		setRxSequence((short) -1);
		setTxSequence((short) 0);
		setAckSequence((short) 0);
		setOOOSequence((short) 0);
		setLastAcknowledgement(0);
		setZoning(false);
	}
	
	public void reset(ClientConnectionStatus status) {
		resetConnectionInfo();
		setStatus(status);
		if (status == ClientConnectionStatus.LOGIN_CONNECTED)
			setClientServer(ClientServer.LOGIN);
		else if (status == ClientConnectionStatus.ZONE_CONNECTED)
			setClientServer(ClientServer.ZONE);
		else
			setClientServer(ClientServer.NONE);
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
	
	public long getLastAcknowledgement() {
		return lastAcknowledgement;
	}
	
	public boolean isZoning() {
		return zoning;
	}
	
	public double getTimeSinceLastAcknowledgement() {
		long last = lastAcknowledgement;
		if (last == 0)
			return 0;
		return (System.nanoTime() - last) / 1E6;
	}
	
	public boolean isTimedOut() {
		if (getTimeSinceLastAcknowledgement() > 5000 && !zoning)
			return true;
		return getTimeSinceLastAcknowledgement() > 30000 && zoning;
	}
	
	public ClientServer getClientServer() {
		return server;
	}
	
	public ClientConnectionStatus getStatus() {
		return status;
	}
	
	public boolean isWaitingForClientAcknowledge() {
		return txSequence-1 > ackSequence && connectionId != -1;
	}
	
	public boolean isConnectionInitialized() {
		return connectionId != -1 && communicationPort > 0;
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
	
	public void setLastAcknowledgement(long lastAcknowledgement) {
		this.lastAcknowledgement = lastAcknowledgement;
	}
	
	public void setZoning(boolean zoning) {
		this.zoning = zoning;
	}
	
	public void setClientServer(ClientServer server) {
		this.server = server;
	}
	
	public ClientConnectionStatus setStatus(ClientConnectionStatus status) {
		ClientConnectionStatus old = this.status;
		this.status = status;
		return old;
	}
	
	public short getAndIncrementRxSequence() {
		return rxSequence++;
	}
	
	public short getAndIncrementTxSequence() {
		return txSequence++;
	}
	
}
