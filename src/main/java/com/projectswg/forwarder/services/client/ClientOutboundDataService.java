package com.projectswg.forwarder.services.client;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.PacketType;
import com.projectswg.common.network.packets.swg.zone.HeartBeat;
import com.projectswg.forwarder.intents.client.*;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.resources.networking.ClientServer;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.data.SequencedOutbound;
import com.projectswg.forwarder.resources.networking.packets.Acknowledge;
import com.projectswg.forwarder.resources.networking.packets.OutOfOrder;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import me.joshlarson.jlcommon.concurrency.BasicScheduledThread;
import me.joshlarson.jlcommon.concurrency.BasicThread;
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.IntentMultiplexer;
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientOutboundDataService extends Service {
	
	private final SequencedOutbound [] outboundBuffer;
	private final IntentMultiplexer multiplexer;
	private final Set<ProtocolStack> activeStacks;
	private final BasicThread sendThread;
	private final BasicScheduledThread heartbeatThread;
	
	public ClientOutboundDataService() {
		this.outboundBuffer = new SequencedOutbound[4096];
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
		this.activeStacks = ConcurrentHashMap.newKeySet();
		this.sendThread = new BasicThread("outbound-sender", this::persistentSend);
		this.heartbeatThread = new BasicScheduledThread("heartbeat", this::heartbeat);
	}
	
	@Override
	public boolean terminate() {
		if (sendThread.isExecuting())
			sendThread.stop(true);
		if (heartbeatThread.isRunning())
			heartbeatThread.stop();
		return sendThread.awaitTermination(1000) && heartbeatThread.awaitTermination(1000);
	}
	
	@IntentHandler
	private void handleStartForwarderIntent(StartForwarderIntent sfi) {
	}
	
	@IntentHandler
	private void handleSonyPacketInboundIntent(SonyPacketInboundIntent spii) {
		multiplexer.call(spii.getStack(), spii.getPacket());
	}
	
	@IntentHandler
	private void handleClientConnectedIntent(ClientConnectedIntent cci) {
		if (sendThread.isExecuting())
			return;
		sendThread.start();
		heartbeatThread.startWithFixedRate(0, 500);
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		if (!sendThread.isExecuting())
			return;
		sendThread.stop(true);
		heartbeatThread.stop();
		sendThread.awaitTermination(1000);
		heartbeatThread.awaitTermination(1000);
	}
	
	@IntentHandler
	private void handleStackCreatedIntent(StackCreatedIntent sci) {
		activeStacks.add(sci.getStack());
	}
	
	@IntentHandler
	private void handleStackDestroyedIntent(StackDestroyedIntent sdi) {
		activeStacks.remove(sdi.getStack());
	}
	
	@IntentHandler
	private void handleDataPacketOutboundIntent(DataPacketOutboundIntent dpoi) {
		PacketType type = PacketType.fromCrc(ByteBuffer.wrap(dpoi.getData()).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
		ClientServer filterServer = ClientServer.ZONE;
		if (type != null) {
			switch (type) {
				case ERROR_MESSAGE:
				case SERVER_ID:
				case SERVER_NOW_EPOCH_TIME:
					filterServer = null;
					break;
				case LOGIN_CLUSTER_STATUS:
				case LOGIN_CLIENT_TOKEN:
				case LOGIN_INCORRECT_CLIENT_ID:
				case LOGIN_ENUM_CLUSTER:
				case ENUMERATE_CHARACTER_ID:
				case CHARACTER_CREATION_DISABLED:
				case DELETE_CHARACTER_REQUEST:
				case DELETE_CHARACTER_RESPONSE:
					filterServer = ClientServer.LOGIN;
					break;
				case HEART_BEAT_MESSAGE: {
					HeartBeat heartbeat = new HeartBeat();
					heartbeat.decode(NetBuffer.wrap(dpoi.getData()));
					if (heartbeat.getPayload().length > 0) {
						new SendPongIntent(heartbeat.getPayload()).broadcast(getIntentManager());
						return;
					}
					break;
				}
			}
		}
		final ClientServer finalFilterServer = filterServer;
		ProtocolStack stack = activeStacks.stream().filter(s -> s.getServer() == finalFilterServer).findFirst().orElse(null);
		if (stack == null) {
			Log.d("Data/Outbound Sending %s [len=%d] to %s", type, dpoi.getData().length, activeStacks);
			for (ProtocolStack active : activeStacks) {
				active.addOutbound(dpoi.getData());
			}
		} else {
			Log.d("Data/Outbound Sending %s [len=%d] to %s", type, dpoi.getData().length, stack);
			stack.addOutbound(dpoi.getData());
		}
	}
	
	@Multiplexer
	private void handleAcknowledgement(ProtocolStack stack, Acknowledge ack) {
		Log.t("Data/Outbound Client Acknowledged: %d. Min Sequence: %d", ack.getSequence(), stack.getFirstUnacknowledgedOutbound());
		stack.clearAcknowledgedOutbound(ack.getSequence());
	}
	
	@Multiplexer
	private void handleOutOfOrder(ProtocolStack stack, OutOfOrder ooo) {
		Log.t("Data/Outbound Out of Order: %d. Min Sequence: %d", ooo.getSequence(), stack.getFirstUnacknowledgedOutbound());
	}
	
	private void persistentSend() {
		Log.d("Data/Outbound Starting Persistent Send");
		int iteration = 0;
		while (!Delay.isInterrupted()) {
			flushPackaged(iteration % 20 == 0);
			iteration++;
			Delay.sleepMilli(50);
		}
		Log.d("Data/Outbound Stopping Persistent Send");
	}
	
	private void heartbeat() {
		for (ProtocolStack stack : activeStacks) {
			stack.send(new HeartBeat().encode().array());
		}
	}
	
	private void flushPackaged(boolean overrideSent) {
		for (ProtocolStack stack : activeStacks) {
			stack.fillOutboundPackagedBuffer(outboundBuffer.length);
			int count = stack.fillOutboundBuffer(outboundBuffer);
			int sent = 0;
			for (int i = 0; i < count; i++) {
				SequencedOutbound out = outboundBuffer[i];
				if (overrideSent || !out.isSent()) {
					stack.send(out.getData());
					out.setSent(true);
					sent++;
				}
			}
			
			if (sent > 0)
				Log.t("Data/Outbound Sent %d - %d  [%d]", outboundBuffer[0].getSequence(), outboundBuffer[count-1].getSequence(), count);
			else if (count > 0)
				Log.t("Data/Outbound Waiting to send %d - %d [count=%d]", outboundBuffer[0].getSequence(), outboundBuffer[count-1].getSequence(), count);
		}
	}
	
}
