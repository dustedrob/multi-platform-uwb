package com.dustedrob.uwb

/**
 * Describes one BLE "profile" for the UWB configuration exchange: the GATT service that is
 * advertised / scanned for, the characteristics used to move a serialized [UwbSessionConfig],
 * and which [ExchangeProtocol] the peer speaks.
 *
 * The library ships only the vendor-neutral [LOCAL_PROFILE]. The implementing app supplies any
 * additional profiles (e.g. vendor accessories) by passing its own [UwbProfile] implementations
 * via [BleDiscoveryConfig] — the library owns the BLE *mechanism* (the exchange engines), the app
 * owns its *profiles* (which UUIDs, which protocol). Apps can use the [ServiceEntry] data class for
 * the common case, or implement this interface directly for anything custom (e.g. a beacon that
 * advertises a UUID different from its GATT service — override [advertisedUuid]).
 *
 * Exchange flow per matched profile:
 * - [ExchangeProtocol.ReadWrite] (phone-to-phone): the initiator reads [readFromUuid] to get the
 *   responder's [UwbSessionConfig], then writes its own to [writeToUuid].
 * - [ExchangeProtocol.AccessoryNotify] (accessory): the initiator writes [initCommand] to
 *   [writeToUuid], and the accessory notifies its config back on [notifyFromUuid].
 */
interface UwbProfile {
    /** Identity label; selects the advertised profile (see [BleDiscoveryConfig.advertiseProfile]). */
    val name: String

    /** GATT service UUID hosted on the local server and discovered on the peer. */
    val discoveryServiceUuid: String

    /**
     * Service UUID actually put on-air when advertising / filtered for when scanning. Usually equal
     * to [discoveryServiceUuid], but some accessories advertise a different beacon UUID than the GATT
     * service they host. Defaults to [discoveryServiceUuid].
     */
    val advertisedUuid: String get() = discoveryServiceUuid

    /**
     * Characteristic the client reads to obtain the peer's config (the peer's "tx").
     * Used by [ExchangeProtocol.ReadWrite]; null for accessory profiles.
     */
    val readFromUuid: String?

    /** Characteristic the client writes to: the peer's "rx" (ReadWrite) or the accessory's control char. */
    val writeToUuid: String

    /**
     * Characteristic the accessory notifies its config on (the accessory's "tx").
     * Used by [ExchangeProtocol.AccessoryNotify]; null for read/write profiles.
     */
    val notifyFromUuid: String? get() = null

    /** Which config-exchange protocol this profile speaks. Defaults to [ExchangeProtocol.ReadWrite]. */
    val exchange: ExchangeProtocol get() = ExchangeProtocol.ReadWrite

    /**
     * Command byte(s) written to [writeToUuid] to trigger an accessory to send its config back over
     * [notifyFromUuid]. Only used by [ExchangeProtocol.AccessoryNotify]; null falls back to the
     * Nearby-Interaction accessory default ([NI_ACCESSORY_INIT_COMMAND]).
     */
    val initCommand: ByteArray? get() = null
}

/** How a [UwbProfile] exchanges its [UwbSessionConfig] with a peer. */
enum class ExchangeProtocol {
    /** Phone-to-phone: the client reads the peer's config, then writes its own. */
    ReadWrite,

    /** Accessory: the client writes an init command, the accessory notifies its config back. */
    AccessoryNotify,
}

/**
 * Ergonomic default [UwbProfile] implementation for the common case. For a profile whose advertised
 * UUID differs from its GATT service, implement [UwbProfile] directly and override [advertisedUuid].
 */
data class ServiceEntry(
    override val name: String,
    override val discoveryServiceUuid: String,
    override val writeToUuid: String,
    override val readFromUuid: String? = null,
    override val notifyFromUuid: String? = null,
    override val exchange: ExchangeProtocol = ExchangeProtocol.ReadWrite,
    override val initCommand: ByteArray? = null,
) : UwbProfile

