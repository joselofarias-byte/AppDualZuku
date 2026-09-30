package com.nathanhanapps.appdual

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Home-screen shortcuts for apps that live in another Android user.
 *
 * The shortcut always targets AppDual in user 0. [ShortcutProxyActivity] then
 * starts/switches to the target full secondary user and launches the stored component.
 */
internal object WorkspaceShortcutManager {

    private const val PREFS = "appdual_shortcuts"

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return context.getSystemService(ShortcutManager::class.java)
            ?.isRequestPinShortcutSupported == true
    }

    fun pin(
        context: Context,
        workspace: WorkspaceInfo,
        item: AppItem,
        component: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false

        val id = "appdual:u${workspace.userId}:${item.packageName}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tokenKey = "token:$id"
        val token = prefs.getString(tokenKey, null)
            ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString(tokenKey, it).apply()
            }

        val intent = Intent(context, ShortcutProxyActivity::class.java).apply {
            action = ShortcutProxyActivity.ACTION_LAUNCH
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(ShortcutProxyActivity.EXTRA_USER_ID, workspace.userId)
            putExtra(ShortcutProxyActivity.EXTRA_PACKAGE, item.packageName)
            putExtra(ShortcutProxyActivity.EXTRA_COMPONENT, component)
            putExtra(ShortcutProxyActivity.EXTRA_APP_LABEL, item.label)
            putExtra(ShortcutProxyActivity.EXTRA_USER_LABEL, workspace.displayName)
            putExtra(ShortcutProxyActivity.EXTRA_SHORTCUT_ID, id)
            putExtra(ShortcutProxyActivity.EXTRA_TOKEN, token)
        }

        val builder = ShortcutInfo.Builder(context, id)
            .setShortLabel(item.label)
            .setLongLabel("${item.label} · ${workspace.displayName}")
            .setIntent(intent)

        drawableToBitmap(item.icon)?.let { builder.setIcon(Icon.createWithBitmap(it)) }
            ?: builder.setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))

        val shortcut = builder.build()
        val pinned = runCatching { manager.pinnedShortcuts.any { it.id == id } }.getOrDefault(false)
        if (pinned) {
            return runCatching {
                manager.updateShortcuts(listOf(shortcut))
                true
            }.getOrDefault(false)
        }

        return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        drawable ?: return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }.getOrNull()
    }
}
