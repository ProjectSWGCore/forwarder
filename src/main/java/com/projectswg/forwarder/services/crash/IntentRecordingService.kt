package com.projectswg.forwarder.services.crash

import com.projectswg.forwarder.Forwarder.ForwarderData
import com.projectswg.forwarder.intents.*
import me.joshlarson.jlcommon.control.Intent
import me.joshlarson.jlcommon.control.IntentHandler
import me.joshlarson.jlcommon.control.Service
import me.joshlarson.jlcommon.log.Log
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IntentRecordingService : Service() {
	
	private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm:ss.SSS zzz").withZone(ZoneId.systemDefault())
	
	private var logPath: Path? = null
	private var logWriter: FileWriter? = null
	private var data: ForwarderData? = null
	
	override fun initialize(): Boolean {
		try {
			logPath = Files.createTempFile("HolocoreIntents", ".txt")
			logWriter = FileWriter(logPath!!.toFile())
		} catch (e: IOException) {
			Log.a(e)
			return false
		}
		
		return true
	}
	
	override fun terminate(): Boolean {
		try {
			if (logWriter != null)
				logWriter!!.close()
			if (logPath != null)
				return logPath!!.toFile().delete()
		} catch (e: IOException) {
			Log.w(e)
			return false
		}
		
		return true
	}
	
	@IntentHandler
	private fun handleClientConnectedIntent(cci: ClientConnectedIntent) {
		val data = this.data
		if (data == null)
			log(cci, "")
		else
			log(cci, "Base URL='%s' Username='%s' Login='%d' Zone='%d'", data.baseConnectionUri, data.username, data.loginPort, data.zonePort)
	}
	
	@IntentHandler
	private fun handleClientDisconnectedIntent(cdi: ClientDisconnectedIntent) {
		log(cdi, "")
	}
	
	@IntentHandler
	private fun handleStartForwarderIntent(sfi: StartForwarderIntent) {
		this.data = sfi.data
	}
	
	@IntentHandler
	private fun handleStopForwarderIntent(sfi: StopForwarderIntent) {
		val data = this.data
		if (data == null)
			log(sfi, "")
		else
			log(sfi, "Base URL='%s' Username='%s' Login='%d' Zone='%d'", data.baseConnectionUri, data.username, data.loginPort, data.zonePort)
	}
	
	@IntentHandler
	private fun handleServerConnectedIntent(sci: ServerConnectedIntent) {
		val data = this.data
		if (data == null)
			log(sci, "")
		else
			log(sci, "Base URL='%s' Username='%s' Login='%d' Zone='%d'", data.baseConnectionUri, data.username, data.loginPort, data.zonePort)
	}
	
	@IntentHandler
	private fun handleServerDisconnectedIntent(sdi: ServerDisconnectedIntent) {
		log(sdi, "")
	}
	
	@IntentHandler
	private fun handleClientCrashedIntent(cci: ClientCrashedIntent) {
		log(cci, "")
		try {
			logWriter!!.flush()
			val data = Files.readAllBytes(logPath!!)
			val entry = ZipEntry("log.txt")
			entry.time = System.currentTimeMillis()
			entry.size = data.size.toLong()
			entry.method = ZipOutputStream.DEFLATED
			synchronized(cci.fileMutex) {
				cci.outputStream.putNextEntry(entry)
				cci.outputStream.write(data)
				cci.outputStream.closeEntry()
			}
		} catch (e: IOException) {
			Log.w("Failed to write intent data to crash log - IOException")
			Log.w(e)
		}
		
	}
	
	@Synchronized
	private fun log(i: Intent, message: String, vararg args: Any?) {
		try {
			logWriter!!.write(dateTimeFormatter.format(Instant.now()) + ": " + i.javaClass.simpleName + ' '.toString() + String.format(message, *args) + '\r'.toString() + '\n'.toString())
		} catch (e: IOException) {
			Log.e("Failed to write to intent log")
		}
		
	}
	
}
