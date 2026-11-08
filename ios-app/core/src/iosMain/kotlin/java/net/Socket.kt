package java.net

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.cinterop.*
import platform.posix.*

/** An IPv4 or IPv6 address, as far as the link code needs one: its text form. */
open class InetAddress internal constructor(val hostAddress: String, internal val family: Int) {
    val hostName: String get() = hostAddress
    override fun toString() = "/$hostAddress"
    companion object {
        /** Resolves [host] (usually an IP literal from a pairing code). */
        fun getByName(host: String): InetAddress = resolve(host, 0).let { (address, family) -> InetAddress(address, family) }
        fun getLocalHost(): InetAddress = InetAddress("127.0.0.1", AF_INET)
    }
}
class Inet4Address internal constructor(hostAddress: String) : InetAddress(hostAddress, AF_INET) {
    val isSiteLocalAddress: Boolean get() = hostAddress.startsWith("10.") || hostAddress.startsWith("192.168.") ||
        hostAddress.split('.').let { it.size == 4 && it[0] == "172" && (it[1].toIntOrNull() ?: 0) in 16..31 }
}

open class SocketAddress
class InetSocketAddress(val hostString: String, val port: Int) : SocketAddress() {
    constructor(address: InetAddress, port: Int) : this(address.hostAddress, port)
    val address: InetAddress get() = InetAddress.getByName(hostString)
}

/** The numeric address and family for [host]; throws UnknownHostException. */
internal fun resolve(host: String, port: Int): Pair<String, Int> = memScoped {
    val hints = alloc<addrinfo>().apply { ai_family = AF_UNSPEC; ai_socktype = SOCK_STREAM }
    val result = allocPointerTo<addrinfo>()
    if (getaddrinfo(host, port.toString(), hints.ptr, result.ptr) != 0 || result.value == null) throw UnknownHostException(host)
    try {
        val info = result.value!!.pointed
        val text = allocArray<ByteVar>(64)
        getnameinfo(info.ai_addr, info.ai_addrlen, text, 64u, null, 0u, NI_NUMERICHOST)
        text.toKString() to info.ai_family
    } finally { freeaddrinfo(result.value) }
}

private fun errnoMessage(what: String) = "$what: ${strerror(errno)?.toKString() ?: "error $errno"}"

/** Fills a sockaddr for [host]:[port] and calls [block] with it. */
private fun <T> withAddress(host: String, port: Int, socketType: Int, block: (CPointer<sockaddr>, socklen_t, Int) -> T): T = memScoped {
    val hints = alloc<addrinfo>().apply { ai_family = AF_UNSPEC; ai_socktype = socketType; ai_flags = AI_NUMERICSERV }
    val result = allocPointerTo<addrinfo>()
    if (getaddrinfo(host, port.toString(), hints.ptr, result.ptr) != 0 || result.value == null) throw UnknownHostException(host)
    try { val info = result.value!!.pointed; block(info.ai_addr!!, info.ai_addrlen, info.ai_family) } finally { freeaddrinfo(result.value) }
}

private fun setTimeout(fd: Int, option: Int, millis: Int) = memScoped {
    val tv = alloc<timeval>().apply { tv_sec = (millis / 1000).convert(); tv_usec = ((millis % 1000) * 1000).convert() }
    setsockopt(fd, SOL_SOCKET, option, tv.ptr, sizeOf<timeval>().convert())
}

private fun noSigPipe(fd: Int) = memScoped {
    val on = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, on.ptr, sizeOf<IntVar>().convert())
}

/** A TCP connection over BSD sockets, with the blocking java.net.Socket behaviour the link client expects. */
class Socket() : Closeable {
    private var fd = -1
    @kotlin.concurrent.Volatile private var closed = false
    var soTimeout: Int = 0
        set(value) { field = value; if (fd >= 0) { setTimeout(fd, SO_RCVTIMEO, value); setTimeout(fd, SO_SNDTIMEO, value) } }
    var tcpNoDelay: Boolean = false
    var keepAlive: Boolean = false
    val isClosed: Boolean get() = closed
    val isConnected: Boolean get() = fd >= 0 && !closed
    var inetAddress: InetAddress? = null
        private set
    var port: Int = 0
        private set

    constructor(host: String, port: Int) : this() { connect(InetSocketAddress(host, port), 0) }

    fun connect(endpoint: SocketAddress, timeout: Int = 0) {
        val target = endpoint as InetSocketAddress
        withAddress(target.hostString, target.port, SOCK_STREAM) { address, length, family ->
            val s = socket(family, SOCK_STREAM, 0)
            if (s < 0) throw SocketException(errnoMessage("socket"))
            noSigPipe(s)
            val flags = fcntl(s, F_GETFL, 0)
            fcntl(s, F_SETFL, flags or O_NONBLOCK)
            val started = platform.posix.connect(s, address, length)
            if (started != 0 && errno != EINPROGRESS) { val m = errnoMessage("Couldn't connect to ${target.hostString}"); platform.posix.close(s); throw ConnectException(m) }
            if (started != 0) memScoped {
                val waiting = alloc<pollfd>().apply { this.fd = s; events = POLLOUT.toShort() }
                val ready = platform.posix.poll(waiting.ptr, 1u, if (timeout > 0) timeout else 30_000)
                if (ready <= 0) { platform.posix.close(s); throw SocketTimeoutException("connect timed out") }
                val error = alloc<IntVar>(); val size = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
                getsockopt(s, SOL_SOCKET, SO_ERROR, error.ptr, size.ptr)
                if (error.value != 0) { platform.posix.close(s); throw ConnectException("Couldn't connect to ${target.hostString}: ${strerror(error.value)?.toKString()}") }
            }
            fcntl(s, F_SETFL, flags and O_NONBLOCK.inv())
            fd = s
            inetAddress = InetAddress(target.hostString, family)
            port = target.port
            if (soTimeout > 0) soTimeout = soTimeout
        }
    }

