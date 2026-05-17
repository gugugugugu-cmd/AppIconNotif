package com.example.appiconnotif

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "AppIconNotif"
        private const val SYSTEMUI = "com.android.systemui"

        private fun log(msg: String) {
            XposedBridge.log("$TAG: $msg")
        }

        private fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("handleLoadPackage called for: ${lpparam.packageName}")

        if (lpparam.packageName != SYSTEMUI) {
            log("Skipping non-SystemUI package: ${lpparam.packageName}")
            return
        }

        log("SystemUI package detected. Initializing hooks...")

        try {
            StatusbarAppIconHook.hook(lpparam)
            log("StatusbarAppIconHook.hook() completed successfully")
        } catch (t: Throwable) {
            log("FATAL: StatusbarAppIconHook.hook() threw an exception")
            log(t)
        }

        log("NotificationIconHook initialization finished")
    }
}