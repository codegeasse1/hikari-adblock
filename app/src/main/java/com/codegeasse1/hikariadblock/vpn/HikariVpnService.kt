package com.codegeasse1.hikariadblock.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.codegeasse1.hikariadblock.data.Blocklist
import com.codegeasse1.hikariadblock.data.Preferences
import com.codegeasse1.hikariadblock.data.QueryLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class HikariVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.codegeasse1.hikariadblock.START"
        const val ACTION_STOP = "com.codegeasse1.hikariadblock.STOP"
        const val NOTIF_ID = 1001
        @Volatile
        var running = false
            private set
        @Volatile
        var stopRequested = false
            private set

        fun requestStop() {
            stopRequested = true
        }
    }

    private var interfaceFd: ParcelFileDescriptor? = null
    private var readThread: Thread? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = HashMap<String, Client>()
    private val outputLock = Any()

    @Volatile
    private var customDns: String? = null

    private class Client(val socket: DatagramSocket) {
        @Volatile
        var lastUsed: Long = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || stopRequested) {
            stopRequested = false
            teardown()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        stopRequested = false
        if (interfaceFd == null) {
            startForegroundCompat()
            startInternal()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        NotificationHelper.ensureChannel(this)
        val notification = NotificationHelper.build(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startInternal() {
        val builder = Builder()
        builder.setSession("Hikari AdBlock")
        builder.setMtu(1500)
        builder.addAddress("10.80.0.1", 32)
        builder.addRoute("0.0.0.0", 0)
        runCatching { builder.addRoute("::", 0) }
        val fd = builder.establish()
        if (fd == null) {
            stopSelf()
            return
        }
        interfaceFd = fd
        running = true
        VpnController.setRunning(true)
        scope.launch {
            customDns = Preferences.customDnsOnce(this@HikariVpnService)
                .split(',', ';')
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
            Blocklist.loadIfNeeded(this@HikariVpnService)
            startReadLoop(fd)
        }
        scope.launch {
            while (!stopRequested && running) {
                delay(100)
            }
            if (stopRequested) {
                teardown()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun teardown() {
        running = false
        VpnController.setRunning(false)
        synchronized(clients) {
            for (c in clients.values) {
                runCatching { c.socket.close() }
            }
            clients.clear()
        }
        runCatching { readThread?.interrupt() }
        runCatching { interfaceFd?.close() }
        interfaceFd = null
    }

    private fun startReadLoop(fd: ParcelFileDescriptor) {
        if (readThread != null) return
        readThread = Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(65536)
            while (running && !stopRequested) {
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    break
                }
                if (n < 0) break
                if (n < 28) continue
                try {
                    handlePacket(buffer, n, output)
                } catch (_: Exception) {
                    // ignore and continue
                }
            }
            running = false
            VpnController.setRunning(false)
        }.apply {
            isDaemon = true
            name = "hikari-vpn-read"
        }
        readThread?.start()
    }

    private fun handlePacket(buf: ByteArray, len: Int, output: FileOutputStream) {
        val version = (buf[0].toInt() and 0xF0) shr 4
        if (version == 4) {
            val hdr = PacketBuilder.parseIPv4Header(buf, 0, len) ?: return
            val udpStart = hdr.ihl
            if (hdr.protocol == 17 && len >= udpStart + 8) {
                val srcPort = ((buf[udpStart].toInt() and 0xFF) shl 8) or (buf[udpStart + 1].toInt() and 0xFF)
                val dstPort = ((buf[udpStart + 2].toInt() and 0xFF) shl 8) or (buf[udpStart + 3].toInt() and 0xFF)
                if (dstPort == 53) {
                    val payloadStart = udpStart + 8
                    val payloadLen = hdr.totalLen - payloadStart
                    if (payloadLen > 0 && payloadStart + payloadLen <= len) {
                        handleDns(
                            payload = buf.copyOfRange(payloadStart, payloadStart + payloadLen),
                            srcIp = PacketBuilder.ipv4ToString(buf, 12), srcPort = srcPort,
                            dstIp = PacketBuilder.ipv4ToString(buf, 16),
                            srcBytes = hdr.src, dstBytes = hdr.dst, isV6 = false, output = output
                        )
                        return
                    }
                }
            }
            echo(buf, len, output)
        } else if (version == 6) {
            val hdr = PacketBuilder.parseIPv6Header(buf, 0, len) ?: return
            val udpStart = hdr.udpOffset
            if (len >= udpStart + 8) {
                val srcPort = ((buf[udpStart].toInt() and 0xFF) shl 8) or (buf[udpStart + 1].toInt() and 0xFF)
                val dstPort = ((buf[udpStart + 2].toInt() and 0xFF) shl 8) or (buf[udpStart + 3].toInt() and 0xFF)
                if (dstPort == 53) {
                    val udpLen = ((buf[udpStart + 4].toInt() and 0xFF) shl 8) or (buf[udpStart + 5].toInt() and 0xFF)
                    val payloadLen = if (udpLen >= 8) udpLen - 8 else 0
                    if (payloadLen > 0 && udpStart + 8 + payloadLen <= len) {
                        handleDns(
                            payload = buf.copyOfRange(udpStart + 8, udpStart + 8 + payloadLen),
                            srcIp = PacketBuilder.ipv6ToString(buf, 8), srcPort = srcPort,
                            dstIp = PacketBuilder.ipv6ToString(buf, 24),
                            srcBytes = hdr.src, dstBytes = hdr.dst, isV6 = true, output = output
                        )
                        return
                    }
                }
            }
            echo(buf, len, output)
        }
    }

    private fun echo(buf: ByteArray, len: Int, output: FileOutputStream) {
        writePacket(buf.copyOfRange(0, len), output)
    }

    private fun handleDns(
        payload: ByteArray, srcIp: String, srcPort: Int, dstIp: String,
        srcBytes: ByteArray, dstBytes: ByteArray, isV6: Boolean, output: FileOutputStream
    ) {
        val name = DnsMessage.questionName(payload, 0)
        val blocked = name != null && Blocklist.isBlocked(name)
        QueryLog.onQuery(name ?: "<unparseable>", blocked)
        if (blocked) {
            val resp = DnsMessage.buildNxDomain(payload)
            val packet = if (isV6) {
                PacketBuilder.ipv6Packet(dstBytes, 53, srcBytes, srcPort, resp)
            } else {
                PacketBuilder.ipv4Packet(dstBytes, 53, srcBytes, srcPort, resp)
            }
            writePacket(packet, output)
        } else if (name != null) {
            forward(payload, srcIp, srcPort, dstIp, isV6, output)
        }
    }

    private fun forward(payload: ByteArray, srcIp: String, srcPort: Int, dnsIp: String, isV6: Boolean, output: FileOutputStream) {
        val target = customDns ?: dnsIp
        val key = "$srcIp:$srcPort->$target"
        val client = synchronized(clients) {
            clients.getOrPut(key) { createClient(srcIp, srcPort, dnsIp, target, isV6, output) }
        }
        client.lastUsed = System.currentTimeMillis()
        try {
            client.socket.send(DatagramPacket(payload, payload.size))
        } catch (e: IOException) {
            removeClient(key)
        }
    }

    private fun createClient(srcIp: String, srcPort: Int, dnsIp: String, target: String, isV6: Boolean, output: FileOutputStream): Client {
        val socket = DatagramSocket()
        socket.setSoTimeout(15000)
        protect(socket)
        val targetAddress = runCatching { InetAddress.getByName(target) }.getOrNull()
            ?: InetAddress.getByName(dnsIp)
        socket.connect(targetAddress, 53)
        val client = Client(socket)
        val key = "$srcIp:$srcPort->$target"
        val srcBytes = InetAddress.getByName(srcIp).address
        val dstBytes = InetAddress.getByName(dnsIp).address
        Thread {
            val rbuf = ByteArray(65536)
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    val pkt = DatagramPacket(rbuf, rbuf.size)
                    socket.receive(pkt)
                    client.lastUsed = System.currentTimeMillis()
                    val resp = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                    val packet = if (isV6) {
                        PacketBuilder.ipv6Packet(dstBytes, 53, srcBytes, srcPort, resp)
                    } else {
                        PacketBuilder.ipv4Packet(dstBytes, 53, srcBytes, srcPort, resp)
                    }
                    writePacket(packet, output)
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - client.lastUsed > 90000) {
                        removeClient(key)
                        break
                    }
                } catch (e: IOException) {
                    removeClient(key)
                    break
                }
            }
            runCatching { socket.close() }
        }.apply {
            isDaemon = true
            name = "hikari-dns-client"
        }.start()
        return client
    }

    private fun removeClient(key: String) {
        synchronized(clients) {
            val c = clients.remove(key)
            if (c != null) {
                runCatching { c.socket.close() }
            }
        }
    }

    private fun writePacket(packet: ByteArray, output: FileOutputStream) {
        synchronized(outputLock) {
            try {
                output.write(packet)
            } catch (_: IOException) {
                // tun closed
            }
        }
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
