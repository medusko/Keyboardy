package com.medusko.keyboardy.search

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class SafeNetworkClientTest {

    private data class TestTlsMaterial(
        val sslContext: SSLContext,
        val trustManager: X509TrustManager
    )

    private fun createSelfSignedTlsMaterial(): TestTlsMaterial {
        val keystoreFile = File.createTempFile("keyboardy-test-", ".p12")
        keystoreFile.delete()
        try {
            val password = "changeit".toCharArray()
            val keytoolPath = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process = ProcessBuilder(
                keytoolPath,
                "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=bounce.example.com",
                "-storetype", "PKCS12",
                "-keystore", keystoreFile.absolutePath,
                "-storepass", "changeit",
                "-keypass", "changeit"
            ).redirectErrorStream(true).start()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "keytool failed ($exitCode): ${process.inputStream.bufferedReader().readText()}" }

            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(keystoreFile).use { keyStore.load(it, password) }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf(trustManager), null)
            }
            return TestTlsMaterial(sslContext = sslContext, trustManager = trustManager)
        } finally {
            keystoreFile.delete()
        }
    }

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
    fun `SafeNetworkClient blocks cleartext http requests immediately`() {
        val result = SafeNetworkClient.getBytesBoundedBlocking(
            url = "http://example.com/photo.jpg",
            maxBytes = 1024
        )
        assertNull(result)
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
                url = "https://127.0.0.1:${server.localPort}/secret",
                maxBytes = 1024
            )
            assertNull(result)
            server.close()
            acceptThread.join(500)
            assertEquals(0, connections.get())
        }
    }

    @Test
    fun `SafeNetworkClient blocks redirect to private or loopback address and http downgrades`() {
        val tls = createSelfSignedTlsMaterial()
        val targetHits = AtomicInteger(0)
        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { targetServer ->
            (tls.sslContext.serverSocketFactory.createServerSocket(
                0,
                5,
                InetAddress.getLoopbackAddress()
            ) as SSLServerSocket).use { bounceServer ->
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
                    repeat(2) { idx ->
                        try {
                            bounceServer.accept().use { sock ->
                                sock.getInputStream().read()
                                val location = if (idx == 0) {
                                    "https://127.0.0.1:${targetServer.localPort}/admin"
                                } else {
                                    "http://example.com/downgrade.gif"
                                }
                                val resp = (
                                    "HTTP/1.1 302 Found\r\n" +
                                        "Location: $location\r\n" +
                                        "Content-Length: 0\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(Charsets.US_ASCII)
                                sock.getOutputStream().write(resp)
                                sock.getOutputStream().flush()
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // Allow only "bounce.example.com" to reach the local bounce server; target redirects
                // to 127.0.0.1 or plain http:// must still be blocked by SafeUrlInterceptor.
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
                    .newBuilder()
                    .sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .build()

                val loopbackRedirectResult = SafeNetworkClient.getBytesBoundedBlocking(
                    url = "https://bounce.example.com:${bounceServer.localPort}/start",
                    maxBytes = 1024,
                    client = client
                )
                val downgradeRedirectResult = SafeNetworkClient.getBytesBoundedBlocking(
                    url = "https://bounce.example.com:${bounceServer.localPort}/downgrade",
                    maxBytes = 1024,
                    client = client
                )

                assertNull(loopbackRedirectResult)
                assertNull(downgradeRedirectResult)
                assertTrue(targetHits.get() == 0)
                bounceThread.join(1000)
                targetServer.close()
                targetThread.join(500)
            }
        }
    }
}
