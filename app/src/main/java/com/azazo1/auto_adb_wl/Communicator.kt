package com.azazo1.auto_adb_wl

import java.io.Closeable
import java.net.InetSocketAddress

interface Communicator : Closeable {
    fun connect(serverAddress: InetSocketAddress)

    /**
     * 服务器终止时返回 null, 其他情况下返回获取到的字符串
     */
    suspend fun receive(): String?

    /**
     * 立刻不阻塞
     */
    fun tryReceive(): String?

    /**
     * 发送消息
     */
    fun send(message: String)
}
