package com.projectswg.forwarder

import com.projectswg.forwarder.intents.*
import me.joshlarson.jlcommon.concurrency.Delay
import me.joshlarson.jlcommon.control.IntentManager
import me.joshlarson.jlcommon.control.Manager
import me.joshlarson.jlcommon.log.Log
import java.io.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Forwarder {
	
	val data: ForwarderData = ForwarderData()
	val intentManager: IntentManager = IntentManager(false, Runtime.getRuntime().availableProcessors()*2)
	private val connected: AtomicBoolean = AtomicBoolean(false)
	
	fun readClientOutput(input: InputStream): File? {
		val output = ByteArrayOutputStream()
		try {
			input.transferTo(output)
		} catch (e: IOException) {
			Log.w("IOException while reading client output")
			Log.w(e)
		}
		
		return onClientClosed(output.toString())
	}
	
	private fun onClientClosed(clientOutput: String): File? {
		if (!connected.get())
			return null
		val output: File
		try {
			output = Files.createTempFile("HolocoreCrashLog", ".zip").toFile()
		} catch (e: IOException) {
			Log.e("Failed to write crash log! Could not create temp file.")
			return null
		}
		
		try {
			ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
				run {
					val data = clientOutput.toByteArray(StandardCharsets.UTF_8)
					val entry = ZipEntry("output.txt")
					entry.time = System.currentTimeMillis()
					entry.size = data.size.toLong()
					entry.method = ZipOutputStream.DEFLATED
					zip.putNextEntry(entry)
					zip.write(data)
					zip.closeEntry()
				}
				val cci = ClientCrashedIntent(zip, ReentrantLock())
				cci.broadcast(intentManager)
				val startSleep = System.nanoTime()
				while (!cci.isComplete && System.nanoTime() - startSleep < 1E9)
					Delay.sleepMilli(10)
				return output
			}
		} catch (e: IOException) {
			Log.e("Failed to write crash log! %s: %s", e.javaClass.name, e.message)
			return null
		}
		
	}
	
	fun run() {
		intentManager.registerForIntent(ClientConnectedIntent::class.java, "Forwarder#handleClientConnectedIntent") { connected.set(true) }
		intentManager.registerForIntent(ClientDisconnectedIntent::class.java, "Forwarder#handleClientDisconnectedIntent") { connected.set(false) }
		
		val primary = ConnectionManager()
		primary.setIntentManager(intentManager)
		val managers = listOf<Manager>(primary)
		Manager.start(managers)
		StartForwarderIntent(data).broadcast(intentManager)
		Manager.run(managers, 100)
		val intentTimes = intentManager.speedRecorder.sortedByDescending { it.totalTime }
		Log.i("    Intent Times: [%d]", intentTimes.size)
		Log.i("        %-30s%-60s%-40s%-10s%-20s", "Intent", "Receiver Class", "Receiver Method", "Count", "Time")
		for (record in intentTimes) {
			var receiverName = record.key.toString()
			if (receiverName.indexOf('$') != -1)
				receiverName = receiverName.substring(0, receiverName.indexOf('$'))
			receiverName = receiverName.replace("com.projectswg.forwarder.services.", "")
			val intentName = record.intent.simpleName
			val recordCount = record.count.toString()
			val recordTime = String.format("%.6fms", record.totalTime / 1E6)
			val receiverSplit = receiverName.split("#".toRegex(), 2).toTypedArray()
			if (receiverSplit.size >= 2)
				Log.i("        %-30s%-60s%-40s%-10s%-20s", intentName, receiverSplit[0], receiverSplit[1], recordCount, recordTime)
			else
				Log.i("        %-30s%-100s%-10s%-20s", intentName, receiverSplit[0], recordCount, recordTime)
		}
		StopForwarderIntent().broadcast(intentManager)
		Manager.stop(managers)
		
		intentManager.close(false, 1000)
		primary.setIntentManager(null)
	}
	
	class ForwarderData internal constructor() {
		
		var baseConnectionUri: String? = null
		var username: String? = null
		var password: String? = null
		var protocolVersion: String? = null
		
		var loginPort = 0
		var zonePort = 0
		var pingPort = 0
		var outboundTunerMaxSend = 100
		var outboundTunerInterval = 20
		var crashed: Boolean = false
		
		val connectionUri: URI
			get() {
				val encodedUsername = Base64.getEncoder().encodeToString((username ?: "").encodeToByteArray())
				val encodedPassword = Base64.getEncoder().encodeToString((password ?: "").encodeToByteArray())
				val encodedProtocolVersion = Base64.getEncoder().encodeToString((protocolVersion ?: "").encodeToByteArray())
				val connectionUriStr = "$baseConnectionUri?username=$encodedUsername&password=$encodedPassword&protocolVersion=$encodedProtocolVersion"
				return URI(connectionUriStr)
			}
		
	}
	
}
