package dev.jaredhq.dashboardandroid.data.settings

/**
 * Light, non-binding validation of a pasted device token. The dashboard mints
 * tokens as `dwtk_` + base64url(32 bytes) (see `src/lib/auth/device.ts`), so we
 * can give the user a friendly heads-up when a paste looks wrong — WITHOUT
 * rejecting it, since the prefix is a server convention that could change and
 * only the server can truly validate a token.
 */
object DeviceTokenFormat {

    const val PREFIX = "dwtk_"

    /** Trimmed, non-blank, prefixed, and long enough to plausibly be a token. */
    fun isLikelyValid(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith(PREFIX) && t.length >= PREFIX.length + 20
    }

    /**
     * A non-blocking hint to show when a non-blank token doesn't look right, or
     * null when it looks fine (or is blank — blank is handled elsewhere).
     */
    fun hintFor(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || isLikelyValid(t)) return null
        return if (!t.startsWith(PREFIX)) {
            "That token doesn't start with \"$PREFIX\" — make sure you pasted the whole token."
        } else {
            "That token looks too short — make sure you pasted all of it."
        }
    }
}
