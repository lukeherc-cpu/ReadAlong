// The java.io subset the phone's shared code uses, for iOS. Small, honest implementations over POSIX.
package java.io

open class IOException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    constructor(cause: Throwable) : this(cause.toString(), cause)
}
open class EOFException(message: String? = null) : IOException(message)
open class FileNotFoundException(message: String? = null) : IOException(message)
class UncheckedIOException(message: String?, cause: IOException) : RuntimeException(message, cause)

interface Closeable : AutoCloseable {
    override fun close()
}
interface Flushable { fun flush() }
