package com.nathanhanapps.appdual

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/**
 * Tiny trampoline used by pinned home-screen shortcuts.
 *
 * For a full secondary user Android cannot surface that user's Activity while user 0 is
 * foreground. The safe path is therefore: start user -> switch foreground user -> launch
 * the requested component inside that user.
 */
class ShortcutProxyActivity : AppCompatActivity() {

    companion object {
        const val ACTION_LAUNCH = "com.nathanhanapps.appdual.action.LAUNCH_IN_USER"
        const val EXTRA_USER_ID = "target_user_id"
        const val EXTRA_PACKAGE = "target_package"
        const val EXTRA_COMPONENT = "target_component"
        const val EXTRA_APP_LABEL = "target_app_label"
        const val EXTRA_USER_LABEL = "target_user_label"
        const val EXTRA_SHORTCUT_ID = "shortcut_id"
        const val EXTRA_TOKEN = "shortcut_token"
        private const val REQUEST_SHIZUKU = 9042
        private const val PREFS = "appdual_shortcuts"
    }

    private var shell: ShellClient? = null
    private var commandInFlight = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
        runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) {
                launchTarget()
            } else {
                Toast.makeText(this, R.string.shortcut_shizuku_required, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A minimal visual acknowledgement while the foreground Android user changes.
        setContentView(ProgressBar(this).apply { isIndeterminate = true })

        if (intent.action != ACTION_LAUNCH) {
            finish()
            return
        }

        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shi_not_run, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            launchTarget()
        } else {
            Shizuku.requestPermission(REQUEST_SHIZUKU)
        }
    }

    private fun launchTarget() {
        if (commandInFlight) return

        val userId = intent.getIntExtra(EXTRA_USER_ID, -1)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val component = intent.getStringExtra(EXTRA_COMPONENT).orEmpty()
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty()
        val userLabel = intent.getStringExtra(EXTRA_USER_LABEL).orEmpty()
        val shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID).orEmpty()
        val suppliedToken = intent.getStringExtra(EXTRA_TOKEN).orEmpty()

        val packagePattern = Regex("""^[A-Za-z0-9_.$-]+$""")
        val componentPattern = Regex("""^[A-Za-z0-9_.$-]+/[A-Za-z0-9_.$-]+$""")
        val storedToken = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("token:$shortcutId", null)

        val valid = userId > 0 &&
            packagePattern.matches(packageName) &&
            componentPattern.matches(component) &&
            component.substringBefore('/') == packageName &&
            shortcutId == "appdual:u$userId:$packageName" &&
            suppliedToken.isNotEmpty() &&
            suppliedToken == storedToken

        if (!valid) {
            // This Activity must be exported so the launcher can invoke a pinned shortcut.
            // Reject anything that wasn't minted by AppDual itself before touching shell.
            finish()
            return
        }

        Toast.makeText(
            this,
            getString(R.string.shortcut_opening, appLabel.ifBlank { component }, userLabel.ifBlank { "User $userId" }),
            Toast.LENGTH_SHORT
        ).show()

        commandInFlight = true
        val client = ShellClient(this)
        shell = client

        // One shell process owns the complete transition. The process keeps running even
        // while this Activity's user becomes background, avoiding a race between callbacks
        // and the Android user switch.
        val cmd = buildString {
            append("am start-user -w ").append(userId).append(" >/dev/null 2>&1; ")
            append("am switch-user ").append(userId).append(" >/dev/null 2>&1; ")
            append("sleep 1; ")
            append("am start --user ").append(userId).append(" -n ").append(component)
        }

        client.execWhenReady(cmd) { output ->
            commandInFlight = false
            runOnUiThread {
                if (output.contains("Error:", ignoreCase = true) ||
                    output.startsWith("ERROR:", ignoreCase = true) ||
                    output.contains("failed", ignoreCase = true)
                ) {
                    Toast.makeText(
                        this,
                        getString(R.string.failed_generic, output),
                        Toast.LENGTH_LONG
                    ).show()
                }
                shell?.unbind()
                shell = null
                finish()
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (!commandInFlight) {
            shell?.unbind()
            shell = null
        }
        super.onDestroy()
    }
}
