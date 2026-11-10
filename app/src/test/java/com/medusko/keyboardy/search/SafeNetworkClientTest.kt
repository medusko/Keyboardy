package com.medusko.keyboardy.search

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SafeNetworkClientTest {

    @Test(expected = UnknownHostException::class)
    fun `SafeDns rejects hostname resolving to loopback address`() {
        val fakeDns = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        SafeDns(fakeDns).lookup("127.0.0.1.nip.io")
    }

    @Test(expected = UnknownHostException::class)
    fun `SafeDns rejects hostname resolving to CGNAT or IPv6 ULA`() {
        val fakeDns = object : Dns {
            override fun lookup(hostname: String) = listOf(
                InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1)),
                InetAddress.getByName("fd12:3456:789a::1")
            )
        }
        SafeDns(fakeDns).lookup("rebind.example.com")
    }

    @Test
    fun `SafeDns filters out private addresses and keeps public addresses`() {
        val publicAddr = InetAddress.getByAddress("example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
        val privateAddr = InetAddress.getByAddress("example.com", byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
        val fakeDns = object : Dns {
            override fun lookup(hostname: String) = listOf(privateAddr, publicAddr)
        }

        val result = SafeDns(fakeDns).lookup("example.com")
        assertEquals(listOf(publicAddr), result)
    }

    @Test
    fun `SafeNetworkClient blocks direct loopback request without opening socket`() {
        val connections = AtomicInteger(0)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { server ->
            val acceptThread = thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        server.accept().use { connections.incrementAndGet() }
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            val result = SafeNetworkClient.getBytesBoundedBlocking(
                url = "http://127.0.0.1:${server.localPort}/secret",
                maxBytes = 1024
            )
            assertNull(result)
            server.close()
            acceptThread.join(500)
            assertEquals(0, connections.get())
        }
    }

    @Test
    fun `SafeNetworkClient blocks redirect to private or loopback address`() {
        val targetHits = AtomicInteger(0)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { targetServer ->
            ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { bounceServer ->
                val targetThread = thread(isDaemon = true) {
                    while (!targetServer.isClosed) {
                        try {
                            targetServer.accept().use { targetHits.incrementAndGet() }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
                val bounceThread = thread(isDaemon = true) {
                    try {
                        bounceServer.accept().use { sock ->
                            sock.getInputStream()
                            val resp = (
                                "HTTP/1.1 302 Found\r\n" +
                                    "Location: http://127.0.0.1:${targetServer.localPort}/admin\r\n" +
                                    "Content-Length: 0\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII)
                            sock.getOutputStream().write(resp)
                            sock.getOutputStream().flush()
                        }
                    } catch (_: Exception) {
                    }
                }

                // Allow only "bounce.example.com" to reach the local bounce server; target redirect
                // to 127.0.0.1 must still be blocked by SafeUrlInterceptor.
                val testDns = object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return if (hostname == "bounce.example.com") {
                            listOf(InetAddress.getLoopbackAddress())
                        } else {
                            SafeDns().lookup(hostname)
                        }
                    }
                }
                val client = SafeNetworkClient.createClient(dns = testDns)
                val result = SafeNetworkClient.getBytesBoundedBlocking(
                    url = "http://bounce.example.com:${bounceServer.localPort}/start",
                    maxBytes = 1024,
                    client = client
                )

                assertNull(result)
                assertTrue(targetHits.get() == 0)
                bounceThread.join(500)
                targetServer.close()
                targetThread.join(500)
            }
        }
    }
}
