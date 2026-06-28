import com.dustedrob.uwb.ExchangeProtocol
import com.dustedrob.uwb.UwbProfile

/**
 * App-owned vendor accessory profiles.
 *
 * These deliberately live in the app, not the library — the library ships only its vendor-neutral
 * `LOCAL_PROFILE` and exposes the [UwbProfile] interface; apps supply their own profiles based on the
 * hardware they target. UUIDs here are hardware-specific and should be verified against the actual
 * accessory before relying on them.
 */
object QorvoNearbyProfile : UwbProfile {
    override val name = "QorvoNearby"

    // The accessory advertises a beacon UUID distinct from the GATT service it hosts.
    override val advertisedUuid = "11000000-27B9-42F0-82AA-2E951747BBF9"
    override val discoveryServiceUuid = "2E938FD0-6A61-11ED-A1EB-0242AC120002"

    override val readFromUuid = "2E93941C-6A61-11ED-A1EB-0242AC120002"   // readable char (props 0x2)
    override val writeToUuid = "2E93998A-6A61-11ED-A1EB-0242AC120002"    // control / rx char (props 0xC)
    override val notifyFromUuid = "2E939AF2-6A61-11ED-A1EB-0242AC120002" // accessory tx / notify (props 0x10)

    override val exchange = ExchangeProtocol.AccessoryNotify
}

/** Nordic UART Service — a common Nearby-Interaction accessory transport. */
object NordicUartProfile : UwbProfile {
    override val name = "NordicUart"

    override val discoveryServiceUuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    override val writeToUuid = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"    // RX (host -> accessory)
    override val notifyFromUuid = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // TX (accessory -> host, notify)
    override val readFromUuid: String? = null

    override val exchange = ExchangeProtocol.AccessoryNotify
}
