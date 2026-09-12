package com.dustedrob.uwb

/**
 * Platform-agnostic UWB session configuration exchanged between peers via BLE GATT.
 *
 * On Android: contains creation timestamp, UWB address, session ID, channel, and preamble index.
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
    /**
     * Creation time (epoch millis), exchanged so both peers can deterministically pick a winner:
     * the older config owns the session parameters. The live platform ranging handle
     * (Android session scope / iOS NISession) is kept out of this wire model and tracked per peer
     * in the manager instead.
     */
    val timestamp: Long = 0L,
    /**
     * Android peer-to-peer only: this device's **controller-scope** UWB address, sent alongside the
     * controlee address in [uwbAddress]. A phone holds both a controller and a controlee session
     * scope (each with its own fixed address), and the two ends can't range as two controlees, so one
     * is elected controller. Carrying both addresses up front means that once roles are known, the
     * controller ranges against the peer's controlee address and the controlee ranges against the
     * peer's controller address, without either side minting a new address after the exchange. Null
     * on iOS and for accessories.
     */
    val controllerAddress: ByteArray? = null,
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
     * [2B ctrl.size][ctrl bytes]     // optional trailer; controller-scope address (Android P2P)
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
        val ctrlBytes = controllerAddress ?: ByteArray(0)
        val size = 1 +8 + 4 + 4 + 4 + 2 + uwbAddress.size + 2 + tokenBytes.size + 2 + keyBytes.size + 2 + accBytes.size + 2 + ctrlBytes.size
        val buf = ByteArray(size)
        var pos = 0

        // Version
        buf[pos++] = PROTOCOL_VERSION

        // timestamp (LE, 64-bit) — serialize the actual property so both peers can
        // compare ages; a local `val timestamp = 0` used to shadow it and always sent 0.
        buf[pos++]=timestamp.toByte()
        buf[pos++]=(timestamp shr 8).toByte()
        buf[pos++]=(timestamp shr 16).toByte()
        buf[pos++]=(timestamp shr 24).toByte()
        buf[pos++]=(timestamp shr 32).toByte()
        buf[pos++]=(timestamp shr 40).toByte()
        buf[pos++]=(timestamp shr 48).toByte()
        buf[pos++]=(timestamp shr 56).toByte()

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
        pos += accBytes.size

        // controllerAddress (optional trailer)
        buf[pos++] = ctrlBytes.size.toByte()
        buf[pos++] = (ctrlBytes.size shr 8).toByte()
        ctrlBytes.copyInto(buf, pos)

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
        val thisCtrl = controllerAddress ?: ByteArray(0)
        val otherCtrl = other.controllerAddress ?: ByteArray(0)
        return sessionId == other.sessionId &&
                channel == other.channel &&
                preambleIndex == other.preambleIndex &&
                uwbAddress.contentEquals(other.uwbAddress) &&
                thisToken.contentEquals(otherToken) &&
                thisKey.contentEquals(otherKey) &&
                thisAcc.contentEquals(otherAcc) &&
                thisCtrl.contentEquals(otherCtrl)
    }
   
    fun isOlder(other: UwbSessionConfig): Boolean {
        return timestamp<=other.timestamp
        }

    /**
     * Deterministic session-owner election for Android peer-to-peer, independent of BLE identity.
     *
     * The peer with the lexicographically smaller UWB address owns the session parameters (sessionId
     * and static-STS key), so both ends agree on one set even when a phone is seen under several
     * randomized BLE addresses. Timestamps can't decide this: a device mints a fresh config (new
     * timestamp) per BLE identity, so the two ends may compare different timestamp pairs and disagree
     * on the owner. The UWB address is stable across identities, so it gives a symmetric result.
     */
    fun ownsSessionOver(other: UwbSessionConfig): Boolean {
        val a = uwbAddress
        val b = other.uwbAddress
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff < 0
        }
        return a.size <= b.size
    }

    override fun hashCode(): Int {
        var result :Int = sessionId!!
        result =  31 * result + channel
        result =  31 * result + preambleIndex
        result =  31 * result + uwbAddress.contentHashCode()
        result =  31 * result + (discoveryToken?.contentHashCode() ?: 0)
        result =  31 * result + (sessionKey?.contentHashCode() ?: 0)
        result =  31 * result + (accessoryData?.contentHashCode() ?: 0)
        result =  31 * result + (controllerAddress?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        private const val PROTOCOL_VERSION: Byte = 1

        /**
         * Session id for one controller/controlee pair.
         *
         * With one session per peer, the id has to be unique per pair on this device and identical on
         * both ends. Both ends know both addresses after the config exchange (the controller its own
         * controller address and the peer's [uwbAddress]; the controlee the peer's [controllerAddress]
         * and its own [uwbAddress]), so folding the pair in a fixed order gives the same id on each
         * side without another round trip. Never 0, which the stack treats as "derive it yourself".
         */
        fun sessionIdFor(controllerAddress: ByteArray, controleeAddress: ByteArray): Int {
            var h = 17
            for (b in controllerAddress) h = h * 31 + (b.toInt() and 0xFF)
            h = h * 31 + 0x5A
            for (b in controleeAddress) h = h * 31 + (b.toInt() and 0xFF)
            return if (h == 0) 1 else h
        }

        fun fromByteArray(
            bytes: ByteArray,
            accessoryDevice: Boolean = false
        ): UwbSessionConfig? {
            if (bytes.size < 25) return null // minimum: 1(ver) + 8(ts) + 4(sid) + 4(ch) + 4(pre) + 2(addrLen) + 2(tokLen)
            var pos = 0

            val version = bytes[pos++]
            if (version != PROTOCOL_VERSION) return null

	        val timestamp = readLong(bytes,pos); pos+=8
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
                if (accLen > 0 && pos + accLen <= bytes.size) bytes.copyOfRange(pos, pos + accLen).also { pos += accLen } else null
            } else {
                null
            }

            // Optional controller-address trailer (absent in older payloads).
            val controllerAddress = if (pos + 2 <= bytes.size) {
                val ctrlLen = readShort(bytes, pos); pos += 2
                if (ctrlLen > 0 && pos + ctrlLen <= bytes.size) bytes.copyOfRange(pos, pos + ctrlLen) else null
            } else {
                null
            }

            return UwbSessionConfig(
                timestamp = timestamp,
                sessionId = sessionId,
                channel = channel,
                preambleIndex = preambleIndex,
                uwbAddress = uwbAddress,
                discoveryToken = discoveryToken,
                sessionKey = sessionKey,
                accessoryData = accessoryData,
                isAccessoryDevice = accessoryDevice,
                controllerAddress = controllerAddress,
            )
        }

        // Little-endian readers (least-significant byte first), matching toByteArray.
        private fun readInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun readLong(bytes:ByteArray, offset:Int): Long =
           //add function like readInt above, 4 more bytes
            ((bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 4].toLong() and 0xFFL) shl 32) or
            ((bytes[offset + 5].toLong() and 0xFFL) shl 40) or
            ((bytes[offset + 6].toLong() and 0xFFL) shl 48) or
            ((bytes[offset + 7].toLong() and 0xFFL) shl 56))

        private fun readShort(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
