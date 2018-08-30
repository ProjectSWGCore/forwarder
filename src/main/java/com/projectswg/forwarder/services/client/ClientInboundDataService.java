package com.projectswg.forwarder.services.client;

import com.projectswg.common.network.packets.PacketType;
import com.projectswg.common.network.packets.swg.zone.HeartBeat;
import com.projectswg.forwarder.intents.client.DataPacketInboundIntent;
import com.projectswg.forwarder.intents.client.SonyPacketInboundIntent;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.packets.*;
import me.joshlarson.jlcommon.control.IntentChain;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.IntentMultiplexer;
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer;
import me.joshlarson.jlcommon.control.Service;
import me.joshlarson.jlcommon.log.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ClientInboundDataService extends Service {
	
	private final IntentMultiplexer multiplexer;
	private final IntentChain intentChain;
	
	public ClientInboundDataService() {
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
		this.intentChain = new IntentChain();
	}
	
	@IntentHandler
	private void handleSonyPacketInboundIntent(SonyPacketInboundIntent spii) {
		multiplexer.call(spii.getStack(), spii.getPacket());
	}
	
	@Multiplexer
	private void handlePingPacket(ProtocolStack stack, PingPacket ping) {
		onData(new HeartBeat(ping.getPayload()).encode().array());
	}
	
	@Multiplexer
	private void handleRawSwgPacket(ProtocolStack stack, RawSWGPacket data) {
		onData(data.getRawData());
	}
	
	@Multiplexer
	private void handleDataChannel(ProtocolStack stack, DataChannel data) {
		switch (stack.addIncoming(data)) {
			case READY:
				readAvailablePackets(stack);
				break;
			case OUT_OF_ORDER:
				Log.d("Data/Inbound Received Data Out of Order %d", data.getSequence());
				for (short seq = stack.getRxSequence(); seq < data.getSequence(); seq++)
					stack.send(new OutOfOrder(seq));
				break;
			default:
				break;
		}
	}
	
	@Multiplexer
	private void handleFragmented(ProtocolStack stack, Fragmented frag) {
		switch (stack.addIncoming(frag)) {
			case READY:
				readAvailablePackets(stack);
				break;
			case OUT_OF_ORDER:
				Log.d("Data/Inbound Received Frag Out of Order %d", frag.getSequence());
				for (short seq = stack.getRxSequence(); seq < frag.getSequence(); seq++)
					stack.send(new OutOfOrder(seq));
				break;
			default:
				break;
		}
	}
	
	private void readAvailablePackets(ProtocolStack stack) {
		short highestSequence = -1;
		boolean updatedSequence = false;
		SequencedPacket packet = stack.getNextIncoming();
		while (packet != null) {
			Log.t("Data/Inbound Received: %s", packet);
			if (packet instanceof DataChannel) {
				for (byte [] data : ((DataChannel) packet).getPackets())
					onData(data);
			} else if (packet instanceof Fragmented) {
				byte [] data = stack.addFragmented((Fragmented) packet);
				if (data != null)
					onData(data);
			}
			highestSequence = packet.getSequence();
			packet = stack.getNextIncoming();
			updatedSequence = true;
		}
		if (updatedSequence)
			stack.send(new Acknowledge(highestSequence));
	}
	
	private void onData(byte [] data) {
		PacketType type = PacketType.fromCrc(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
		Log.d("Data/Inbound Received Data: %s", type);
		intentChain.broadcastAfter(getIntentManager(), new DataPacketInboundIntent(data));
	}
	
}
