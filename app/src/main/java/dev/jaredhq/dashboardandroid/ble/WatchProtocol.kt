package dev.jaredhq.dashboardandroid.ble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Command builders and response parsers for the VeryFit / IDO **V3** BLE protocol.
 *
 * ## Real transport architecture (verified against veryfit-3-4-0.apk)
 *
 * The official VeryFit app talks to the watch through a native library,
 * `lib/arm64-v8a/libVeryFitMulti.so`, whose JNI surface proves how the protocol
 * actually works on the wire:
 *
 * - `Java_..._Protocol_WriteJsonData(byte[] json, int type)` — the app hands JSON
 *   to native; native **encodes it into a binary IDO frame** and that frame is what
 *   gets written to the BLE write characteristic (0x0AF6).
 * - `Java_..._Protocol_ReceiveDatafromBle(byte[] frame)` — the app feeds the **raw
 *   binary bytes** received on the notify characteristic back into native; native
 *   decodes the frame and calls back with JSON (`CallBackJsonData`).
 *
 * In other words: **JSON only ever exists on the Java↔native boundary. The bytes on
 * the BLE characteristic are binary IDO frames, never JSON.** A previous iteration of
 * this file wrote raw JSON to 0x0AF6 and tried to parse JSON off the notify
 * characteristic — that approach cannot work on-device and is corrected here.
 *
 * ## Frame shape (from libVeryFitMulti.so log strings)
 *
 * `head fixed error:0x%02X`, `cmd 0x%X,nseq = %d,is reply`, `cmd=0x%02X,key=0x%02X`,
 * `version:0x%X`, `length:%d`, and a trailing `CRC16` (`crc16_compute` =
 * CRC-16/CCITT-FALSE 0x1021; `crc16_x16x15x21` = 0x8005). The reconstructed layout:
 *
 * ```
 * [0] fixed head byte        (HYPOTHESIS: 0xAB — UNVERIFIED, see note below)
 * [1] cmd                    (main command group)
 * [2] key                    (sub command within the group)
 * [3] nseq                   (sequence counter, echoed in replies)
 * [4] version                (protocol/payload version)
 * [5..6] length (LE)         (payload byte count)
 * [7..7+len-1] payload       (binary struct — NOT JSON)
 * [end-2..end] crc16 (LE)    (over header+payload)
 * ```
 *
 * ## ⚠ What is verified vs. what still needs an on-device capture
 *
 * VERIFIED from static analysis: the transport is binary (not JSON); the UUIDs; the
 * battery JSON field names native emits (`level/status/voltage/mode/lastCharging*`);
 * that a CRC16 trailer exists.
 *
 * NOT YET VERIFIED (requires a real BLE snoop of the VeryFit app, because the exact
 * values live inside the stripped native lib, not in any string): the fixed head
 * byte, the exact byte offsets, the **battery cmd/key**, the binary battery payload
 * layout, and which CRC16 variant guards the frame. Everything below that depends on
 * those is marked `UNVERIFIED` and logged loudly so a single capture confirms or
 * corrects it. We deliberately do **not** pretend a request is correct when its
 * cmd/key is still a guess.
 */
object WatchProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Frame structure constants (HYPOTHESIS — confirm against capture) ─────────

    /**
     * Fixed head byte that opens every IDO V3 frame. UNVERIFIED placeholder — the
     * real value is inside libVeryFitMulti.so. The parser treats any first byte as a
     * candidate head and logs it, so a capture immediately reveals the true value.
     */
    const val FRAME_HEAD: Byte = 0xAB.toByte()

    private const val HEADER_LEN = 7
    private const val CRC_LEN = 2

    // ── JSON-boundary data-type IDs (documentation only) ─────────────────────────
    //
    // These are the `int type` values passed to WriteJsonData on the Java↔native
    // boundary in the official app. They are NOT necessarily the binary frame
    // cmd/key bytes (that mapping is internal to native and not yet captured). Kept
    // here for traceability and log labelling.

    const val DATA_TYPE_BASIC_INFO = 300       // 0x012C
    const val DATA_TYPE_MAC_ADDRESS = 301      // 0x012D
    const val DATA_TYPE_FUNCTION_TABLE = 309   // 0x0135
    const val DATA_TYPE_BATTERY_INFO = 321     // 0x0141
    const val DATA_TYPE_FIRMWARE_STATUS = 348  // 0x015C

    /**
     * Best-available hypothesis for the binary frame (cmd, key) of each request.
     * These are UNVERIFIED guesses retained only so the request builder produces a
     * well-formed frame for capture/diffing. Replace with captured values.
     */
    private val FRAME_CMD_KEY: Map<Int, Pair<Int, Int>> = mapOf(
        DATA_TYPE_BASIC_INFO to (0x01 to 0x2C),
        DATA_TYPE_MAC_ADDRESS to (0x01 to 0x2D),
        DATA_TYPE_BATTERY_INFO to (0x01 to 0x41),
        DATA_TYPE_FIRMWARE_STATUS to (0x01 to 0x5C),
    )

    @Volatile
    private var sequence: Int = 0

    private fun nextSeq(): Int {
        sequence = (sequence + 1) and 0xFF
        return sequence
    }

    // ── Request builders ─────────────────────────────────────────────────────────

    /**
     * Build an IDO V3 request frame for [dataType] with an empty payload.
     *
     * ⚠ UNVERIFIED: the (cmd, key) used here is a hypothesis (see [FRAME_CMD_KEY]) and
     * the head byte / CRC variant are unconfirmed. The watch may not answer until the
     * real values are captured. The frame is still well-formed so it can be diffed
     * byte-for-byte against a VeryFit-app capture of the same request.
     */
    fun buildRequestFrame(dataType: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val (cmd, key) = FRAME_CMD_KEY[dataType] ?: (0x00 to (dataType and 0xFF))
        return buildFrame(cmd, key, payload)
    }

    /**
     * Assemble a raw IDO V3 frame: head | cmd | key | seq | version | len(LE) |
     * payload | crc16(LE). Exposed for tests and future captured-value plumbing.
     */
    fun buildFrame(cmd: Int, key: Int, payload: ByteArray, version: Int = 0): ByteArray {
        val len = payload.size
        val frame = ByteArray(HEADER_LEN + len + CRC_LEN)
        frame[0] = FRAME_HEAD
        frame[1] = (cmd and 0xFF).toByte()
        frame[2] = (key and 0xFF).toByte()
        frame[3] = (nextSeq() and 0xFF).toByte()
        frame[4] = (version and 0xFF).toByte()
        frame[5] = (len and 0xFF).toByte()
        frame[6] = ((len shr 8) and 0xFF).toByte()
        payload.copyInto(frame, HEADER_LEN)
        val crc = crc16Ccitt(frame, 0, HEADER_LEN + len)
        frame[HEADER_LEN + len] = (crc and 0xFF).toByte()
        frame[HEADER_LEN + len + 1] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    fun buildBatteryInfoCommand(): ByteArray = buildRequestFrame(DATA_TYPE_BATTERY_INFO)
    fun buildMacAddressCommand(): ByteArray = buildRequestFrame(DATA_TYPE_MAC_ADDRESS)
    fun buildDeviceInfoCommand(): ByteArray = buildRequestFrame(DATA_TYPE_BASIC_INFO)
    fun buildFirmwareStatusCommand(): ByteArray = buildRequestFrame(DATA_TYPE_FIRMWARE_STATUS)

    // ── Inbound frame parsing ────────────────────────────────────────────────────

    /**
     * Structured view of a received notification, used primarily for diagnostic
     * logging so a capture session can confirm the real frame layout.
     */
    class ParsedFrame(
        val head: Int,
        val cmd: Int,
        val key: Int,
        val seq: Int,
        val version: Int,
        val declaredLen: Int,
        val payload: ByteArray,
        val crcReceived: Int,
        val crcComputedCcitt: Int,
        val crcComputedArc: Int,
    ) {
        /** True if either common CRC16 variant matches — i.e. our framing guess is right. */
        val crcValid: Boolean get() = crcReceived == crcComputedCcitt || crcReceived == crcComputedArc

        fun summary(): String = buildString {
            append("head=0x%02X cmd=0x%02X key=0x%02X seq=%d ver=0x%02X len=%d ".format(head, cmd, key, seq, version, declaredLen))
            append("payload=${payload.toHex()} crcRx=0x%04X ".format(crcReceived))
            append("crc[ccitt=0x%04X arc=0x%04X] valid=$crcValid".format(crcComputedCcitt, crcComputedArc))
        }
    }

    /**
     * Best-effort structural parse of a notification as an IDO V3 frame. Returns null
     * when the buffer is too short to even contain a header+crc. Never throws.
     *
     * This does NOT assume the head byte or CRC are correct — it reports them so the
     * caller can log a breakdown and a human can confirm the framing from one capture.
     */
    fun parseFrame(data: ByteArray): ParsedFrame? {
        if (data.size < HEADER_LEN + CRC_LEN) return null
        val head = data[0].toInt() and 0xFF
        val cmd = data[1].toInt() and 0xFF
        val key = data[2].toInt() and 0xFF
        val seq = data[3].toInt() and 0xFF
        val version = data[4].toInt() and 0xFF
        val declaredLen = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)

        // Clamp the payload to what actually arrived so a wrong length never overruns.
        val available = data.size - HEADER_LEN - CRC_LEN
        val payloadLen = declaredLen.coerceIn(0, maxOf(0, available))
        val payload = data.copyOfRange(HEADER_LEN, HEADER_LEN + payloadLen)

        val crcOffset = HEADER_LEN + payloadLen
        val crcReceived = (data[crcOffset].toInt() and 0xFF) or ((data[crcOffset + 1].toInt() and 0xFF) shl 8)
        val crcCcitt = crc16Ccitt(data, 0, crcOffset)
        val crcArc = crc16Arc(data, 0, crcOffset)

        return ParsedFrame(head, cmd, key, seq, version, declaredLen, payload, crcReceived, crcCcitt, crcArc)
    }

    /**
     * Human-readable description for short 6-byte notifications observed from the
     * updated watch firmware, e.g. `AD 01 32 00 00 00`. These are too short to
     * include a CRC or battery payload; they look like transport status/ACK packets.
     */
    fun describeShortStatus(data: ByteArray): String {
        if (data.size >= 6) {
            val head = data[0].toInt() and 0xFF
            val cmd = data[1].toInt() and 0xFF
            val key = data[2].toInt() and 0xFF
            val status0 = data[3].toInt() and 0xFF
            val status1 = data[4].toInt() and 0xFF
            val status2 = data[5].toInt() and 0xFF
            return "Short status/ACK (no payload): head=0x%02X cmd=0x%02X key=0x%02X status=%02X %02X %02X raw=%s".format(
                head,
                cmd,
                key,
                status0,
                status1,
                status2,
                data.toHex(),
            )
        }
        return "Short/non-frame notification (needs capture): ${data.toHex()}"
    }

    // ── Battery extraction (two independent, clearly-labelled strategies) ─────────

    /**
     * Attempt to read battery info from a binary IDO payload struct.
     *
     * ⚠ UNVERIFIED layout heuristic. From the native field order the most likely
     * packing is: `level:u8, status:u8, voltage:u16(LE)` (voltage in mV). We only
     * accept a result whose level is a plausible percentage (0..100) so random
     * frames are not misread as battery. Returns null when it does not look like one.
     */
    fun parseBatteryInfoFromBinary(payload: ByteArray): WatchBatteryInfo? {
        if (payload.size < 2) return null
        val level = payload[0].toInt() and 0xFF
        if (level !in 0..100) return null
        val status = payload[1].toInt() and 0xFF
        val voltage = if (payload.size >= 4) {
            (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
        } else {
            0
        }
        return WatchBatteryInfo(level = level, status = status, voltage = voltage, mode = 0)
    }

    /**
     * Defensive JSON battery parse. The wire is binary, but the native lib's decoded
     * representation is JSON with confirmed keys (`level/status/voltage/mode` and
     * `lastCharging*`). Some rebadged firmwares / bridges do surface ASCII JSON on the
     * characteristic, so we still try this when the bytes look like JSON. Harmless
     * when they don't (returns null).
     */
    fun parseBatteryInfoFromJson(jsonBytes: ByteArray): WatchBatteryInfo? {
        val obj = asJsonObject(jsonBytes) ?: return null
        val level = obj.intField("level") ?: return null
        return WatchBatteryInfo(
            level = level,
            status = obj.intField("status") ?: WatchBatteryInfo.BATTERY_STATE_NORMAL,
            voltage = obj.intField("voltage") ?: 0,
            mode = obj.intField("mode") ?: 0,
            lastChargingTime = buildLastChargingTime(obj),
        )
    }

    /** Defensive JSON MAC parse, mirroring [parseBatteryInfoFromJson]. */
    fun parseMacAddressFromJson(jsonBytes: ByteArray): String? {
        val obj = asJsonObject(jsonBytes) ?: return null
        return (obj.stringField("mac") ?: obj.stringField("macAddress"))
    }

    /** True when the buffer is ASCII and starts with a JSON object/array. */
    fun looksLikeJson(data: ByteArray): Boolean {
        val first = data.firstOrNull { it.toInt() != 0x20 } ?: return false
        return first == '{'.code.toByte() || first == '['.code.toByte()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun asJsonObject(bytes: ByteArray): JsonObject? = try {
        if (!looksLikeJson(bytes)) null
        else json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** Read an int field, tolerant of the element not being a JSON primitive. */
    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    /** Read a string field, tolerant of the element not being a JSON primitive. */
    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun buildLastChargingTime(obj: JsonObject): String? {
        val year = obj.intField("lastChargingYear") ?: return null
        val month = obj.intField("lastChargingMonth") ?: return null
        val day = obj.intField("lastChargingDay") ?: return null
        val hour = obj.intField("lastChargingHour") ?: 0
        val minute = obj.intField("lastChargingMinute") ?: 0
        val second = obj.intField("lastChargingSecond") ?: 0
        return "%04d-%02d-%02dT%02d:%02d:%02d".format(year, month, day, hour, minute, second)
    }

    /**
     * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection). Matches the
     * native `crc16_compute` (Nordic) symbol.
     */
    fun crc16Ccitt(data: ByteArray, start: Int, end: Int): Int {
        var crc = 0xFFFF
        for (i in start until end) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /**
     * CRC-16/ARC (poly 0x8005 reflected = 0xA001, init 0x0000). Matches the native
     * `crc16_x16x15x21` (x^16+x^15+x^2+1) symbol. Computed alongside CCITT so the
     * parser can report which variant — if either — guards real frames.
     */
    fun crc16Arc(data: ByteArray, start: Int, end: Int): Int {
        var crc = 0x0000
        for (i in start until end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if (crc and 0x0001 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}
