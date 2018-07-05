package com.projectswg.forwarder.services.client;

import com.projectswg.forwarder.intents.client.SonyPacketInboundIntent;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.packets.ClientNetworkStatusUpdate;
import com.projectswg.forwarder.resources.networking.packets.KeepAlive;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import com.projectswg.forwarder.resources.networking.packets.ServerNetworkStatusUpdate;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.IntentMultiplexer;
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer;
import me.joshlarson.jlcommon.control.Service;

public class ClientProtocolService extends Service {
	
	private static final int GALACTIC_BASE_TIME	= 1323043200;
	
	private final IntentMultiplexer multiplexer;
	
	public ClientProtocolService() {
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
	}
	
	@IntentHandler
	private void handleSonyPacketInboundIntent(SonyPacketInboundIntent spii) {
		multiplexer.call(spii.getStack(), spii.getPacket());
	}
	
	@Multiplexer
	private void handleClientNetworkStatus(ProtocolStack stack, ClientNetworkStatusUpdate update) {
		ServerNetworkStatusUpdate serverNet = new ServerNetworkStatusUpdate();
		serverNet.setClientTickCount((short) update.getTick());
		serverNet.setServerSyncStampLong((int) (System.currentTimeMillis()-GALACTIC_BASE_TIME));
		serverNet.setClientPacketsSent(update.getSent());
		serverNet.setClientPacketsRecv(update.getRecv());
		serverNet.setServerPacketsSent(stack.getTxSequence());
		serverNet.setServerPacketsRecv(stack.getRxSequence());
		stack.send(serverNet);
	}
	
	@Multiplexer
	private void handleKeepAlive(ProtocolStack stack, KeepAlive keepAlive) {
		stack.send(new KeepAlive());
	}
	
}
