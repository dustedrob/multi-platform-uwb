package com.dustedrob.uwb

/**
 * Platform-agnostic UWB session configuration exchanged between peers via BLE GATT.
 *
 * On Android: contains UWB address, session ID, channel, and preamble index.
 * On iOS: contains the NearbyInteraction discovery token (serialized).
 */
data class UwbSessionConfig(
    /** Agreed-upon session identifier. Both peers must use the same value. */
    val sessionId: Int,
    /** UWB channel number (e.g., 9). */
    val channel: Int,
    /** Preamble index for the UWB channel (e.g., 10). */
    val preambleIndex: Int,
    /** Platform UWB address bytes (Android) or empty (iOS). */
    val uwbAddress: ByteArray,
    /** Serialized NI discovery token (iOS) or null (Android). */
    val discoveryToken: ByteArray? = null,
) {
    /**
     * Serialize to a simple binary format for BLE GATT exchange.
     *
     * Format (big-endian):
     * ```
     * [1B version][4B sessionId][4B channel][4B preambleIndex]
     * [2B uwbAddr.size][uwbAddr bytes]
     * [2B token.size][token bytes]   // size=0 if null
     * ```
     */
    fun toByteArray(): ByteArray {
        val tokenBytes = discoveryToken ?: ByteArray(0)
        val size = 1 + 4 + 4 + 4 + 2 + uwbAddress.size + 2 + tokenBytes.size
        val buf = ByteArray(size)
        var pos = 0

        // Version
        buf[pos++] = PROTOCOL_VERSION

        // sessionId
        buf[pos++] = (sessionId shr 24).toByte()
        buf[pos++] = (sessionId shr 16).toByte()
        buf[pos++] = (sessionId shr 8).toByte()
        buf[pos++] = sessionId.toByte()

        // channel
        buf[pos++] = (channel shr 24).toByte()
        buf[pos++] = (channel shr 16).toByte()
        buf[pos++] = (channel shr 8).toByte()
        buf[pos++] = channel.toByte()

        // preambleIndex
        buf[pos++] = (preambleIndex shr 24).toByte()
        buf[pos++] = (preambleIndex shr 16).toByte()
        buf[pos++] = (preambleIndex shr 8).toByte()
        buf[pos++] = preambleIndex.toByte()

        // uwbAddress
        buf[pos++] = (uwbAddress.size shr 8).toByte()
        buf[pos++] = uwbAddress.size.toByte()
        uwbAddress.copyInto(buf, pos)
        pos += uwbAddress.size

        // discoveryToken
        buf[pos++] = (tokenBytes.size shr 8).toByte()
        buf[pos++] = tokenBytes.size.toByte()
        tokenBytes.copyInto(buf, pos)

        return buf
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwbSessionConfig) return false
        return sessionId == other.sessionId &&
                channel == other.channel &&
                preambleIndex == other.preambleIndex &&
                uwbAddress.contentEquals(other.uwbAddress) &&
                (discoveryToken?.contentEquals(other.discoveryToken ?: ByteArray(0)) ?: (other.discoveryToken == null))
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + channel
        result = 31 * result + preambleIndex
        result = 31 * result + uwbAddress.contentHashCode()
        result = 31 * result + (discoveryToken?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        private const val PROTOCOL_VERSION: Byte = 1

        fun fromByteArray(bytes: ByteArray): UwbSessionConfig? {
            if (bytes.size < 15) return null // minimum size: 1+4+4+4+2+0+2+0 = 17... but be lenient
            var pos = 0

            val version = bytes[pos++]
            if (version != PROTOCOL_VERSION) return null

            val sessionId = readInt(bytes, pos); pos += 4
            val channel = readInt(bytes, pos); pos += 4
            val preambleIndex = readInt(bytes, pos); pos += 4

            if (pos + 2 > bytes.size) return null
            val addrLen = readShort(bytes, pos); pos += 2
            if (pos + addrLen > bytes.size) return null
            val uwbAddress = bytes.copyOfRange(pos, pos + addrLen); pos += addrLen

            if (pos + 2 > bytes.size) return null
            val tokenLen = readShort(bytes, pos); pos += 2
            if (pos + tokenLen > bytes.size) return null
            val discoveryToken = if (tokenLen > 0) bytes.copyOfRange(pos, pos + tokenLen) else null

            return UwbSessionConfig(
                sessionId = sessionId,
                channel = channel,
                preambleIndex = preambleIndex,
                uwbAddress = uwbAddress,
                discoveryToken = discoveryToken,
            )
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)

        private fun readShort(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 1].toInt() and 0xFF)
    }
}
