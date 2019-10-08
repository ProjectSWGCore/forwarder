package com.projectswg.forwarder.intents

import com.projectswg.forwarder.resources.networking.data.ProtocolStack
import com.projectswg.forwarder.resources.networking.packets.Packet
import me.joshlarson.jlcommon.control.Intent

class ClientConnectedIntent: Intent()
class ClientDisconnectedIntent: Intent()
class DataPacketInboundIntent(val data: ByteArray): Intent()
class DataPacketOutboundIntent(val data: ByteArray): Intent()
class SendPongIntent(val data: ByteArray): Intent()
class SonyPacketInboundIntent(val stack: ProtocolStack, val packet: Packet): Intent()
class StackCreatedIntent(val stack: ProtocolStack): Intent()
class StackDestroyedIntent(val stack: ProtocolStack): Intent()
