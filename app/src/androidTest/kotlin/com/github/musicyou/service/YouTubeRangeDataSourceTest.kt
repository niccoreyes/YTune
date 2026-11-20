package com.github.musicyou.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class YouTubeRangeDataSourceTest {
    private val rangeLength = YouTubeRangeDataSource.RANGE_LENGTH.toInt()
    private val audio = ByteArray(rangeLength * 2 + 321) { (it % 251).toByte() }
    private val uri = Uri.parse("https://media.googlevideo.com/audio")

    @Test
    fun openEndedReadReportsFullLengthAndCrossesHttpRanges() {
        val upstream = RangeServer(audio)
        val source = YouTubeRangeDataSource(upstream)

        assertArrayEquals(audio, read(source, DataSpec(uri), audio.size.toLong()))
        assertEquals(listOf(0L, rangeLength.toLong(), rangeLength * 2L), upstream.positions)
        assertEquals(listOf(rangeLength.toLong(), rangeLength.toLong(), 321L), upstream.lengths)
    }

    @Test
    fun boundedSeekStopsAtRequestedEnd() {
        val upstream = RangeServer(audio)
        val start = rangeLength + 7
        val length = 89
        val request = DataSpec.Builder().setUri(uri)
            .setPosition(start.toLong()).setLength(length.toLong()).build()

        assertArrayEquals(
            audio.copyOfRange(start, start + length),
            read(YouTubeRangeDataSource(upstream), request, length.toLong())
        )
        assertEquals(listOf(start.toLong()), upstream.positions)
        assertEquals(listOf(length.toLong()), upstream.lengths)
    }

    @Test
    fun media3CachesTheEntireResourceAndReadsItOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "range-test-${UUID.randomUUID()}")
        val provider = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        try {
            val upstream = RangeServer(audio)
            val factory = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { YouTubeRangeDataSource(upstream) }
            val request = DataSpec.Builder().setUri(uri).setKey("fixture").build()

            assertArrayEquals(audio, read(factory.createDataSource(), request, audio.size.toLong()))
            assertEquals(audio.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata("fixture")))
            val offline = CacheDataSource.Factory().setCache(cache).createDataSource()
            assertArrayEquals(audio, read(offline, request, audio.size.toLong()))
        } finally {
            cache.release()
            SimpleCache.delete(directory, provider)
            provider.close()
        }
    }

    @Test
    fun truncatedResponseRetriesFromTheNextUnreadByte() {
        val upstream = RangeServer(audio, dropAt = rangeLength + 37)
        val invalidated = mutableListOf<String>()
        val source = RecoveringDataSource.Factory(
            upstreamFactory = { YouTubeRangeDataSource(upstream) },
            invalidateUrl = { invalidated.add(it) }
        ).createDataSource()
        val request = DataSpec.Builder().setUri(uri).setKey("track").build()

        assertArrayEquals(audio, read(source, request, audio.size.toLong()))
        assertEquals(listOf("track"), invalidated)
        assertEquals(
            listOf(0L, rangeLength.toLong(), rangeLength + 37L, rangeLength * 2L + 37),
            upstream.positions
        )
    }

    private fun read(source: DataSource, request: DataSpec, expectedLength: Long): ByteArray {
        try {
            assertEquals(expectedLength, source.open(request))
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) return bytes.toByteArray()
                bytes.write(buffer, 0, count)
            }
        } finally {
            source.close()
        }
    }

    private class RangeServer(private val audio: ByteArray, private var dropAt: Int? = null) : HttpDataSource {
        val positions = mutableListOf<Long>()
        val lengths = mutableListOf<Long>()
        private var spec: DataSpec? = null
        private var cursor = 0
        private var end = 0

        override fun open(dataSpec: DataSpec): Long {
            spec = dataSpec
            positions += dataSpec.position
            lengths += dataSpec.length
            cursor = dataSpec.position.toInt()
            end = minOf(audio.size, cursor + dataSpec.length.toInt())
            return dataSpec.length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cursor == dropAt) {
                dropAt = null
                return C.RESULT_END_OF_INPUT
            }
            if (cursor == end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - cursor, (dropAt ?: end) - cursor)
            audio.copyInto(buffer, offset, cursor, cursor + count)
            cursor += count
            return count
        }

        override fun getUri(): Uri? = spec?.uri
        override fun getResponseCode(): Int = 206
        override fun getResponseHeaders(): Map<String, List<String>> =
            mapOf("Content-Range" to listOf("bytes ${spec?.position}-${end - 1}/${audio.size}"))
        override fun close() { spec = null }
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun setRequestProperty(name: String, value: String) = Unit
        override fun clearRequestProperty(name: String) = Unit
        override fun clearAllRequestProperties() = Unit
    }
}
