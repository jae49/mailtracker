package com.mailtracker.source

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Raised when an HTTP call to Graph/EWS/Exchange returns a non-2xx status. */
class HttpException(val status: Int, val responseBody: String, message: String) : RuntimeException(message)

/** Thin wrapper over the JDK HttpClient for the small set of calls mailtracker makes. */
object Http {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun urlEncode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    fun get(url: String, bearer: String, accept: String = "application/json", headers: Map<String, String> = emptyMap()): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $bearer")
            .header("Accept", accept)
            .GET()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder.build(), "GET", url)
    }

    fun post(
        url: String,
        bearer: String,
        body: String,
        contentType: String,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): String {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", contentType)
            .header("Accept", accept)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder.build(), "POST", url)
    }

    private fun send(req: HttpRequest, method: String, url: String): String {
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw HttpException(resp.statusCode(), resp.body(), "$method $url -> HTTP ${resp.statusCode()}")
        }
        return resp.body()
    }
}
