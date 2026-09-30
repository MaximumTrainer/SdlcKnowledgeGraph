package com.repodatagraph.config

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream

/**
 * A request whose body a filter had to read to inspect, handed on with the same bytes again: the
 * ones the filter read, then whatever it left unread in [rest]. Used by the read-only posture (#48)
 * and the scope gate (#116), both of which look inside a GraphQL document before it runs.
 */
class ReplayedBodyRequest(
    request: HttpServletRequest,
    private val read: ByteArray,
    private val rest: InputStream? = null,
) : HttpServletRequestWrapper(request) {
    private val body: InputStream by lazy {
        rest?.let { SequenceInputStream(ByteArrayInputStream(read), it) } ?: ByteArrayInputStream(read)
    }

    override fun getInputStream(): ServletInputStream = ReplayedInputStream(body)

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))

    override fun getContentLength(): Int = if (rest == null) read.size else super.getContentLength()

    override fun getContentLengthLong(): Long = if (rest == null) read.size.toLong() else super.getContentLengthLong()

    private class ReplayedInputStream(
        private val source: InputStream,
    ) : ServletInputStream() {
        private var finished = false

        override fun read(): Int = source.read().also { if (it < 0) finished = true }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = source.read(b, off, len).also { if (it < 0) finished = true }

        override fun isFinished(): Boolean = finished || (source is ByteArrayInputStream && source.available() == 0)

        override fun isReady(): Boolean = true

        override fun setReadListener(listener: ReadListener?): Unit = throw IOException("asynchronous reads are not supported")
    }
}
