package com.nathanhanapps.appdual

class WorkspaceRepository(private val shell: IShellExecutor) {

    private val safePackageName = Regex("""^[A-Za-z0-9_.$-]+$""")

    fun listWorkspaces(callback: (List<WorkspaceInfo>) -> Unit) {
        shell.execWhenReady("pm list users") { output ->
            callback(WorkspaceParsers.parseUsers(output))
        }
    }

    /**
     * Creates a profile cloned from user 0.
     * [type] can be "managed" or "clone".
     */
    fun createWorkspace(name: String, type: String = "managed", callback: (Boolean, Int, String) -> Unit) {
        val safe = name.replace("\"", "").trim()
        val cmd = if (type == "clone") {
            "pm create-user --profileOf 0 --user-type android.os.usertype.profile.CLONE \"$safe\""
        } else {
            "pm create-user --profileOf 0 --managed \"$safe\""
        }
        
        shell.execWhenReady(cmd) { output ->
            // Typical success line: "Success: created user id 15"
            val userId = Regex("""created user id (\d+)""", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            
            val success = output.contains("Success", ignoreCase = true) && userId != -1
            callback(success, userId, output)
        }
    }

    fun removeWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("pm remove-user $userId") { out ->
            callback(out.contains("Success", ignoreCase = true), out)
        }
    }

    fun startWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am start-user $userId") { out ->
            callback(!out.startsWith("ERROR:") && !out.contains("failed", ignoreCase = true), out)
        }
    }

    fun stopWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am stop-user -f $userId") { out ->
            callback(!out.startsWith("ERROR:") && !out.contains("failed", ignoreCase = true), out)
        }
    }

    fun getInstalledPackages(userId: Int, callback: (Set<String>) -> Unit) {
        shell.execWhenReady("pm list packages --user $userId") { output ->
            callback(PmParsers.parsePmListPackages(output))
        }
    }

    /** Third-party/user-installed apps only, matching CloneCat-style user exports. */
    fun getUserInstalledPackages(userId: Int, callback: (Set<String>) -> Unit) {
        shell.execWhenReady("pm list packages -3 --user $userId") { output ->
            callback(PmParsers.parsePmListPackages(output))
        }
    }

    fun switchUser(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am switch-user $userId") { out ->
            val ok = !out.startsWith("ERROR:", ignoreCase = true) &&
                !out.contains("Error:", ignoreCase = true) &&
                !out.contains("failed", ignoreCase = true)
            callback(ok, out)
        }
    }

    fun switchAndLaunch(userId: Int, component: String, callback: (Boolean, String) -> Unit) {
        val cmd = "am start-user -w $userId >/dev/null 2>&1; " +
            "am switch-user $userId >/dev/null 2>&1; sleep 1; " +
            "am start --user $userId -n $component"
        shell.execWhenReady(cmd) { out ->
            val ok = !out.startsWith("ERROR:", ignoreCase = true) &&
                !out.contains("Error:", ignoreCase = true) &&
                !out.contains("failed", ignoreCase = true)
            callback(ok, out)
        }
    }

    fun resolveLauncherComponent(userId: Int, packageName: String, callback: (String?) -> Unit) {
        if (!safePackageName.matches(packageName)) {
            callback(null)
            return
        }
        val cmd = "cmd package resolve-activity --user $userId --brief " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
        shell.execWhenReady(cmd) { out ->
            // ShellClient wraps output in exitCode/stdout sections. Pick the first
            // component-looking token and ignore diagnostic/header lines.
            val component = Regex("""([A-Za-z0-9_.$-]+/[A-Za-z0-9_.$-]+)""")
                .find(out)?.groupValues?.getOrNull(1)
            callback(component)
        }
    }

    fun installToWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        if (!safePackageName.matches(packageName)) {
            callback(false, "Invalid package name")
            return
        }
        shell.execWhenReady("pm install-existing --user $userId $packageName") { out ->
            val ok = out.contains("Package", ignoreCase = true) &&
                    out.contains("installed", ignoreCase = true)
            callback(ok, out)
        }
    }

    fun uninstallFromWorkspace(userId: Int, packageName: String, keepData: Boolean, callback: (Boolean, String) -> Unit) {
        val keepFlag = if (keepData) "-k " else ""
        shell.execWhenReady("pm uninstall $keepFlag--user $userId $packageName") { out ->
            callback(out.contains("Success", ignoreCase = true), out)
        }
    }

    fun launchInWorkspace(userId: Int, component: String, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am start --user $userId -n $component") { out ->
            callback(!out.contains("Error", ignoreCase = true), out)
        }
    }

    fun openAppInfoInWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        val cmd = "am start --user $userId -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName"
        shell.execWhenReady(cmd) { out ->
            callback(!out.startsWith("ERROR:") && !out.contains("failed", ignoreCase = true), out)
        }
    }

    /** Suggests "Work1", "Work2", etc. avoiding names already taken. */
    fun suggestName(existing: List<WorkspaceInfo>, prefix: String = "Work"): String {
        val taken = existing.filter { !it.isMainUser }.map { it.name }.toSet()
        var i = 1
        while ("$prefix$i" in taken) i++
        return "$prefix$i"
    }

}
