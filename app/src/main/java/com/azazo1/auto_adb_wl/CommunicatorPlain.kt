package com.azazo1.auto_adb_wl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CommunicatorPlain : Communicator {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isClosed = AtomicBoolean(false)

    @Volatile
    private var client: Socket? = null

    @Volatile
    private var bufReader: BufferedReader? = null

    @Volatile
    private var bufWriter: BufferedWriter? = null

    @Volatile
    private var connected = AtomicBoolean(false)

    override suspend fun connect(serverAddress: InetSocketAddress) {
        check(!isClosed.get()) { "Communicator already closed." }

        // 已连接则直接返回
        if (connected.get()) return

        withContext(Dispatchers.IO) {
            client = Socket(serverAddress.address, serverAddress.port)
            bufReader = BufferedReader(InputStreamReader(client!!.inputStream))
            bufWriter = BufferedWriter(OutputStreamWriter(client!!.outputStream))
        }
        connected.set(true)
    }

    override suspend fun receive(): String? {
        return suspendCoroutine {
            it.resume(bufReader?.readLine())
        }
    }

    override suspend fun send(message: String) {
        check(!isClosed.get()) { "Communicator is closed." }
        check(connected.get()) { "Not connected yet. Call connect() first." }
        withContext(Dispatchers.IO) {
            bufWriter?.write(message.trimEnd('\n') + "\n")
            bufWriter?.flush() // 这行有时会把 pair 的那个弹出窗口挤掉, 不知道为什么.
        }
    }

    override fun close() {
        if (isClosed.getAndSet(true)) return

        // 取消协程作用域
        ioScope.cancel()

        client?.close()
        client = null

        connected.set(false)
    }
}