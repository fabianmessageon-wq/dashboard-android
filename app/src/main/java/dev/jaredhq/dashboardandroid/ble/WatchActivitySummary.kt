package dev.jaredhq.dashboardandroid.ble

/**
 * Decoded **summary/header** of a reassembled `33 DA AD` activity buffer (Private Phase 3).
 *
 * This is intentionally the *summary only* — start/type/duration/steps/distance/calories
 * and (when present) heart-rate aggregates. Per-point HR/cadence/pace/GPS arrays and TCX
 * export are deliberately out of scope for a later phase.
 *
 * ## ⚠ Confidence — read before trusting any value
 *
 * Every field carries its own [ActivityFieldConfidence] in [confidence]. As of this phase
 * the byte offsets are taken from the IDO/VeryFit/Ryze Web-Bluetooth reference downloader
 * and have **not** been confirmed against a real version-16 capture from Fabian's watch
 * (on-device we have only ever seen an empty version-0 buffer). So:
 *
 * - [activityVersion] is [ActivityFieldConfidence.CAPTURE_OBSERVED] — we have observed the
 *   byte at [WatchProtocol.ACTIVITY_DATA_OFFSET] on-device (it read 0 = "no stored activity").
 * - Every other field is [ActivityFieldConfidence.REFERENCE_ONLY] — a documented hypothesis
 *   that a single real v16 capture will confirm or correct. Do not present these as truth,
 *   do not upload them, and do not mark the activity synced on the strength of them.
 *
 * Fields the buffer is too short to contain are returned as `null` rather than throwing, so a
 * short-but-valid buffer still yields whatever prefix decoded. Decoding never crashes the BLE
 * flow — [WatchProtocol.parseActivitySummary] returns `null` for anything it can't decode.
 */
data class WatchActivitySummary(
    /** Activity-data version (byte at [WatchProtocol.ACTIVITY_DATA_OFFSET]); only 16 is decodable. */
    val activityVersion: Int,

    /** Activity/workout type code (meaning is reference-only; not yet mapped to names). */
    val activityType: Int?,

    /** Start time as Unix epoch seconds (u32 LE), or null when absent/too short. */
    val startTimeEpochSeconds: Long?,

    /** Activity duration in seconds (u32 LE). */
    val durationSeconds: Long?,

    /** Total steps during the activity (u32 LE). */
    val steps: Long?,

    /** Distance in metres (u32 LE); unit is reference-only and unconfirmed. */
    val distanceMeters: Long?,

    /** Calories (u32 LE); unit (cal vs kcal) is reference-only and unconfirmed. */
    val calories: Long?,

    /** Average heart rate (bpm); null when absent or reported as 0 ("not measured"). */
    val avgHeartRate: Int?,

    /** Maximum heart rate (bpm); null when absent or 0. */
    val maxHeartRate: Int?,

    /** Minimum heart rate (bpm); null when absent or 0. */
    val minHeartRate: Int?,

    /** Per-field confidence label. Keyed by [Field]; every decoded field has an entry. */
    val confidence: Map<Field, ActivityFieldConfidence>,
) {
    /** The summary fields, used as keys into [confidence] and in tests/logs. */
    enum class Field {
        VERSION,
        ACTIVITY_TYPE,
        START_TIME,
        DURATION,
        STEPS,
        DISTANCE,
        CALORIES,
        AVG_HEART_RATE,
        MAX_HEART_RATE,
        MIN_HEART_RATE,
    }

    /** Confidence for [field], defaulting to [ActivityFieldConfidence.REFERENCE_ONLY]. */
    fun confidenceFor(field: Field): ActivityFieldConfidence =
        confidence[field] ?: ActivityFieldConfidence.REFERENCE_ONLY

    /**
     * One-line, log-safe summary. Contains only decoded scalar values (no raw payload), so it
     * is safe for the developer log; it is still private health data and must not be uploaded
     * or persisted by default (see CLAUDE.md privacy rules).
     */
    fun describe(): String = buildString {
        append("ActivitySummary[")
        append("ver=$activityVersion(${confidenceFor(Field.VERSION).tag}) ")
        append("type=${activityType ?: "?"} ")
        append("start=${startTimeEpochSeconds ?: "?"} ")
        append("dur=${durationSeconds ?: "?"}s ")
        append("steps=${steps ?: "?"} ")
        append("dist=${distanceMeters ?: "?"}m ")
        append("kcal=${calories ?: "?"} ")
        append("hr(avg/max/min)=${avgHeartRate ?: "?"}/${maxHeartRate ?: "?"}/${minHeartRate ?: "?"}")
        append("] — fields REFERENCE_ONLY unless tagged; UNVERIFIED on hardware")
    }
}

/** How much we trust a decoded activity field, mirroring CLAUDE.md's confidence ladder. */
enum class ActivityFieldConfidence(val tag: String) {
    /** Decoded value confirmed against a real frame from Fabian's watch. */
    WATCH_VERIFIED("watch-verified"),

    /** Byte position observed in a real on-device capture, value not yet semantically confirmed. */
    CAPTURE_OBSERVED("capture-observed"),

    /** Offset/semantics come from the reference downloader only — not yet seen on hardware. */
    REFERENCE_ONLY("reference-only"),
}
