package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
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
                            val thisObj = param.thisObject

                            val isNotification = try {
                                XposedHelpers.getObjectField(thisObj, "mNotification") != null
                            } catch (_: Throwable) {
                                false
                            }
                            if (!isNotification) return

                            val statusBarIcon = try {
                                XposedHelpers.getObjectField(thisObj, "mIcon")
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            val pkgName = try {
                                XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            val context = try {
                                XposedHelpers.getObjectField(thisObj, "mContext") as Context
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            if (isThirdPartyApp(context, pkgName)) {
                                try {
                                    XposedHelpers.setIntField(thisObj, "mCurrentSetColor", 0)
                                } catch (_: Throwable) {
                                }

                                try {
                                    XposedHelpers.callMethod(thisObj, "setStaticDrawableColor", 0)
                                } catch (_: Throwable) {
                                }

                                try {
                                    XposedHelpers.callMethod(thisObj, "setDecorColor", 0)
                                } catch (_: Throwable) {
                                }

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
                            val thisObj = param.thisObject
                            val statusBarIcon = param.args[0]

                            val context = try {
                                XposedHelpers.getObjectField(thisObj, "mContext") as Context
                            } catch (_: Throwable) {
                                return
                            }

                            setNotificationIcon(statusBarIcon, context, param)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun setNotificationIcon(
        statusBarIcon: Any?,
        context: Context,
        param: XC_MethodHook.MethodHookParam
    ) {
        try {
            if (statusBarIcon == null) return

            val pkgName = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return
            if (!isThirdPartyApp(context, pkgName)) return

            val icon: Drawable = try {
                context.packageManager.getApplicationIcon(pkgName)
            } catch (_: Throwable) {
                return
            }

            param.result = icon
        } catch (_: Throwable) {
        }
    }

    private fun isThirdPartyApp(context: Context, pkgName: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp =
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !isSystemApp && !isUpdatedSystemApp
        } catch (_: Throwable) {
            false
        }
    }
}