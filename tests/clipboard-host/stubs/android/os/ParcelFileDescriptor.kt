package android.os

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Host pipes stand in for Binder/Unix descriptors; production copy/receive code is unmodified. */
class ParcelFileDescriptor private constructor(val fd: Int) {
    fun detachFd(): Int = fd

    class AutoCloseInputStream(private val parcel: ParcelFileDescriptor) : InputStream() {
        private val delegate = endpoints.getValue(parcel.fd).input!!
        override fun read(): Int = delegate.read()
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int = delegate.read(bytes, offset, count)
        override fun close() = closeFd(parcel.fd)
    }

    class AutoCloseOutputStream(private val parcel: ParcelFileDescriptor) : OutputStream() {
        private val delegate = endpoints.getValue(parcel.fd).output!!
        override fun write(value: Int) = delegate.write(value)
        override fun write(bytes: ByteArray, offset: Int, count: Int) = delegate.write(bytes, offset, count)
        override fun close() = closeFd(parcel.fd)
    }

    private class Endpoint(val input: InputStream? = null, val output: OutputStream? = null)
    companion object {
        private val nextId = AtomicInteger(100)
        private val endpoints = ConcurrentHashMap<Int, Endpoint>()
        fun createPipe(): Array<ParcelFileDescriptor> {
            val input = PipedInputStream(1024 * 1024)
            val output = PipedOutputStream(input)
            val readId = nextId.getAndIncrement()
            val writeId = nextId.getAndIncrement()
            endpoints[readId] = Endpoint(input = input)
            endpoints[writeId] = Endpoint(output = output)
            return arrayOf(ParcelFileDescriptor(readId), ParcelFileDescriptor(writeId))
        }
        fun adoptFd(fd: Int) = ParcelFileDescriptor(fd)
        fun closeFd(fd: Int) {
            endpoints.remove(fd)?.let { endpoint ->
                endpoint.input?.close()
                endpoint.output?.close()
            }
        }
        fun openCount(): Int = endpoints.size
    }
}