    private val input = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (closed) throw SocketException("Socket closed")
            val n = b.usePinned { platform.posix.recv(fd, it.addressOf(off), len.convert(), 0) }.toInt()
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) throw SocketTimeoutException("Read timed out")
                throw SocketException(errnoMessage("Connection lost"))
            }
            return if (n == 0) -1 else n
        }
        override fun close() = this@Socket.close()
    }
    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off; val end = off + len
            while (at < end) {
                if (closed) throw SocketException("Socket closed")
                val n = b.usePinned { platform.posix.send(fd, it.addressOf(at), (end - at).convert(), 0) }.toInt()
                if (n < 0) {
                    if (errno == EINTR) continue
                    if (errno == EAGAIN || errno == EWOULDBLOCK) throw SocketTimeoutException("Write timed out")
                    throw SocketException(errnoMessage("Connection lost"))
                }
                at += n
            }
        }
        override fun close() = this@Socket.close()
    }

    fun getInputStream(): InputStream { if (fd < 0) throw SocketException("Not connected"); return input }
    fun getOutputStream(): OutputStream { if (fd < 0) throw SocketException("Not connected"); return output }
    val inputStream: InputStream get() = getInputStream()
    val outputStream: OutputStream get() = getOutputStream()

    fun shutdownOutput() { if (fd >= 0) platform.posix.shutdown(fd, SHUT_WR) }

    override fun close() {
        if (closed) return
        closed = true
        // Wakes a thread blocked in recv on this socket before the descriptor goes away.
        if (fd >= 0) { platform.posix.shutdown(fd, SHUT_RDWR); platform.posix.close(fd) }
    }
}

class DatagramPacket(var data: ByteArray, var offset: Int, var length: Int) {
    constructor(data: ByteArray, length: Int) : this(data, 0, length)
    constructor(data: ByteArray, length: Int, address: InetAddress, port: Int) : this(data, 0, length) { this.address = address; this.port = port }
    var address: InetAddress = InetAddress("0.0.0.0", AF_INET)
    var port: Int = 0
    fun getData() = data
}

/**
 * UDP over BSD sockets. Broadcasting on iOS needs Apple's multicast entitlement, which a sideloaded app doesn't
 * have, so a broadcast send fails with an IOException; the link client treats that as "not found" and keeps
 * using the addresses it already knows.
 */
class DatagramSocket(port: Int = 0) : Closeable {
    private val fd = socket(AF_INET, SOCK_DGRAM, 0).also { if (it < 0) throw SocketException(errnoMessage("socket")) }
    @kotlin.concurrent.Volatile private var closed = false
    val isClosed get() = closed
    var soTimeout: Int = 0
        set(value) { field = value; setTimeout(fd, SO_RCVTIMEO, value) }
    var broadcast: Boolean = false
        set(value) { field = value; memScoped { val on = alloc<IntVar>().apply { this.value = if (value) 1 else 0 }; setsockopt(fd, SOL_SOCKET, SO_BROADCAST, on.ptr, sizeOf<IntVar>().convert()) } }

    init {
        if (port != 0) memScoped {
            val reuse = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())
            val address = alloc<sockaddr_in>().apply {
                sin_len = sizeOf<sockaddr_in>().convert(); sin_family = AF_INET.convert(); sin_port = swap(port); sin_addr.s_addr = 0u
            }
            if (platform.posix.bind(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) { platform.posix.close(fd); throw SocketException(errnoMessage("bind")) }
        }
    }

    private fun swap(port: Int): UShort = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()

    fun send(packet: DatagramPacket) {
        val target = packet.address
        val sent = withAddress(target.hostAddress, packet.port, SOCK_DGRAM) { address, length, _ ->
            packet.data.usePinned { sendto(fd, it.addressOf(packet.offset), packet.length.convert(), 0, address, length) }
        }
        if (sent < 0) throw SocketException(errnoMessage("send"))
    }

    fun receive(packet: DatagramPacket) = memScoped {
        val from = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        val n = packet.data.usePinned { recvfrom(fd, it.addressOf(packet.offset), (packet.data.size - packet.offset).convert(), 0, from.ptr.reinterpret(), length.ptr) }.toInt()
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) throw SocketTimeoutException("Receive timed out")
            throw SocketException(errnoMessage("receive"))
        }
        packet.length = n
        val text = allocArray<ByteVar>(64)
        getnameinfo(from.ptr.reinterpret(), length.value, text, 64u, null, 0u, NI_NUMERICHOST)
        packet.address = Inet4Address(text.toKString())
        packet.port = swap(from.sin_port.toInt()).toInt()
    }

    override fun close() { if (!closed) { closed = true; platform.posix.shutdown(fd, SHUT_RDWR); platform.posix.close(fd) } }
}
