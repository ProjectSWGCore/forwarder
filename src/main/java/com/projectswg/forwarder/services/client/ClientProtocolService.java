package com.projectswg.forwarder.services.client;

import com.projectswg.forwarder.intents.client.SonyPacketInboundIntent;
import com.projectswg.forwarder.intents.client.UpdateStackIntent;
import com.projectswg.forwarder.resources.networking.data.ProtocolStack;
import com.projectswg.forwarder.resources.networking.packets.ClientNetworkStatusUpdate;
import com.projectswg.forwarder.resources.networking.packets.KeepAlive;
import com.projectswg.forwarder.resources.networking.packets.Packet;
import com.projectswg.forwarder.resources.networking.packets.ServerNetworkStatusUpdate;
import me.joshlarson.jlcommon.control.IntentHandler;
import me.joshlarson.jlcommon.control.IntentMultiplexer;
import me.joshlarson.jlcommon.control.IntentMultiplexer.Multiplexer;
import me.joshlarson.jlcommon.control.Service;

import java.util.concurrent.atomic.AtomicReference;

public class ClientProtocolService extends Service {
	
	private static final int GALACTIC_BASE_TIME	= 1323043200;
	
	private final AtomicReference<ProtocolStack> stack;
	private final IntentMultiplexer multiplexer;
	
	public ClientProtocolService() {
		this.stack = new AtomicReference<>(null);
		this.multiplexer = new IntentMultiplexer(this, ProtocolStack.class, Packet.class);
	}
	
	@IntentHandler
	private void handleSonyPacketInboundIntent(SonyPacketInboundIntent spii) {
		ProtocolStack stack = this.stack.get();
		assert stack != null : "stack is null";
		multiplexer.call(stack, spii.getPacket());
	}
	
	@IntentHandler
	private void handleUpdateStackIntent(UpdateStackIntent sci) {
		stack.set(sci.getStack());
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
