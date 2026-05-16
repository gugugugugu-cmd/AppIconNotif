package com.example.appiconnotif

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHook : IXposedHookLoadPackage {

    companion object {
        private const val SYSTEMUI = "com.android.systemui"

        private fun log(msg: String) {
            XposedBridge.log("AppIconNotif: $msg")
        }

        private fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI) return

        try {
            StatusbarAppIconHook.hook(lpparam)
            log("StatusbarAppIconHook initialized")
        } catch (t: Throwable) {
            log("Failed to initialize hooks in SystemUI")
            log(t)
        }
    }
}