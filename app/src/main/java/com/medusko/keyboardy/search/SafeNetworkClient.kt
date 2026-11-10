package com.medusko.keyboardy.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Custom DNS resolver that rejects localhost, internal hostnames, and any DNS resolution
 * returning loopback, private, link-local, CGNAT, ULA, or multicast addresses.
 */
class SafeDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (SearchParsers.isBlockedHostname(hostname)) {
            throw UnknownHostException("Blocked unsafe hostname: $hostname")
        }
        val resolved = delegate.lookup(hostname)
        val publicAddresses = resolved.filter { SearchParsers.isPublicIpAddress(it) }
        if (publicAddresses.isEmpty()) {
            throw UnknownHostException("Blocked non-public IP address for host: $hostname")
        }
        return publicAddresses
    }
}

/**
 * Shared OkHttpClient and bounded HTTP helpers enforcing SSRF protection across every
 * request, redirect hop, DNS resolution, and Coil image fetch.
 */
object SafeNetworkClient {

    private class SafeUrlInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!SearchParsers.isSafeHttpUrl(request.url.toString())) {
                throw IOException("Blocked unsafe URL: ${request.url.host}")
            }
            val remoteAddr = chain.connection()?.socket()?.inetAddress
            if (remoteAddr != null && !SearchParsers.isPublicIpAddress(remoteAddr)) {
                throw IOException("Blocked connection to non-public address")
            }
            return chain.proceed(request)
        }
    }

    fun createClient(dns: Dns = SafeDns()): OkHttpClient {
        val urlGuard = SafeUrlInterceptor()
        return OkHttpClient.Builder()
            .dns(dns)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(4500, TimeUnit.MILLISECONDS)
            .readTimeout(5000, TimeUnit.MILLISECONDS)
            .callTimeout(12000, TimeUnit.MILLISECONDS)
            .addInterceptor(urlGuard)
            .addNetworkInterceptor(urlGuard)
            .build()
    }

    val okHttpClient: OkHttpClient by lazy { createClient() }

    suspend fun fetchStringBounded(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        client: OkHttpClient = okHttpClient
    ): String = withContext(Dispatchers.IO) {
        getBytesBoundedBlocking(
            url = url,
            headers = headers,
            maxBytes = maxBytes,
            client = client,
            cancelJob = coroutineContext[Job]
        )?.toString(Charsets.UTF_8).orEmpty()
    }

    suspend fun fetchBytesBounded(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        client: OkHttpClient = okHttpClient
    ): ByteArray? = withContext(Dispatchers.IO) {
        getBytesBoundedBlocking(
            url = url,
            headers = headers,
            maxBytes = maxBytes,
            client = client,
            cancelJob = coroutineContext[Job]
        )
    }

    fun getBytesBoundedBlocking(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        client: OkHttpClient = okHttpClient,
        cancelJob: Job? = null
    ): ByteArray? {
        if (!SearchParsers.isSafeHttpUrl(url)) return null
        val requestBuilder = Request.Builder().url(url)
        for ((name, value) in headers) {
            requestBuilder.header(name, value)
        }
        val call = client.newCall(requestBuilder.build())
        val completionHandle = cancelJob?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return null
                if (!SearchParsers.isSafeHttpUrl(response.request.url.toString())) return null
                val body = response.body ?: return null
                val declaredLength = body.contentLength()
                if (declaredLength > maxBytes) return null
                body.byteStream().use { stream ->
                    SearchParsers.readBoundedBytes(stream, maxBytes)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            completionHandle?.dispose()
        }
    }
}
