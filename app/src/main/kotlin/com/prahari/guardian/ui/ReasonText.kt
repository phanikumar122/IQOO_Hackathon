package com.prahari.guardian.ui

import android.content.Context
import com.prahari.guardian.R
import com.prahari.guardian.risk.Factor
import com.prahari.guardian.model.Tactic

/**
 * Maps machine facts to human sentences.
 *
 * This file is the product's voice, and it is deliberately the only place that
 * voice exists. The SLM classifies; these reviewed strings do the talking. A 1B
 * model asked to reassure a frightened seventy-year-old in Hinglish will
 * eventually produce something wrong, and "the model wrote it" is not a defence
 * when someone has lost their savings.
 *
 * Every line here should read as a plain observation the user can check against
 * their own memory of the last ten minutes — never a probability, never a
 * lecture, never "fraud detected".
 */
object ReasonText {

    fun forFactor(context: Context, factor: Factor, detail: String?): String =
        when (factor) {
            Factor.REMOTE_ACCESS_APP ->
                context.getString(R.string.reason_remote_access, detail ?: "")
            Factor.PAYMENT_APP_OPEN ->
                context.getString(R.string.reason_payment_open, detail ?: "")
            Factor.SCREEN_SHARED -> context.getString(R.string.reason_screen_shared)
            Factor.OTP_DURING_CALL -> context.getString(R.string.reason_otp)
            Factor.NEW_PAYEE -> context.getString(R.string.reason_new_payee)
            Factor.AMOUNT_ANOMALY -> context.getString(R.string.reason_amount)
            Factor.LONG_CALL -> context.getString(R.string.reason_long_call)
            Factor.COERCION_LANGUAGE -> context.getString(R.string.reason_language)
        }

    fun forTactic(context: Context, tactic: Tactic): String = context.getString(
        when (tactic) {
            Tactic.AUTHORITY_CLAIM -> R.string.tactic_authority
            Tactic.URGENCY -> R.string.tactic_urgency
            Tactic.SECRECY -> R.string.tactic_secrecy
            Tactic.STAY_ON_LINE -> R.string.tactic_stay_on_line
            Tactic.OTP_REQUEST -> R.string.tactic_otp
            Tactic.ARREST_THREAT -> R.string.tactic_arrest
            Tactic.ACCOUNT_FREEZE_THREAT -> R.string.tactic_freeze
            Tactic.TRANSFER_TO_VERIFY -> R.string.tactic_transfer_verify
            Tactic.ISOLATION -> R.string.tactic_isolation
            Tactic.IMPERSONATION_OF_OFFICIAL -> R.string.tactic_impersonation
        }
    )
}
