package com.projectswg.forwarder.services.client;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.PacketType;
import com.projectswg.common.network.packets.swg.zone.HeartBeat;
import com.projectswg.forwarder.Forwarder.ForwarderData;
import com.projectswg.forwarder.intents.client.*;
import com.projectswg.forwarder.intents.control.StartForwarderIntent;
import com.projectswg.forwarder.resources.networking.ClientServer;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.data.SequencedOutbound;
import com.projectswg.forwarder.resources.networking.packets.Acknowledge;
import com.projectswg.forwarder.resources.networking.packets.OutOfOrder;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import me.joshlarson.jlcommon.concurrency.BasicThread;
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.concurrency.SmartLock;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.IntentMultiplexer;
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ClientOutboundDataService extends Service {
	
	private final IntentMultiplexer multiplexer;
	private final Set<ProtocolStack> activeStacks;
	private final BasicThread sendThread;
	private final SmartLock signaller;
	
	private ForwarderData data;
	
	public ClientOutboundDataService() {
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
		this.activeStacks = ConcurrentHashMap.newKeySet();
		this.sendThread = new BasicThread("outbound-sender", this::persistentSend);
		this.signaller = new SmartLock();
		this.data = null;
	}
	
	@Override
	public boolean terminate() {
		sendThread.stop(true);
		return sendThread.awaitTermination(1000);
	}
	
	@IntentHandler
	private void handleStartForwarderIntent(StartForwarderIntent sfi) {
		data = sfi.getData();
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
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		if (!sendThread.isExecuting())
			return;
		sendThread.stop(true);
		sendThread.awaitTermination(1000);
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
						for (ProtocolStack stack : activeStacks)
							stack.sendPing(heartbeat.getPayload());
						return;
					}
					break;
				}
			}
		}
		final ClientServer finalFilterServer = filterServer;
		ProtocolStack stack = activeStacks.stream().filter(s -> s.getServer() == finalFilterServer).findFirst().orElse(null);
		if (stack == null) {
			Log.d("Data/Oubound Sending %s [len=%d] to %s", type, dpoi.getData().length, activeStacks);
			for (ProtocolStack active : activeStacks)
				active.addOutbound(dpoi.getData());
		} else {
			Log.d("Data/Outbound Sending %s [len=%d] to %s", type, dpoi.getData().length, stack);
			stack.addOutbound(dpoi.getData());
		}
		signaller.signal();
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
		int maxSend = data.getOutboundTunerMaxSend();
		if (maxSend <= 0)
			maxSend = 400;
		int interval = data.getOutboundTunerInterval();
		if (interval <= 0)
			interval = 1000;
		
		int bucket = 0;
		int [] sendCounts = new int[interval / 20];
		SequencedOutbound [] buffer = new SequencedOutbound[maxSend];
		Log.d("Data/Outbound Starting Persistent Send");
		while (!Delay.isInterrupted()) {
			sendCounts[bucket] = 0;
			
			int previousInterval = 0;
			for (int count : sendCounts)
				previousInterval += count;
			if (previousInterval < maxSend) {
				for (ProtocolStack stack : activeStacks) {
					stack.fillOutboundPackagedBuffer(maxSend);
					int count = stack.fillOutboundBuffer(buffer);
					for (int i = 0; i < count && Delay.sleepMicro(50); i++) {
						stack.send(buffer[i].getData());
					}
					
					if (count > 0)
						Log.t("Data/Outbound Sent %d  Start: %d", count, buffer[0].getSequence());
					sendCounts[bucket] += count;
				}
				try {
					signaller.await(20, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
					break;
				}
			} else {
				Delay.sleepMilli(20);
			}
			
			bucket = (bucket + 1) % sendCounts.length;
		}
		Log.d("Data/Outbound Stopping Persistent Send");
	}
	
}
