package com.projectswg.forwarder.services.crash

import com.projectswg.forwarder.intents.ClientCrashedIntent
import com.projectswg.forwarder.intents.DataPacketInboundIntent
import com.projectswg.forwarder.intents.DataPacketOutboundIntent
import com.projectswg.forwarder.resources.recording.PacketRecorder
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PacketRecordingService : Service() {
	
	private var recorderPath: Path? = null
	private var recorder: PacketRecorder? = null
	
	override fun initialize(): Boolean {
		try {
			recorderPath = Files.createTempFile("HolocorePackets", ".hcap")
			recorder = PacketRecorder(recorderPath!!.toFile())
		} catch (e: IOException) {
			Log.a(e)
			return false
		}
		
		return true
	}
	
	override fun terminate(): Boolean {
		try {
			if (recorder != null)
				recorder!!.close()
			return recorderPath!!.toFile().delete()
		} catch (e: IOException) {
			Log.w(e)
			return false
		}
		
	}
	
	@IntentHandler
	private fun handleClientCrashedIntent(cci: ClientCrashedIntent) {
		try {
			val data = Files.readAllBytes(recorderPath!!)
			val entry = ZipEntry("packet_log.hcap")
			entry.time = System.currentTimeMillis()
			entry.size = data.size.toLong()
			entry.method = ZipOutputStream.DEFLATED
			synchronized(cci.fileMutex) {
				cci.outputStream.putNextEntry(entry)
				cci.outputStream.write(data)
				cci.outputStream.closeEntry()
			}
		} catch (e: IOException) {
			Log.w("Failed to write packet data to crash log - IOException")
			Log.w(e)
		}
		
	}
	
	@IntentHandler
	private fun handleDataPacketInboundIntent(dpii: DataPacketInboundIntent) {
		recorder?.record(false, dpii.data)
	}
	
	@IntentHandler
	private fun handleDataPacketOutboundIntent(dpoi: DataPacketOutboundIntent) {
		recorder?.record(true, dpoi.data)
	}
	
}
