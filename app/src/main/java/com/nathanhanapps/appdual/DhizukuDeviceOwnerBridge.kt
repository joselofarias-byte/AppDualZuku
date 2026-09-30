package com.nathanhanapps.appdual

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Device-owner path for creating a full secondary Android user while keeping
 * Dhizuku as Device Owner on user 0.
 *
 * This is intentionally separate from Shizuku/shell. Shell is blocked by
 * DISALLOW_ADD_USER once a Device Owner exists, while DevicePolicyManager's
 * createAndManageUser() is the supported Device Owner API and internally
 * creates the managed secondary user even when that restriction is present.
 */
internal class DhizukuDeviceOwnerBridge(context: Context) {

    data class CreateResult(
        val success: Boolean,
        val userId: Int = -1,
        val message: String
    )

    private val appContext = context.applicationContext

    @Volatile
    private var routedDpm: DevicePolicyManager? = null

    fun init(): Boolean = runCatching { Dhizuku.init(appContext) }.getOrDefault(false)

    fun isPermissionGranted(): Boolean =
        runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)

    fun requestPermission(callback: (Boolean, String) -> Unit) {
        if (!init()) {
            callback(false, "Dhizuku is not available or is not active")
            return
        }
        if (isPermissionGranted()) {
            callback(true, "Dhizuku permission already granted")
            return
        }

        runCatching {
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        callback(true, "Dhizuku permission granted")
                    } else {
                        callback(false, "Dhizuku permission denied")
                    }
                }
            })
        }.onFailure {
            callback(false, "Dhizuku permission request failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun createManagedSecondaryUser(name: String): CreateResult {
        val safeName = name.replace("\"", "").trim()
        if (safeName.isBlank()) return CreateResult(false, message = "User name is empty")

        if (!init()) {
            return CreateResult(false, message = "Dhizuku is not available or is not active")
        }
        if (!isPermissionGranted()) {
            return CreateResult(false, message = "Dhizuku permission is required")
        }

        val admin = runCatching { Dhizuku.getOwnerComponent() }.getOrNull()
            ?: return CreateResult(false, message = "Dhizuku reported no Device Owner component")

        val localDpm = appContext.getSystemService(DevicePolicyManager::class.java)
        if (!localDpm.isDeviceOwnerApp(admin.packageName)) {
            return CreateResult(
                false,
                message = "Dhizuku is not Device Owner on user 0 (owner=${admin.flattenToShortString()})"
            )
        }

        return try {
            val dpm = getRoutedDpm(admin)
                ?: return CreateResult(false, message = "Could not route DevicePolicyManager through Dhizuku")

            val flags = DevicePolicyManager.SKIP_SETUP_WIZARD or
                DevicePolicyManager.LEAVE_ALL_SYSTEM_APPS_ENABLED

            // profileOwner must belong to the same package as the Device Owner.
            // Reusing Dhizuku's own DeviceAdminReceiver satisfies that contract;
            // Android installs Dhizuku into the new user and makes it Profile Owner.
            val user = dpm.createAndManageUser(
                admin,
                safeName,
                admin,
                null,
                flags
            ) ?: return CreateResult(false, message = "createAndManageUser returned null")

            val startResult = runCatching {
                dpm.startUserInBackground(admin, user)
            }.getOrElse { -1 }

            val userId = runCatching {
                user.javaClass.getMethod("getIdentifier").invoke(user) as Int
            }.getOrElse {
                // UserHandle.toString() is normally "UserHandle{N}"; keep this as a
                // defensive fallback for OEM stubs that hide getIdentifier at compile time.
                Regex("""\\d+""").find(user.toString())?.value?.toIntOrNull() ?: -1
            }

            CreateResult(
                userId >= 0,
                userId,
                if (userId >= 0) {
                    "Created managed secondary user $userId; startUserInBackground=$startResult"
                } else {
                    "User created, but AppDual could not resolve its userId"
                }
            )
        } catch (t: Throwable) {
            CreateResult(
                false,
                message = "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
            )
        }
    }

    /**
     * Returns a DevicePolicyManager whose Binder calls are executed by the
     * Dhizuku owner process instead of AppDual's UID.
     */
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun getRoutedDpm(admin: ComponentName): DevicePolicyManager? {
        routedDpm?.let { return it }

        return synchronized(this) {
            routedDpm ?: runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.addHiddenApiExemptions("Landroid/app/admin/")
                }

                val ownerContext = appContext.createPackageContext(
                    admin.packageName,
                    Context.CONTEXT_IGNORE_SECURITY
                )
                val manager = ownerContext.getSystemService(DevicePolicyManager::class.java)

                val field = DevicePolicyManager::class.java.getDeclaredField("mService")
                field.isAccessible = true

                val original = field.get(manager) as IInterface
                val wrappedBinder = Dhizuku.binderWrapper(original.asBinder())

                val stubClass = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val routedInterface = asInterface.invoke(null, wrappedBinder)

                field.set(manager, routedInterface)
                routedDpm = manager
                manager
            }.getOrNull()
        }
    }
}
