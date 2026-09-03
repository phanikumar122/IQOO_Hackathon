package com.prahari.guardian

import android.content.Context
import android.content.SharedPreferences

/**
 * One number, stored locally, for the "call someone you trust" button.
 *
 * This is the direct counter to the secrecy tactic. Every digital-arrest script
 * contains some version of "do not tell your family" — so the intervention
 * screen offers a single tap that does exactly that, with no dialling, no
 * searching, and no thinking required from someone who is currently frightened.
 *
 * Set during onboarding. Never leaves the device; the app has no `INTERNET`
 * permission, so it could not leave even if a bug tried to send it.
 */
object TrustedContact {

    private const val PREFS = "prahari_prefs"
    private const val KEY_NUMBER = "trusted_number"
    private const val KEY_NAME = "trusted_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, name: String, number: String) {
        prefs(context).edit()
            .putString(KEY_NAME, name)
            .putString(KEY_NUMBER, number)
            .apply()
    }

    fun number(context: Context): String? =
        prefs(context).getString(KEY_NUMBER, null)?.takeIf { it.isNotBlank() }

    fun name(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun isConfigured(context: Context) = number(context) != null
}
