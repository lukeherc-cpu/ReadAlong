package javax.crypto.spec

import java.security.spec.AlgorithmParameterSpec
import java.security.spec.KeySpec
import javax.crypto.SecretKey

class SecretKeySpec(private val key: ByteArray, override val algorithm: String) : SecretKey, KeySpec {
    constructor(key: ByteArray, offset: Int, len: Int, algorithm: String) : this(key.copyOfRange(offset, offset + len), algorithm)
    override val encoded: ByteArray get() = key.copyOf()
}
class IvParameterSpec(iv: ByteArray) : AlgorithmParameterSpec {
    constructor(iv: ByteArray, offset: Int, len: Int) : this(iv.copyOfRange(offset, offset + len))
    val iv: ByteArray = iv.copyOf()
}
class GCMParameterSpec(val tLen: Int, iv: ByteArray) : AlgorithmParameterSpec {
    val iv: ByteArray = iv.copyOf()
}
