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
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.concurrency.ScheduledThreadPool;
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
	
	private final IntentMultiplexer multiplexer;
	private final Set<ProtocolStack> activeStacks;
	private final ScheduledThreadPool timerThread;
	private final Object outboundMutex;
	
	private ForwarderData data;
	
	public ClientOutboundDataService() {
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
		this.activeStacks = ConcurrentHashMap.newKeySet();
		this.timerThread = new ScheduledThreadPool(2, 5, "outbound-sender-%d");
		this.outboundMutex = new Object();
		this.data = null;
	}
	
	@Override
	public boolean terminate() {
		timerThread.stop();
		return timerThread.awaitTermination(1000);
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
		if (timerThread.isRunning())
			return;
		int interval = data.getOutboundTunerInterval();
		if (interval <= 0)
			interval = 20;
		timerThread.start();
		timerThread.executeWithFixedDelay(interval, interval, this::timerCallback);
		timerThread.executeWithFixedRate(0, 5000, this::clearSentBit);
	}
	
	@IntentHandler
	private void handleClientDisconnectedIntent(ClientDisconnectedIntent cdi) {
		if (!timerThread.isRunning())
			return;
		timerThread.stop();
		timerThread.awaitTermination(500);
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
		synchronized (outboundMutex) {
			if (filterServer == null) {
				Log.d("Sending %d bytes to %s", dpoi.getData().length, activeStacks);
				for (ProtocolStack stack : activeStacks)
					stack.addOutbound(dpoi.getData());
			} else {
				final ClientServer finalFilterServer = filterServer;
				ProtocolStack stack = activeStacks.stream().filter(s -> s.getServer() == finalFilterServer).findFirst().orElse(null);
				if (stack != null) {
					Log.d("Sending %d bytes to %s", dpoi.getData().length, stack);
					stack.addOutbound(dpoi.getData());
				}
			}
		}
	}
	
	@Multiplexer
	private void handleAcknowledgement(ProtocolStack stack, Acknowledge ack) {
		Log.t("Acknowledged: %d. Min Sequence: %d", ack.getSequence(), stack.getFirstUnacknowledgedOutbound());
		synchronized (outboundMutex) {
			stack.clearAcknowledgedOutbound(ack.getSequence());
			for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
				outbound.setSent(false);
			}
		}
	}
	
	@Multiplexer
	private void handleOutOfOrder(ProtocolStack stack, OutOfOrder ooo) {
		synchronized (outboundMutex) {
			for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
				if (outbound.getSequence() > ooo.getSequence())
					break;
				outbound.setSent(false);
			}
		}
	}
	
	private void clearSentBit() {
		synchronized (outboundMutex) {
			for (ProtocolStack stack : activeStacks) {
				for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
					outbound.setSent(false);
				}
			}
		}
	}
	
	private void timerCallback() {
		int maxSend = data.getOutboundTunerMaxSend();
		if (maxSend <= 0)
			maxSend = 100;
		synchronized (outboundMutex) {
			for (ProtocolStack stack : activeStacks) {
				stack.fillOutboundPackagedBuffer(maxSend);
				int sent = 0;
				int runStart = Integer.MIN_VALUE;
				int runEnd = 0;
				for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
					runEnd = outbound.getSequence();
					if (runStart == Integer.MIN_VALUE) {
						runStart = runEnd;
					}
					stack.send(outbound.getData());
					Delay.sleepMicro(50);
					if (sent++ >= maxSend)
						break;
				}
				if (runStart != Integer.MIN_VALUE)
					Log.t("Sending to %s: %d - %d", stack.getSource(), runStart, runEnd);
			}
		}
	}
	
}
