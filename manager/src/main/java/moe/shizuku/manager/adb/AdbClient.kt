package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import rikka.core.util.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"
private const val CONNECT_TIMEOUT_MS = 5000
private const val DEFAULT_READ_TIMEOUT_MS = 10000
private const val MAX_DATA_LENGTH = 1024 * 1024

class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : Closeable {

    companion object {
        const val READ_TIMEOUT_MS = DEFAULT_READ_TIMEOUT_MS
    }

    private var socket: Socket? = null
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var tlsSocket: SSLSocket? = null
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private var useTls = false

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    @Throws(IOException::class)
    fun connect() {
        val s = Socket()
        socket = s
        s.soTimeout = readTimeoutMs
        s.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

        plainInputStream = DataInputStream(s.getInputStream())
        plainOutputStream = DataOutputStream(s.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2_sync,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dedupe,openscreen_mdns,audiotrack")

        var message = read()
        if (message.command == A_STLS) {
            if (!BuildUtils.atLeast29) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            val tls = sslContext.socketFactory.createSocket(s, host, port, true) as SSLSocket
            tlsSocket = tls
            tls.soTimeout = readTimeoutMs
            tls.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tls.inputStream)
            tlsOutputStream = DataOutputStream(tls.outputStream)
            useTls = true

            // TLS-era adbd waits for client A_CNXN after handshake
            write(A_CNXN, A_VERSION, A_MAXDATA, "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2_sync,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dedupe,openscreen_mdns,audiotrack")
            message = read()
            if (message.command == A_AUTH) {
                write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
                message = read()
                if (message.command != A_CNXN) {
                    write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                    message = read()
                }
            }
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        // Wait for A_OKAY or A_CLSE, ignoring async/background packets if any
        while (message.command != A_OKAY && message.command != A_CLSE) {
            Log.w(TAG, "Unexpected message before open response: ${message.toStringShort()}, reading next")
            message = read()
        }

        when (message.command) {
            A_OKAY -> {
                val serverRemoteId = message.arg0
                while (true) {
                    message = read()
                    val remoteId = if (message.arg0 != 0) message.arg0 else serverRemoteId
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0 && message.data != null) {
                            listener?.invoke(message.data)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        Log.w(TAG, "Ignored unexpected packet in stream: ${message.toStringShort()}")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int

        if (dataLength < 0 || dataLength > MAX_DATA_LENGTH) {
            error("invalid data_length $dataLength")
        }
        val data: ByteArray?
        if (dataLength > 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        try { tlsSocket?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
    }
}