/** This project's own (vendor-neutral) GATT service for UWB config exchange. */
const val UWB_CONFIG_SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"

/** Readable characteristic: the GATT server's local UWB config (serialized [UwbSessionConfig]). */
const val UWB_CONFIG_CHAR_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"

/** Writable characteristic: the GATT client writes its UWB config here. */
const val UWB_CONFIG_WRITE_CHAR_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"

/** The library's own profile — the default vendor-free identity a device advertises and exchanges over. */
val LOCAL_PROFILE: UwbProfile = ServiceEntry(
    name = "local",
    discoveryServiceUuid = UWB_CONFIG_SERVICE_UUID,
    readFromUuid = UWB_CONFIG_CHAR_UUID,
    writeToUuid = UWB_CONFIG_WRITE_CHAR_UUID,
)

/** Vendor-free default: only the library's own [LOCAL_PROFILE]. Apps add their own profiles explicitly. */
val DEFAULT_PROFILES: List<UwbProfile> = listOf(LOCAL_PROFILE)

// ---- Nearby-Interaction accessory protocol message ids ----
// These are Apple's NI accessory protocol values (host <-> accessory), not vendor specifics, so the
// AccessoryNotify exchange engine owns them. A profile may override the host->accessory init via
// [UwbProfile.initCommand].

/** Host -> accessory: initialize (request the accessory's configuration). */
const val NI_ACCESSORY_INIT_COMMAND: Byte = 0x0A
const val ANDROID_ACCESSORY_INIT_COMMAND: Byte = 0x1A
/** Host -> accessory: configure and start ranging. */
const val NI_ACCESSORY_CONFIGURE_AND_START: Byte = 0x0B
const val ANDROID_ACCESSORY_CONFIGURE_AND_START: Byte = 0x1B
/** Host -> accessory: stop ranging. */
const val NI_ACCESSORY_STOP: Byte = 0x0C

/** Accessory -> host (first byte of a notify payload): the accessory's configuration follows. */
const val NI_ACCESSORY_CONFIG_DATA: Byte = 0x01

/** Accessory -> host: ranging did start. */
const val NI_ACCESSORY_DID_START: Byte = 0x02

/** Accessory -> host: ranging did stop. */
const val NI_ACCESSORY_DID_STOP: Byte = 0x03

/**
 * App-owned configuration for BLE discovery and config exchange.
 *
 * @property profiles the service profiles to scan for and host on the local GATT server.
 * @property advertiseProfile the [UwbProfile.name] of the profile this device advertises as its own
 *   identity. Must match one of [profiles].
 * @property enableAccessoryProtocol opt-in for the accessory (write-init / notify-back) exchange.
 *   Off by default: [ExchangeProtocol.AccessoryNotify] profiles only work against **compatible
 *   accessory firmware** (the app talks a distinct, hardware-specific protocol to the device), so it
 *   must be turned on deliberately. When false, any accessory profiles in [profiles] are ignored for
 *   discovery and exchange (see [activeProfiles]) — the app has to both add an accessory profile and
 *   set this flag. Peer-to-peer ([ExchangeProtocol.ReadWrite]) profiles are unaffected.
 */
data class BleDiscoveryConfig(
    val profiles: List<UwbProfile> = DEFAULT_PROFILES,
    val advertiseProfile: String = DEFAULT_PROFILES.first().name,
    val enableAccessoryProtocol: Boolean = false,
) {
    /**
     * The profiles this device actually scans for and exchanges over. Identical to [profiles] when
     * [enableAccessoryProtocol] is set; otherwise [ExchangeProtocol.AccessoryNotify] profiles are
     * filtered out so the accessory protocol stays completely inert unless opted in.
     */
    val activeProfiles: List<UwbProfile>
        get() = if (enableAccessoryProtocol) {
            profiles
        } else {
            profiles.filter { it.exchange != ExchangeProtocol.AccessoryNotify }
        }
}
