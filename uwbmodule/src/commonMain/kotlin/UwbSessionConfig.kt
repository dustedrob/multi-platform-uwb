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
    /**
     * 8-byte static-STS session key (Android) or null (iOS).
     *
     * Required by androidx.core.uwb for `CONFIG_UNICAST_DS_TWR`; both peers must
     * use the same key. Exchanged here so the two ends can agree on one.
     */
    val sessionKey: ByteArray? = null,
    /**
     * Opaque Apple/Qorvo Nearby-Interaction **Accessory Configuration Data** (iOS accessory) or null.
     *
     * Carries the raw bytes an accessory sends so the iOS NI manager can build a
     * `NINearbyAccessoryConfiguration`. Mutually exclusive with [discoveryToken] (peer-to-peer): the
     * BLE layer wraps the accessory's raw payload in this field locally — it is not our own envelope.
     */
    val accessoryData: ByteArray? = null,
    // indicates if this was created by accessory device info (android)
    val isAccessoryDevice: Boolean = false,
) {
    /**
     * Serialize to a simple binary format for BLE GATT exchange.
     *
     * Format (little-endian):
     * ```
     * [1B version][4B sessionId][4B channel][4B preambleIndex]
     * [2B uwbAddr.size][uwbAddr bytes]
     * [2B token.size][token bytes]   // size=0 if null
     * [2B key.size][key bytes]       // optional trailer; absent or size=0 if null
     * [2B acc.size][acc bytes]       // optional trailer; absent or size=0 if null
     * ```
     * Multi-byte integers (sessionId, channel, preambleIndex, and the 2-byte length prefixes) are
     * little-endian to match the FiRa/UWB convention, so accessory firmware can lay the struct out
     * natively without byte-swapping. The byte-array fields (address, token, key) are opaque and are
     * copied verbatim. The session-key and accessory-data trailers are optional so older/shorter
     * payloads still parse.
     */
    fun toByteArray(): ByteArray {
        val tokenBytes = discoveryToken ?: ByteArray(0)
        val keyBytes = sessionKey ?: ByteArray(0)
        val accBytes = accessoryData ?: ByteArray(0)
        val size = 1 + 4 + 4 + 4 + 2 + uwbAddress.size + 2 + tokenBytes.size + 2 + keyBytes.size + 2 + accBytes.size
        val buf = ByteArray(size)
        var pos = 0

        // Version
        buf[pos++] = PROTOCOL_VERSION

        // sessionId (LE)
        buf[pos++] = sessionId.toByte()
        buf[pos++] = (sessionId shr 8).toByte()
        buf[pos++] = (sessionId shr 16).toByte()
        buf[pos++] = (sessionId shr 24).toByte()


        // channel (LE)
        buf[pos++] = channel.toByte()
        buf[pos++] = (channel shr 8).toByte()
        buf[pos++] = (channel shr 16).toByte()
        buf[pos++] = (channel shr 24).toByte()

        // preambleIndex (LE)
        buf[pos++] = preambleIndex.toByte()
        buf[pos++] = (preambleIndex shr 8).toByte()
        buf[pos++] = (preambleIndex shr 16).toByte()
        buf[pos++] = (preambleIndex shr 24).toByte()

        // uwbAddress
        buf[pos++] = uwbAddress.size.toByte()
        buf[pos++] = (uwbAddress.size shr 8).toByte()
        uwbAddress.copyInto(buf, pos)
        pos += uwbAddress.size

        // discoveryToken
        buf[pos++] = tokenBytes.size.toByte()
        buf[pos++] = (tokenBytes.size shr 8).toByte()
        tokenBytes.copyInto(buf, pos)
        pos += tokenBytes.size

        // sessionKey
        buf[pos++] = keyBytes.size.toByte()
        buf[pos++] = (keyBytes.size shr 8).toByte()
        keyBytes.copyInto(buf, pos)
        pos += keyBytes.size

        // accessoryData
        buf[pos++] = accBytes.size.toByte()
        buf[pos++] = (accBytes.size shr 8).toByte()
        accBytes.copyInto(buf, pos)

        return buf
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UwbSessionConfig) return false
        val thisToken = discoveryToken ?: ByteArray(0)
        val otherToken = other.discoveryToken ?: ByteArray(0)
        val thisKey = sessionKey ?: ByteArray(0)
        val otherKey = other.sessionKey ?: ByteArray(0)
        val thisAcc = accessoryData ?: ByteArray(0)
        val otherAcc = other.accessoryData ?: ByteArray(0)
        return sessionId == other.sessionId &&
                channel == other.channel &&
                preambleIndex == other.preambleIndex &&
                uwbAddress.contentEquals(other.uwbAddress) &&
                thisToken.contentEquals(otherToken) &&
                thisKey.contentEquals(otherKey) &&
                thisAcc.contentEquals(otherAcc)
    }

    override fun hashCode(): Int {
        var result :Int = sessionId
        result =  31 * result + channel
        result =  31 * result + preambleIndex
        result =  31 * result + uwbAddress.contentHashCode()
        result =  31 * result + (discoveryToken?.contentHashCode() ?: 0)
        result =  31 * result + (sessionKey?.contentHashCode() ?: 0)
        result =  31 * result + (accessoryData?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        private const val PROTOCOL_VERSION: Byte = 1

        fun fromByteArray(bytes: ByteArray, accessoryDevice: Boolean= false, accessoryData:ByteArray? = null): UwbSessionConfig? {
            if (bytes.size < 17) return null // minimum: 1(ver) + 4(sid) + 4(ch) + 4(pre) + 2(addrLen) + 2(tokLen)
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
            pos += tokenLen

            // Optional session-key trailer (absent in older payloads).
            val sessionKey = if (pos + 2 <= bytes.size) {
                val keyLen = readShort(bytes, pos); pos += 2
                if (keyLen > 0 && pos + keyLen <= bytes.size) bytes.copyOfRange(pos, pos + keyLen).also { pos += keyLen } else null
            } else {
                null
            }

            // Optional accessory-data trailer (absent in older payloads).
            val accessoryData = if (pos + 2 <= bytes.size) {
                val accLen = readShort(bytes, pos); pos += 2
                if (accLen > 0 && pos + accLen <= bytes.size) bytes.copyOfRange(pos, pos + accLen) else null
            } else {
                null
            }
            return if(sessionId == 0)
                null
            else {
                UwbSessionConfig(
                    sessionId = sessionId,
                    channel = channel,
                    preambleIndex = preambleIndex,
                    uwbAddress = uwbAddress,
                    discoveryToken = discoveryToken,
                    sessionKey = sessionKey,
                    accessoryData = accessoryData,
                    isAccessoryDevice = accessoryDevice
                )
            }
        }

        // Little-endian readers (least-significant byte first), matching toByteArray.
        private fun readInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun readShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
