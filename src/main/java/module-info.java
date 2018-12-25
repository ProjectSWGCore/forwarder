module com.projectswg.forwarder {
	requires com.projectswg.common;
	requires com.projectswg.holocore.client;
	requires org.jetbrains.annotations;
	
	requires java.management;
	
	exports com.projectswg.forwarder;
	
	opens com.projectswg.forwarder.services.client to me.joshlarson.jlcommon;
	opens com.projectswg.forwarder.services.crash to me.joshlarson.jlcommon;
	opens com.projectswg.forwarder.services.server to me.joshlarson.jlcommon;
}
