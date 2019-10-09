module com.projectswg.forwarder {
	requires com.projectswg.common;
	requires com.projectswg.holocore.client;
	requires org.jetbrains.annotations;
	requires me.joshlarson.jlcommon;
	requires me.joshlarson.jlcommon.network;
	
	requires java.management;
	requires kotlin.stdlib;
	
	exports com.projectswg.forwarder;
	exports com.projectswg.forwarder.intents;
	
	opens com.projectswg.forwarder.services.client to me.joshlarson.jlcommon;
	opens com.projectswg.forwarder.services.crash to me.joshlarson.jlcommon;
	opens com.projectswg.forwarder.services.server to me.joshlarson.jlcommon;
}
