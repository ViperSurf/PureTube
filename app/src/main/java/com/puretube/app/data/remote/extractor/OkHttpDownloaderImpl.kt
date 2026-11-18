package com.puretube.app.data.remote.extractor

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/** NewPipeExtractor downloader backed by the app's pooled OkHttp client. */
class OkHttpDownloaderImpl(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = okhttp3.Request.Builder().url(url)
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val data = request.dataToSend()
        when (request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post((data ?: ByteArray(0)).toRequestBody(null))
            "PUT" -> builder.put((data ?: ByteArray(0)).toRequestBody(null))
            "DELETE" -> builder.delete(data?.toRequestBody(null))
            else -> builder.method(request.httpMethod(), data?.toRequestBody(null))
        }
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                body,
                resp.request.url.toString()
            )
        }
    }
}
