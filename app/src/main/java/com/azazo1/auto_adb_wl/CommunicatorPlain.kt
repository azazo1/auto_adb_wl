package com.azazo1.auto_adb_wl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class CommunicatorPlain : Communicator {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val messageChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private val isClosed = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var connected = AtomicBoolean(false)

    override fun connect(serverAddress: InetSocketAddress) {
        check(!isClosed.get()) { "Communicator already closed." }

        // 已连接则直接返回
        if (connected.get()) return

        val scheme = if (serverAddress.port == 443) "wss" else "ws"
        val host = serverAddress.hostString
        val port = serverAddress.port
        val url = "$scheme://$host:$port/"

        // 为 WebSocket 配置一个适合的 OkHttpClient
        val okClient = OkHttpClient.Builder()
            // WebSocket 建议 readTimeout=0 让其长连
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // 定期心跳，避免 NAT 或运营商闲置超时（按需调整）
            .pingInterval(20, TimeUnit.SECONDS)
            // 连接超时（按需调整）
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected.set(true)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // 文本消息入队
                ioScope.launch {
                    messageChannel.send(text)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // 如果服务器偶尔发二进制，这里转成 Base64 或 UTF-8
                val text = try {
                    bytes.utf8()
                } catch (_: Throwable) {
                    bytes.base64()
                }
                ioScope.launch {
                    messageChannel.send(text)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                // 先告诉服务器我们同意关闭
                ws.close(code, reason)
                connected.set(false)
                // 关闭消息通道（不会再有新消息）
                messageChannel.close()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected.set(false)
                messageChannel.close()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                // 将错误信息以特殊格式发出去，调用方可选择性处理（也可以不发送）
                ioScope.launch {
                    // 不终止通道，方便调用方继续 receive 等待后续重连（如果你要做的话）
                    // 这里选择发送一条“系统提示”型消息；也可以选择直接关闭通道
                    messageChannel.trySend("[WS_ERROR] ${t.message ?: "unknown"}")
                }
            }
        }

        val ws = okClient.newWebSocket(request, listener)

        // 保存引用便于关闭
        client = okClient
        webSocket = ws
    }

    override suspend fun receive(): String? {
        // 协程挂起直到有消息或通道关闭
        // 注意：该方法不是挂起函数，但 Channel.receive() 是挂起的
        // 因此这里用 runBlocking 包一层，保持接口签名不变
        // 如果已经关闭且通道也关了，返回 null
        if (isClosed.get() && messageChannel.isClosedForReceive) return null
        return try {
            messageChannel.receiveCatching().getOrNull()
        } catch (_: CancellationException) {
            null
        }
    }

    override fun tryReceive(): String? {
        // 非阻塞拿一条
        return messageChannel.tryReceive().getOrNull()
    }

    override fun send(message: String) {
        check(!isClosed.get()) { "Communicator is closed." }
        check(connected.get()) { "Not connected yet. Call connect() first." }
        val ok = webSocket?.send(message) ?: false
        check(ok) { "WebSocket send failed." }
    }

    override fun close() {
        if (isClosed.getAndSet(true)) return

        // 优雅关闭 WebSocket
        try {
            webSocket?.close(1000, "client closing")
        } catch (_: Throwable) { /* ignore */
        }

        webSocket = null

        // 关闭消息通道（让 receive() 能返回 null）
        messageChannel.close()

        // 取消协程作用域
        ioScope.cancel()

        // 关闭 OkHttp dispatcher / 线程
        try {
            client?.dispatcher?.executorService?.shutdown()
            client?.connectionPool?.evictAll()
        } catch (_: Throwable) { /* ignore */
        }
        client = null

        connected.set(false)
    }
}