package dev.jaredhq.dashboardandroid.ble

/**
 * Simple command builders for the VeryFit/IDO BLE protocol.
 *
 * VeryFit packets typically follow a structure like:
 * [0x02][cmd][len][payload...][checksum]
 *
 * Phase 1 only implements the minimal commands needed to probe the watch:
 * - 02:04 — Request MAC address (returns MAC twice)
 * - 02:02 — Request device info
 * - 02:07 — Request status/battery
 * - CCCD enable — handled in [WatchGattCallback]
 */
object WatchProtocol {

    /**
     * Build the 02:04 command to request the watch MAC address.
     * The watch responds with the MAC address repeated twice.
     */
    fun buildMacAddressCommand(): ByteArray {
        val payload = byteArrayOf(0x02, 0x04, 0x00)
        return appendChecksum(payload)
    }

    /**
     * Build the 02:02 command to request device info.
     */
    fun buildDeviceInfoCommand(): ByteArray {
        val payload = byteArrayOf(0x02, 0x02, 0x00)
        return appendChecksum(payload)
    }

    /**
     * Build the 02:07 command to request status.
     */
    fun buildStatusCommand(): ByteArray {
        val payload = byteArrayOf(0x02, 0x07, 0x00)
        return appendChecksum(payload)
    }

    /**
     * Parse a 02:04 MAC address response.
     * The response contains the 6-byte MAC address repeated twice.
     * Returns a formatted MAC string like "AA:BB:CC:DD:EE:FF" or empty if unparseable.
     */
    fun parseMacAddressResponse(data: ByteArray): String {
        if (data.size < 4) return ""
        // Expect: [0x02][0x04][len][mac(6 bytes)][mac(6 bytes)][checksum]
        val len = data.getOrNull(2)?.toInt()?.and(0xFF) ?: return ""
        if (len < 12 || data.size < 4 + len) return ""
        // Take first 6 bytes of payload as MAC
        val macBytes = data.copyOfRange(3, 9)
        return macBytes.joinToString(":") { "%02X".format(it) }
    }

    /**
     * VeryFit uses a simple checksum (sum of all bytes modulo 256, then & 0xFF).
     * This is a best-guess for Phase 1; the exact algorithm may need tuning.
     */
    private fun appendChecksum(data: ByteArray): ByteArray {
        val sum = data.sumOf { it.toInt().and(0xFF) }.and(0xFF)
        val checksum = sum.toByte()
        return data + checksum
    }
}
