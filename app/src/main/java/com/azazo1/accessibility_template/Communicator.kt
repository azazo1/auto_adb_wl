package com.azazo1.accessibility_template

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class Communicator(private val listener: CommunicationListener?) {

    private val TAG = "Communicator"
    private val SERVER_IP = "YOUR_SERVER_IP" // 替换为你的服务器IP
    private val SERVER_PORT = 443 // 替换为你的服务器端口 (TLS/SSL 默认端口通常是 443)

    private var sslSocket: SSLSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    interface CommunicationListener {
        fun onConnected()
        fun onMessageReceived(message: String)
        fun onError(errorMessage: String)
        fun onDisconnected()
    }

    fun connect() {
        thread {
            try {
                // !!! 警告: 忽略证书验证 !!!
                // 创建一个信任所有证书的 TrustManager
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?
                    ) {
                    }

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?
                    ) {
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate>? {
                        return null
                    }
                })

                // 获取 SSLContext 并初始化，使用信任所有证书的 TrustManager
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())

                // 获取 SSLSocketFactory
                val sslSocketFactory = sslContext.getSocketFactory()

                // 创建 SSLSocket 并连接服务器
                sslSocket = sslSocketFactory.createSocket(SERVER_IP, SERVER_PORT) as SSLSocket

                outputStream = sslSocket?.getOutputStream()
                inputStream = sslSocket?.getInputStream()

                Log.d(TAG, "Connected to server via TLS/SSL.")
                listener?.onConnected()

                // 开始接收消息
                startReceivingMessages()

            } catch (e: IOException) {
                Log.e(TAG, "Connection error: ${e.message}")
                listener?.onError("Connection error: ${e.message}")
                disconnect()
            } catch (e: Exception) { // 捕获 SSLContext 初始化等其他异常
                Log.e(TAG, "SSL/TLS setup error: ${e.message}")
                listener?.onError("SSL/TLS setup error: ${e.message}")
                disconnect()
            }
        }
    }

    private fun startReceivingMessages() {
        thread {
            try {
                val buffer = ByteArray(4096) // 接收缓冲区
                var bytesRead: Int
                while (inputStream?.read(buffer)
                        .also { bytesRead = it ?: -1 } != -1 && bytesRead != 0
                ) {
                    // 接收到数据 (TLS/SSL 会自动解密)
                    val receivedData = buffer.copyOfRange(0, bytesRead)
                    val message = String(receivedData)

                    // 注意：在实际应用中，您需要更健壮的方式来处理接收到的数据块，
                    // 特别是当消息可能被分成多个 TCP 包发送时。
                    // 您可能需要一个消息边界协议。

                    Log.d(TAG, "Received message: $message")
                    listener?.onMessageReceived(message)
                }
            } catch (e: IOException) {
                // 连接断开或其他接收错误
                Log.e(TAG, "Error receiving messages: ${e.message}")
                listener?.onError("Error receiving messages: ${e.message}")
            } finally {
                disconnect()
            }
        }
    }

    fun sendMessage(message: String) {
        if (outputStream == null) {
            Log.e(TAG, "Cannot send message: not connected.")
            listener?.onError("Cannot send message: not connected.")
            return
        }

        thread {
            try {
                // 发送数据 (TLS/SSL 会自动加密)
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Sent message.")

            } catch (e: IOException) {
                Log.e(TAG, "Error sending message: ${e.message}")
                listener?.onError("Error sending message: ${e.message}")
            }
        }
    }

    fun disconnect() {
        try {
            if (sslSocket != null && sslSocket?.isClosed == false) {
                sslSocket?.close()
                Log.d(TAG, "Disconnected from server.")
                listener?.onDisconnected()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
            listener?.onError("Error disconnecting: ${e.message}")
        } finally {
            sslSocket = null
            outputStream = null
            inputStream = null
        }
    }
}
