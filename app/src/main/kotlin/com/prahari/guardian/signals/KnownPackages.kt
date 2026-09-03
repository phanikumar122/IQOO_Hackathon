package com.prahari.guardian.signals

/**
 * The package lists. One file, so there is exactly one place to add the app you
 * forgot at 2 a.m.
 *
 * Keep these narrow. A short explicit list is why the manifest can use
 * `<queries>` instead of `QUERY_ALL_PACKAGES`, and "we look for ten payment apps
 * and seven remote-access tools, by name" survives a privacy question that "we
 * enumerate everything installed" does not.
 *
 * These seventeen strings must stay in sync with the `packageNames` attribute in
 * `res/xml/accessibility_service_config.xml`. Nothing enforces that at build
 * time, so it is on the pre-event checklist.
 */
object KnownPackages {

    /**
     * UPI and banking apps whose foreground presence during an unknown call is
     * the 30-point signal.
     *
     * Verify each string on the actual phone with `adb shell pm list packages`
     * during the pre-event checklist. Package names change, and a typo here
     * silently removes the single heaviest behavioural signal in the product.
     */
    val PAYMENT: Map<String, String> = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "net.one97.paytm" to "Paytm",
        "com.phonepe.app" to "PhonePe",
        "in.org.npci.upiapp" to "BHIM",
        "com.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.sbi.lotusintouch" to "SBI YONO",
        "com.snapwork.hdfc" to "HDFC Bank",
        "com.icicibank.imobile" to "iMobile Pay",
        "com.axis.mobile" to "Axis Mobile",
        "com.msf.kbank.mobile" to "Kotak"
    )

    /**
     * Remote-access and screen-sharing tools. Installing one of these mid-call
     * is the signal almost no other product looks for, and it is worth 35
     * points on its own.
     */
    val REMOTE_ACCESS: Map<String, String> = mapOf(
        "com.anydesk.anydeskandroid" to "AnyDesk",
        "com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport",
        "com.teamviewer.teamviewer.market.mobile" to "TeamViewer",
        "com.rustdesk.rustdesk" to "RustDesk",
        "com.microsoft.rdc.androidx" to "Remote Desktop",
        "com.zoho.assist.agent" to "Zoho Assist",
        "com.airdroid.remotesupport" to "AirDroid Remote Support"
    )

    fun paymentLabel(pkg: String?): String? = pkg?.let { PAYMENT[it] }
    fun remoteAccessLabel(pkg: String?): String? = pkg?.let { REMOTE_ACCESS[it] }
    fun isPayment(pkg: String?) = pkg != null && pkg in PAYMENT
    fun isRemoteAccess(pkg: String?) = pkg != null && pkg in REMOTE_ACCESS
}
