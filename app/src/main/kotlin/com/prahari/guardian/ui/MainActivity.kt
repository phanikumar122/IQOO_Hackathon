package com.prahari.guardian.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.prahari.guardian.BuildConfig
import com.prahari.guardian.R
import com.prahari.guardian.TrustedContact
import com.prahari.guardian.databinding.ActivityMainBinding
import com.prahari.guardian.demo.DemoScenarios
import com.prahari.guardian.risk.RiskEngine
import com.prahari.guardian.service.GuardianService
import com.prahari.guardian.signals.ForegroundAppPoller
import com.prahari.guardian.signals.SignalStore
import com.prahari.guardian.signals.TransferHistory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Onboarding, the master switch, and the demo controls.
 *
 * Prahari needs five different permission grants across four different Settings
 * screens, and there is no API that batches them. Rather than hide that, this
 * screen lists them in plain language and lets the user walk them one at a
 * time. Slower, but the alternative — a wall of system dialogs on first launch —
 * is how people end up granting accessibility access to things they should not.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRuntime.setOnClickListener {
            runtimePermissions.launch(RUNTIME_PERMISSIONS)
        }
        binding.btnOverlay.setOnClickListener {
            open(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        binding.btnNotifications.setOnClickListener {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnAccessibility.setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnUsage.setOnClickListener {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) GuardianService.start(this) else GuardianService.stop(this)
            binding.masterSwitch.setText(
                if (checked) R.string.main_disable else R.string.main_enable
            )
        }

        binding.btnSaveTrusted.setOnClickListener { saveTrustedContact() }

        if (BuildConfig.DEMO_MODE_ENABLED) buildDemoControls()
        observeEngine()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Live engine + level + score readout.
     *
     * Not decoration. During the demo this is how you know whether the SLM
     * actually loaded — and because the load is deferred to the arming edge, the
     * label starts at `dormant · SLM loads when armed` and changes on stage at
     * the 180-second mark, which is the most convincing way to show that the
     * dormancy claim is real. It also lets you point at the behaviour-only number
     * while explaining that the model is not load-bearing. Never guess on stage.
     */
    private fun observeEngine() = lifecycleScope.launch {
        combine(
            GuardianService.assessment,
            GuardianService.engineLabel
        ) { a, engine -> a to engine }.collectLatest { (a, engine) ->
            binding.engineLabel.text = getString(
                R.string.main_engine,
                buildString {
                    append(engine)
                    append(" · ").append(a.level.name)
                    if (a.armed) {
                        append(" · ").append(a.score)
                        append(" (behaviour ")
                        append(RiskEngine.behaviouralOnlyScore(a.snapshot)).append(')')
                    }
                }
            )
        }
    }

    /**
     * Two fields, one number, stored locally. No contact picker: it would need
     * READ_CONTACTS at the exact moment we are arguing that Prahari asks for as
     * little as it can get away with.
     */
    private fun saveTrustedContact() {
        val name = binding.trustedName.text?.toString()?.trim().orEmpty()
        val number = binding.trustedNumber.text?.toString()?.trim().orEmpty()
        if (name.isEmpty() || number.isEmpty()) {
            toast(getString(R.string.trusted_incomplete))
            return
        }
        TrustedContact.save(this, name, number)
        toast(getString(R.string.trusted_saved, name))
        refresh()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        mark(binding.btnRuntime, R.string.perm_runtime, RUNTIME_PERMISSIONS.all { granted(it) })
        mark(binding.btnOverlay, R.string.perm_overlay, Settings.canDrawOverlays(this))
        mark(
            binding.btnNotifications, R.string.perm_notifications,
            packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        )
        mark(binding.btnAccessibility, R.string.perm_accessibility, isAccessibilityEnabled())
        // AppOpsManager is the supported way to read this one — see
        // ForegroundAppPoller.hasUsageAccess. Worth showing accurately: without
        // usage access the poller silently does nothing, and that is a signal
        // you would rather discover here than mid-demo.
        mark(binding.btnUsage, R.string.perm_usage, ForegroundAppPoller.hasUsageAccess(this))

        // Prefill so the fields show what is stored rather than looking unset —
        // but never over an edit in progress, because refresh() also runs on
        // every onResume and clobbering half-typed input is infuriating.
        if (binding.trustedName.text.isNullOrBlank()) {
            binding.trustedName.setText(TrustedContact.name(this).orEmpty())
        }
        if (binding.trustedNumber.text.isNullOrBlank()) {
            binding.trustedNumber.setText(TrustedContact.number(this).orEmpty())
        }
        // And say whether the step is done, the same way the permission rows do.
        // Not routed through mark(): that disables a row once it is satisfied,
        // which is right for a granted permission and wrong here — the whole
        // point of a trusted contact is that people change theirs.
        binding.btnSaveTrusted.setText(
            if (TrustedContact.isConfigured(this)) R.string.trusted_save_done
            else R.string.trusted_save
        )
    }

    /**
     * Reads the enabled-services list rather than trying to infer it. There is
     * no `isMyServiceEnabled()` on the platform, and OEM skins are inconsistent
     * about `AccessibilityManager.getEnabledAccessibilityServiceList`.
     */
    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/", ignoreCase = true)
    }

    private fun mark(button: MaterialButton, labelRes: Int, ok: Boolean?) {
        val suffix = when (ok) {
            true -> "  ✓ " + getString(R.string.perm_granted)
            false -> "  — " + getString(R.string.perm_grant)
            null -> ""
        }
        button.text = getString(labelRes) + suffix
        button.isEnabled = ok != true
    }

    /**
     * One button per scenario, plus an exit.
     *
     * Debug-only, gated on `BuildConfig.DEMO_MODE_ENABLED`. Build the release
     * variant for the final run so a stray tap cannot drop you into replay mode
     * while a judge is watching.
     */
    private fun buildDemoControls() {
        binding.demoTitle.visibility = View.VISIBLE
        binding.demoContainer.visibility = View.VISIBLE
        binding.demoContainer.removeAllViews()

        DemoScenarios.ALL.forEach { scenario ->
            binding.demoContainer.addView(
                MaterialButton(this).apply {
                    text = "${scenario.title}  →  ${scenario.expectation}"
                    isAllCaps = false
                    setOnClickListener { SignalStore.replay(scenario.snapshot) }
                }
            )
        }
        // Replay snapshots carry their own baseline, but a *live* run reads it
        // from TransferHistory — which is empty on a fresh install, so the amount
        // signal silently scores zero and you spend ten minutes wondering why.
        // This seeds a plausible ordinary history so the live path behaves like
        // a phone that has been in use.
        binding.demoContainer.addView(
            MaterialButton(this).apply {
                setText(R.string.demo_seed)
                isAllCaps = false
                setOnClickListener {
                    TransferHistory.seedForDemo(this@MainActivity)
                    toast(
                        getString(
                            R.string.demo_seeded,
                            TransferHistory.sampleCount(this@MainActivity)
                        )
                    )
                }
            }
        )
        binding.demoContainer.addView(
            MaterialButton(this).apply {
                setText(R.string.demo_exit)
                isAllCaps = false
                setOnClickListener { SignalStore.exitReplay() }
            }
        )
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}



