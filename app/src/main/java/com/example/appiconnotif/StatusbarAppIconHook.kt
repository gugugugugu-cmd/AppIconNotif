package com.example.appiconnotif

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object StatusbarAppIconHook {

    private const val SYSTEMUI = "com.android.systemui"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val statusBarIconViewClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                lpparam.classLoader
            )
            hookUpdateIconColor(statusBarIconViewClass)
            hookGetIcon(statusBarIconViewClass, lpparam)
        } catch (_: Throwable) {
        }
    }

    private fun hookUpdateIconColor(statusBarIconViewClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "updateIconColor",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject ?: return

                            // 只有是通知图标签时才拦截着色
                            val isNotification = runCatching { XposedHelpers.getObjectField(thisObj, "mNotification") != null }
                                .getOrDefault(false)
                            if (!isNotification) return

                            val statusBarIcon = runCatching { XposedHelpers.getObjectField(thisObj, "mIcon") }
                                .getOrNull() ?: return

                            val pkgName = runCatching { XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String }
                                .getOrNull() ?: return

                            val context = runCatching { XposedHelpers.getObjectField(thisObj, "mContext") as Context }
                                .getOrNull() ?: return

                            // 如果是第三方应用，强制清空/阻止系统自带的变色、着色逻辑
                            if (XposedUtils.isThirdPartyApp(context, pkgName)) {
                                runCatching { XposedHelpers.setIntField(thisObj, "mCurrentSetColor", 0) }
                                runCatching { XposedHelpers.callMethod(thisObj, "setStaticDrawableColor", 0) }
                                runCatching { XposedHelpers.callMethod(thisObj, "setDecorColor", 0) }
                                
                                // 核心优化：直接中断原方法的执行（因为返回了 null），不再执行原有的 updateIconColor 逻辑
                                param.result = null
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookGetIcon(
        statusBarIconViewClass: Class<*>,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            val statusBarIconClass = XposedHelpers.findClass(
                "com.android.internal.statusbar.StatusBarIcon",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "getIcon",
                statusBarIconClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject ?: return
                            val statusBarIcon = param.args[0] ?: return

                            val context = runCatching { XposedHelpers.getObjectField(thisObj, "mContext") as Context }
                                .getOrNull() ?: return

                            val pkgName = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return
                            
                            if (XposedUtils.isThirdPartyApp(context, pkgName)) {
                                // 走缓存获取，大大降低状态栏图标刷新时的开销
                                XposedUtils.getCachedAppIcon(context, pkgName)?.let { customIcon ->
                                    param.result = customIcon // 替换返回值
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }
}
