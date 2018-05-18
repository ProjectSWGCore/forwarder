package com.projectswg.forwarder.services.client;

import com.projectswg.common.network.NetBuffer;
import com.projectswg.common.network.packets.PacketType;
import com.projectswg.common.network.packets.swg.zone.HeartBeat;
import com.projectswg.forwarder.intents.client.*;
import com.projectswg.forwarder.resources.client.state.OutboundDataTuner;
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
import java.util.concurrent.atomic.AtomicReference;

public class ClientOutboundDataService extends Service {
	
	private static final int TIMER_DELAY = 20;
	
	private final IntentMultiplexer multiplexer;
	private final AtomicReference<ProtocolStack> stack;
	private final ScheduledThreadPool timerThread;
	private final OutboundDataTuner tuner;
	private final Object outboundMutex;
	
	public ClientOutboundDataService() {
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
		this.stack = new AtomicReference<>(null);
		this.timerThread = new ScheduledThreadPool(2, 5, "outbound-sender-%d");
		this.tuner = new OutboundDataTuner();
		this.outboundMutex = new Object();
	}
	
	@IntentHandler
	private void handleSonyPacketInboundIntent(SonyPacketInboundIntent spii) {
		ProtocolStack stack = this.stack.get();
		assert stack != null : "stack is null";
		multiplexer.call(stack, spii.getPacket());
	}
	
	@IntentHandler
	private void handleClientConnectedIntent(ClientConnectedIntent cci) {
		if (timerThread.isRunning())
			return;
		timerThread.start();
		timerThread.executeWithFixedDelay(TIMER_DELAY, TIMER_DELAY, this::timerCallback);
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
	private void handleUpdateStackIntent(UpdateStackIntent sci) {
		stack.set(sci.getStack());
	}
	
	@IntentHandler
	private void handleDataPacketInboundIntent(DataPacketInboundIntent dpii) {
		ProtocolStack stack = this.stack.get();
		if (stack == null)
			return;
		PacketType type = PacketType.fromCrc(ByteBuffer.wrap(dpii.getData()).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
		switch (type) {
			case LAG_REQUEST:
				tuner.markLag();
				break;
		}
	}
	
	@IntentHandler
	private void handleDataPacketOutboundIntent(DataPacketOutboundIntent dpoi) {
		ProtocolStack stack = this.stack.get();
		if (stack == null)
			return;
		PacketType type = PacketType.fromCrc(ByteBuffer.wrap(dpoi.getData()).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
		switch (type) {
			case CMD_START_SCENE:
				tuner.markStart();
				break;
			case CMD_SCENE_READY:
				tuner.markEnd();
				break;
			case HEART_BEAT_MESSAGE: {
				HeartBeat heartbeat = new HeartBeat();
				heartbeat.decode(NetBuffer.wrap(dpoi.getData()));
				if (heartbeat.getPayload().length > 0) {
					stack.sendPing(heartbeat.getPayload());
					return;
				}
				break;
			}
		}
		synchronized (outboundMutex) {
			stack.addOutbound(dpoi.getData());
		}
	}
	
	@Multiplexer
	private void handleAcknowledgement(ProtocolStack stack, Acknowledge ack) {
		Log.t("Acknowledged: %d. Min Sequence: %d", ack.getSequence(), stack.getFirstUnacknowledgedOutbound());
		tuner.markAck();
		synchronized (outboundMutex) {
			stack.clearAcknowledgedOutbound(ack.getSequence());
			for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
				outbound.setSent(false);
			}
		}
	}
	
	@Multiplexer
	private void handleOutOfOrder(ProtocolStack stack, OutOfOrder ooo) {
		tuner.markOOO();
		synchronized (outboundMutex) {
			for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
				if (outbound.getSequence() > ooo.getSequence())
					break;
				outbound.setSent(false);
			}
		}
	}
	
	private void clearSentBit() {
		ProtocolStack stack = this.stack.get();
		if (stack == null)
			return;
		synchronized (outboundMutex) {
			for (SequencedOutbound outbound : stack.getOutboundPackagedBuffer()) {
				outbound.setSent(false);
			}
		}
	}
	
	private void timerCallback() {
		ProtocolStack stack = this.stack.get();
		if (stack == null)
			return;
		int maxSend = tuner.getMaxSend();
		synchronized (outboundMutex) {
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
