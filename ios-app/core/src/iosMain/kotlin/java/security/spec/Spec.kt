package java.security.spec

interface KeySpec
interface AlgorithmParameterSpec
open class EncodedKeySpec(val encoded: ByteArray) : KeySpec
class PKCS8EncodedKeySpec(encoded: ByteArray) : EncodedKeySpec(encoded)
