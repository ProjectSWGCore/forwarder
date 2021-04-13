package com.projectswg.forwarder.intents

import com.projectswg.forwarder.Forwarder
import me.joshlarson.jlcommon.control.Intent
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipOutputStream

class StartForwarderIntent(val data: Forwarder.ForwarderData): Intent()
class StopForwarderIntent: Intent()
class ClientCrashedIntent(val outputStream: ZipOutputStream, val fileMutex: ReentrantLock = ReentrantLock()): Intent()
