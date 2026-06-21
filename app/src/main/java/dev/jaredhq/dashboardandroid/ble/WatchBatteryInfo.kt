package dev.jaredhq.dashboardandroid.ble

/**
 * Battery status constants and data model for VeryFit/IDO protocol.
 *
 * Based on reverse engineering of VeryFit 3.4.0 APK.
 * The native protocol library returns battery info as JSON via command ID 321.
 */
data class WatchBatteryInfo(
    /** Battery percentage 0-100. */
    val level: Int = 0,

    /** Battery status: see [BATTERY_STATE_NORMAL], [CHARGING], [ENERGY_LOW], [ENERGY_FULL]. */
    val status: Int = BATTERY_STATE_NORMAL,

    /** Battery voltage in millivolts (e.g., 4200 = 4.2V). */
    val voltage: Int = 0,

    /** Battery mode (device-specific, usually 0). */
    val mode: Int = 0,

    /** Last charging timestamp as ISO-8601 string, or null if never charged. */
    val lastChargingTime: String? = null,
) {
    companion object {
        const val BATTERY_STATE_NORMAL = 0
        const val CHARGING = 1
        const val ENERGY_LOW = 2
        const val ENERGY_FULL = 3

        fun statusString(status: Int): String = when (status) {
            BATTERY_STATE_NORMAL -> "Normal"
            CHARGING -> "Charging"
            ENERGY_LOW -> "Low"
            ENERGY_FULL -> "Full"
            else -> "Unknown ($status)"
        }
    }
}
