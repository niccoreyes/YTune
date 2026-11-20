package com.github.musicyou.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection

/** Presents successive Google Video HTTP ranges as one Media3 request. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class YouTubeRangeDataSource(private val http: HttpDataSource) : DataSource {
    private var request: DataSpec? = null
    private var position = 0L
    private var contentLength: Long? = null
    private var rangeRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host.orEmpty()
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
            !(host.equals("googlevideo.com", true) || host.endsWith(".googlevideo.com", true))
        ) {
            return http.open(dataSpec)
        }

        request = dataSpec
        position = dataSpec.position
        val directLength = openRange(dataSpec)
        return directLength ?: if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            contentLength?.minus(position) ?: C.LENGTH_UNSET.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val dataSpec = request ?: return http.read(buffer, offset, length)
        if (remaining(dataSpec) == 0L) return C.RESULT_END_OF_INPUT
        if (rangeRemaining == 0L) {
            http.close()
            if (openRange(dataSpec) != null) return http.read(buffer, offset, length)
            if (rangeRemaining == 0L) return C.RESULT_END_OF_INPUT
        }

        val read = http.read(buffer, offset, minOf(length.toLong(), rangeRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            throw EOFException("HTTP range ended before all advertised bytes arrived")
        }
        position += read
        rangeRemaining -= read
        return read
    }

    override fun getUri(): Uri? = http.uri

    override fun getResponseHeaders(): Map<String, List<String>> = http.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
    }

    override fun close() {
        request = null
        contentLength = null
        rangeRemaining = 0
        http.close()
    }

    // A non-null return means the server ignored Range; the delegate now reads the rest directly.
    private fun openRange(dataSpec: DataSpec): Long? {
        val offset = position - dataSpec.position
        val requestedLength = minOf(remaining(dataSpec), RANGE_LENGTH)
        http.open(dataSpec.subrange(offset, requestedLength))

        if (http.responseCode == HttpURLConnection.HTTP_OK) {
            http.close()
            request = null
            return http.open(dataSpec.subrange(offset))
        }

        val header = http.responseHeaders["Content-Range"]?.firstOrNull()
        val total = HttpUtil.getDocumentSize(header).takeUnless { it == C.LENGTH_UNSET.toLong() }
        if (total != null && contentLength != null && total != contentLength) {
            throw IOException("HTTP content length changed between ranges")
        }
        contentLength = total ?: contentLength

        // Media3 allows HTTP 416 as a successful open at the exact end of the resource.
        if (http.responseCode == 416 && total == position) {
            rangeRemaining = 0
            return null
        }

        val range = header?.let(CONTENT_RANGE::matchEntire)
        val start = range?.groupValues?.get(1)?.toLongOrNull()
        val end = range?.groupValues?.get(2)?.toLongOrNull()
        if (http.responseCode != HttpURLConnection.HTTP_PARTIAL || start != position ||
            end == null || end < position || end - position >= requestedLength ||
            (contentLength?.let { end >= it } == true)
        ) {
            throw IOException("Invalid Content-Range for requested audio bytes")
        }

        rangeRemaining = end - position + 1
        return null
    }

    private fun remaining(dataSpec: DataSpec): Long {
        val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            Long.MAX_VALUE
        } else {
            dataSpec.length - (position - dataSpec.position)
        }
        return minOf(requested, contentLength?.minus(position) ?: Long.MAX_VALUE)
    }

    companion object {
        const val RANGE_LENGTH = 512 * 1024L
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(?:\\d+|\\*)")
    }
}
