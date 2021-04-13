package com.projectswg.forwarder.resources.recording

import java.io.*
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.ZoneId
import java.util.*

class PacketRecorder @Throws(FileNotFoundException::class)
constructor(file: File) : AutoCloseable, Closeable {
	
	private val dataOut: DataOutputStream = DataOutputStream(FileOutputStream(file))
	
	init {
		writeHeader()
	}
	
	@Throws(IOException::class)
	override fun close() {
		dataOut.close()
	}
	
	fun record(server: Boolean, data: ByteArray) {
		synchronized(dataOut) {
			try {
				dataOut.writeBoolean(server)
				dataOut.writeLong(System.currentTimeMillis())
				dataOut.writeShort(data.size)
				dataOut.write(data)
			} catch (e: IOException) {
				e.printStackTrace()
			}
			
		}
	}
	
	private fun writeHeader() {
		try {
			dataOut.writeByte(VERSION.toInt())
			writeSystemHeader()
		} catch (e: IOException) {
			e.printStackTrace()
		}
		
	}
	
	private fun writeSystemHeader() {
		val os = ManagementFactory.getOperatingSystemMXBean()
		val systemStrings = TreeMap<String, String>()
		systemStrings["os.arch"] = os.arch
		systemStrings["os.details"] = os.name + ":" + os.version
		systemStrings["os.processor_count"] = os.availableProcessors.toString()
		systemStrings["java.version"] = System.getProperty("java.version")
		systemStrings["java.vendor"] = System.getProperty("java.vendor")
		systemStrings["time.time_zone"] = ZoneId.systemDefault().id
		systemStrings["time.current_time"] = Instant.now().toString()
		try {
			dataOut.writeByte(systemStrings.size) // Count of strings
			for ((key, value) in systemStrings)
				dataOut.writeUTF("$key=$value")
		} catch (e: IOException) {
			e.printStackTrace()
		}
		
	}
	
	companion object {
		
		private const val VERSION: Byte = 3
		
	}
	
}
