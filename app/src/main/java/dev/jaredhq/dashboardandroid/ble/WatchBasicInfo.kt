package dev.jaredhq.dashboardandroid.ble

/**
 * Parsed view of the watch **basic info** response (request/response `02 01`).
 *
 * ## Confidence
 *
 * **capture-observed wire frame** for the leading fields and **watch-verified** for
 * `deviceId` and `batteryLevel`: the field layout below comes from a working
 * Web-Bluetooth reference implementation for the IDO/VeryFit/Ryze family and is
 * corroborated by our own btsnoop capture
 * `02 01 D8 1E 01 01 00 5F 01 00 01 00 5A 02 02 03 06 00`, where `D8 1E` decodes to
 * device id 7896 and byte 7 (`0x5F` = 95) is the battery percentage.
 *
 * ## ⚠ Do NOT confuse [firmwareVersion] with the activity protocol version
 *
 * [firmwareVersion] lives at **offset 4** of this basic-info packet and is commonly
 * `1`. It is a completely different number from the *activity* data version (which
 * lives at offset 25 of a reassembled `33 DA AD` activity buffer and must equal
 * [WatchProtocol.SUPPORTED_ACTIVITY_VERSION]). A `firmwareVersion == 1` is normal and
 * must never be treated as "unsupported version".
 *
 * Offsets are relative to the start of the response packet (offset 0 == `0x02`,
 * offset 1 == `0x01`).
 */
data class WatchBasicInfo(
    /** Device id — 16-bit little-endian signed short at offsets 2..3 (e.g. `D8 1E` = 7896). */
    val deviceId: Int,

    /** Firmware version — single byte at **offset 4** (NOT offset 1). Commonly `1`. */
    val firmwareVersion: Int,

    /** Device mode — offset 5. */
    val mode: Int,

    /** Battery charge status code — offset 6. */
    val batteryStatus: Int,

    /** Battery percentage 0-100 ("energe" in the reference) — offset 7. */
    val batteryLevel: Int,

    /** Pair flag — offset 8, or null when the packet is too short to contain it. */
    val pair: Int? = null,

    /** Reboot flag — offset 9. */
    val reboot: Int? = null,

    /** Bind-confirm flag — offset 11. */
    val bindConfirmFlag: Int? = null,

    /** Platform code — offset 12. */
    val platform: Int? = null,

    /** Shape code — offset 13. */
    val shape: Int? = null,

    /** Device type — offset 14. */
    val devType: Int? = null,

    /** User-defined dial flag — offset 15. */
    val userDefinedDial: Int? = null,

    /** Cloud clock dial flag — offset 16. */
    val cloudClockDial: Int? = null,

    /** Show-bind-choice flag — offset 17. */
    val showBindChoice: Int? = null,

    /** Bootloader version — offset 18. */
    val bootloadVersion: Int? = null,

    /** GPS platform code — offset 19. */
    val gpsPlatform: Int? = null,
)
