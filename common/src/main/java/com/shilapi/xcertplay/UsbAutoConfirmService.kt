package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * An accessibility service to automatically check "Always allow / Use by default"
 * and click "OK / Confirm" when the Android system USB permission dialog appears for DiPlay.
 */
class UsbAutoConfirmService : AccessibilityService() {

    private var lastClickTime = -DEBOUNCE_MILLIS
    private var usbWindowId: Int? = null
    private var ignoredWindowId: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            val pkg = event.packageName?.toString()
            usbWindowId = if (isSystemUsbWindow(pkg, className)) {
                if (event.windowId != usbWindowId) Log.i(TAG, "System dialog window class=$className; checking its prompt")
                event.windowId
            } else {
                // Pioneer shows "不支持的设备" from its own USB package when the iPhone enumerates.
                dismissUnsupportedDevice()
                null
            }
        } else if (usbWindowId != event.windowId) {
            return
        }
        if (usbWindowId != event.windowId) return
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < DEBOUNCE_MILLIS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if (root.windowId != usbWindowId || root.packageName?.toString() !in SYSTEM_PACKAGES) return
            val texts = mutableListOf<String>()
            visit(root) { node ->
                node.text?.toString()?.let(texts::add)
                node.contentDescription?.toString()?.let(texts::add)
                false
            }
            val prompt = texts.joinToString(" ")
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            val usbPermission = isTargetPrompt(prompt, appLabel)
            if (!usbPermission && !isUnsupportedDevicePrompt(prompt)) {
                if (ignoredWindowId != usbWindowId) {
                    ignoredWindowId = usbWindowId
                    Log.i(TAG, "System dialog does not ask for DiPlay USB access; left alone")
                }
                return
            }
            if (usbPermission) {
                // Only the system USB dialog's optional default checkbox may be changed.
                visit(root) { node ->
                    node.isCheckable && !node.isChecked && node.isEnabled &&
                        node.viewIdResourceName == "android:id/alwaysUse" &&
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            if (clickConfirm(root, if (usbPermission) USB_CONFIRM_LABELS else DISMISS_LABELS)) {
                lastClickTime = now
                Log.i(
                    TAG,
                    if (usbPermission) "Successfully auto-confirmed DiPlay USB permission dialog"
                    else "Dismissed unsupported-device dialog",
                )
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() { usbWindowId = null }

    /** Clicks 确定 on the head unit's short "unsupported device" alert and leaves every other window. */
    private fun dismissUnsupportedDevice() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < DEBOUNCE_MILLIS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            val pkg = root.packageName?.toString()
            if (pkg == null || pkg == packageName) return
            val texts = mutableListOf<String>()
            visit(root) { node ->
                node.text?.toString()?.let(texts::add)
                node.contentDescription?.toString()?.let(texts::add)
                false
            }
            if (!isUnsupportedDevicePrompt(texts.joinToString(" "))) return
            if (clickConfirm(root, DISMISS_LABELS)) {
                lastClickTime = now
                Log.i(TAG, "Dismissed unsupported-device dialog from $pkg")
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun clickConfirm(root: AccessibilityNodeInfo, labels: Set<String>): Boolean = visit(root) { node ->
        val label = node.text?.toString()?.trim()
        node.isEnabled && node.isClickable &&
            (node.viewIdResourceName == "android:id/button1" || label in labels) &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun visit(node: AccessibilityNodeInfo, depth: Int = 0, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 32) return false
        if (action(node)) return true
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                if (visit(child, depth + 1, action)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UsbAutoConfirm"
        private const val DEBOUNCE_MILLIS = 800L
        private val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")
        private val USB_ACTIVITIES = setOf(
            "com.android.systemui.usb.UsbPermissionActivity",
            "com.android.systemui.usb.UsbConfirmActivity",
        )
        /**
         * Both USB activities extend AlertActivity, which reports its window to accessibility as
         * android.app.Dialog rather than by activity name, on Android 7 to 14 alike. So the USB
         * dialog never matched, and auto-confirm did nothing even when BYD showed the system
         * UsbPermissionActivity (#409). Other systemui dialog activities, such as screen-capture
         * consent, report the same name, so [isTargetPrompt] must also find a USB device.
         */
        private const val ALERT_ACTIVITY_CLASS = "android.app.Dialog"
        private val USB_CONFIRM_LABELS = setOf("确定", "允许", "OK", "Allow", "Confirm")
        private val DISMISS_LABELS = USB_CONFIRM_LABELS + setOf(
            "确认", "知道了", "我知道了", "好的", "关闭", "Close",
        )
        private val USB_DEVICE_WORDS = Regex("USB|iPhone|iPad", RegexOption.IGNORE_CASE)

        internal fun isSystemUsbWindow(pkg: String?, className: String?): Boolean =
            pkg in SYSTEM_PACKAGES && (className in USB_ACTIVITIES || className == ALERT_ACTIVITY_CLASS)

        /**
         * The Camry head unit shows a short alert, "不支持的设备", when the iPhone enumerates.
         * CarPlay still attaches; the alert only waits for a tap. Longer screens that merely
         * mention an unsupported feature stay untouched.
         */
        internal fun isUnsupportedDevicePrompt(text: String): Boolean {
            val compact = text.replace(Regex("\\s+"), "")
            if (compact.length !in 1..240) return false
            return compact.contains("不支持的设备") ||
                compact.contains("不支持此设备") ||
                compact.contains("不支持该设备") ||
                compact.contains("设备不受支持") ||
                compact.contains("unsupporteddevice", ignoreCase = true) ||
                compact.contains("devicenotsupported", ignoreCase = true)
        }

        /**
         * The prompt must name this app and a USB device. Android 10+ words it "Allow DiPlay to
         * access iPhone?" with no "USB", Android 7.1 names "the USB device" instead of the phone,
         * and Chinese puts the name between letters ("要允许DiPlay访问iPhone吗？"), so only ASCII
         * word characters count as part of a longer name such as "FakeDiPlay".
         */
        internal fun isTargetPrompt(text: String, appLabel: String): Boolean =
            appLabel.isNotBlank() &&
                Regex("(?<![A-Za-z0-9_])${Regex.escape(appLabel)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text) &&
                USB_DEVICE_WORDS.containsMatchIn(text)


        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            val myService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToString()
            val myShortService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToShortString()
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myService, ignoreCase = true) ||
                    componentName.equals(myShortService, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        /** BYD's settings app does not answer the standard action but has its own screen (#409). */
        private val ACCESSIBILITY_SCREENS = listOf(
            ComponentName("com.byd.systemsettings", "com.byd.systemsettings.accessibility.AccessibilityMainActivity"),
            ComponentName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"),
        )

        fun openSettings(context: Context): Boolean =
            (listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) + ACCESSIBILITY_SCREENS.map { Intent().setComponent(it) })
                .any { intent ->
                    runCatching {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                }
    }
}
