package com.azazo1.auto_adb_wl

import java.io.Closeable
import java.net.InetSocketAddress

interface Communicator : Closeable {
    suspend fun connect(serverAddress: InetSocketAddress)

    /**
     * 服务器终止时返回 null, 其他情况下返回获取到的字符串
     */
    suspend fun receive(): String?

    /**
     * 发送消息
     */
    suspend fun send(message: String)
}
