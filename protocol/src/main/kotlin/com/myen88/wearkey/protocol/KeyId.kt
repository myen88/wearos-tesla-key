package com.myen88.wearkey.protocol

/** First 8 bytes of SHA-1(uncompressed public key), which is the Tesla key id prefix. */
object KeyId {
    fun fingerprint(publicUncompressed: ByteArray): String = sha1(publicUncompressed).copyOf(8).toHex()
}
