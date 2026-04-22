package com.fingerthegame.app.util

import android.content.Context

/**
 * First-launch consent + always-on package denylist. The point isn't to
 * be a legal shield (the LICENSE handles disclaimer) — it's to put
 * meaningful friction between the user and the kinds of apps where
 * "modding the save file" is actively harmful (banking, auth, online
 * multiplayer, anti-cheat-protected titles).
 */
object Ethics {
    private const val PREFS = "fingerthegame_consent"
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val CURRENT_CONSENT_VERSION = 1

    /**
     * Package-name prefixes / substrings we refuse to operate on. Editing
     * a banking app's data is fraud, and modding a competitive multiplayer
     * save just gets your account banned — neither is what this tool is for.
     */
    private val DENY_SUBSTRINGS = listOf(
        // banking / wallets / finance / payments
        "bank", "wallet", "paypal", "venmo", "cashapp", "revolut",
        "robinhood", "binance", "coinbase", "metamask",
        // auth / 2FA
        "authenticator", "duo.security", "lastpass", "1password", "bitwarden",
        // government / id
        ".gov", "passport", "driver", "license",
        // major multiplayer / anti-cheat-laden titles where modding = ban
        "mihoyo.", "hoyoverse.", "tencent.", "supercell.", "riot.",
        "moonton.", "garena.", "krafton.", "epicgames.",
    )

    /** Same prefixes the writer enforces server-side, but reflected here so
     *  the picker can also visually flag them BEFORE the user picks them. */
    private val DENY_PREFIXES = listOf(
        "com.android.",
        "com.google.android.gms",
        "com.samsung.android.bank",
        "com.samsung.knox.",
    )

    fun isDeniedPackage(pkg: String): Boolean {
        val l = pkg.lowercase()
        if (DENY_PREFIXES.any { l.startsWith(it) }) return true
        return DENY_SUBSTRINGS.any { l.contains(it) }
    }

    /** Has the user ever accepted the current consent version? */
    fun hasConsented(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CONSENT_VERSION, 0) >= CURRENT_CONSENT_VERSION

    fun grantConsent(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)
            .apply()
    }
}
