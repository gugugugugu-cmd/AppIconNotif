package com.example.appiconnotif

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object StatusbarAppIconHook {

    private const val SYSTEMUI = "com.android.systemui"

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        try {
            val notificationIconContainerClass = XposedCompat.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                classLoader
            )

            val iconStateClass = XposedCompat.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState",
                classLoader
            )

            val statusBarIconViewClass = XposedCompat.findClass(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                classLoader
            )

            hookApplyIconStates(module, notificationIconContainerClass)
            hookIconState(module, iconStateClass)
            hookUpdateIconColor(module, statusBarIconViewClass)
            hookGetIcon(module, statusBarIconViewClass, classLoader)
        } catch (t: Throwable) {
            XposedEntry.log("Failed to initialize StatusbarAppIconHook", t)
        }
    }

    private fun hookApplyIconStates(module: XposedModule, notificationIconContainerClass: Class<*>) {
        try {
            val method = XposedCompat.findMethodBestMatch(notificationIconContainerClass, "applyIconStates")
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    chain.proceed()
                    try {
                        // 不同 ROM 上 mIconStates 可能是 HashMap 或 ArrayMap，统一按 Map 处理
                        val iconStates = XposedCompat.getObjectField(chain.thisObject, "mIconStates")
                            as? Map<*, *> ?: return@intercept null

                        for (icon in iconStates.keys) {
                            removeTintForStatusbarIcon(icon)
                        }
                    } catch (_: Throwable) {
                    }
                    null
                }
        } catch (t: Throwable) {
            XposedEntry.log("Failed to hook applyIconStates", t)
        }
    }

    private fun hookIconState(module: XposedModule, iconStateClass: Class<*>) {
        val hooker = { chain: XposedInterface.Chain ->
            chain.proceed()
            try {
                val icon = chain.getArg(0) as? View
                if (icon != null) {
                    val isNotification = try {
                        XposedCompat.getObjectField(icon, "mNotification") != null
                    } catch (_: Throwable) {
                        false
                    }
                    removeTintForStatusbarIcon(icon, isNotification)
                }
            } catch (_: Throwable) {
            }
            null
        }

        try {
            val initFrom = XposedCompat.findMethodBestMatch(iconStateClass, "initFrom", View::class.java)
            module.hook(initFrom)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
        } catch (t: Throwable) {
            XposedEntry.log("Failed to hook IconState.initFrom", t)
        }

        try {
            val applyToView = XposedCompat.findMethodBestMatch(iconStateClass, "applyToView", View::class.java)
            module.hook(applyToView)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
        } catch (t: Throwable) {
            XposedEntry.log("Failed to hook IconState.applyToView", t)
        }
    }

    private fun hookUpdateIconColor(module: XposedModule, statusBarIconViewClass: Class<*>) {
        try {
            val method = XposedCompat.findMethodBestMatch(statusBarIconViewClass, "updateIconColor")
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 旧版 beforeHookedMethod 中 param.result = null 的等价写法：
                    // 不调用 chain.proceed()，直接返回 null 以跳过原方法
                    try {
                        val thisObj = chain.thisObject
                        val isNotification = try {
                            XposedCompat.getObjectField(thisObj, "mNotification") != null
                        } catch (_: Throwable) {
                            false
                        }
                        if (!isNotification) return@intercept chain.proceed()

                        val statusBarIcon = try {
                            XposedCompat.getObjectField(thisObj, "mIcon")
                        } catch (_: Throwable) {
                            null
                        } ?: return@intercept chain.proceed()

                        val pkgName = try {
                            XposedCompat.getObjectField(statusBarIcon, "pkg") as? String
                        } catch (_: Throwable) {
                            null
                        } ?: return@intercept chain.proceed()

                        val context = try {
                            XposedCompat.getObjectField(thisObj, "mContext") as? Context
                        } catch (_: Throwable) {
                            null
                        } ?: return@intercept chain.proceed()

                        if (IconManager.shouldReplaceApp(context, pkgName)) {
                            // 跳过原方法（不着色）
                            null
                        } else {
                            chain.proceed()
                        }
                    } catch (t: Throwable) {
                        XposedEntry.log("error in updateIconColor hook", t)
                        chain.proceed()
                    }
                }
        } catch (t: Throwable) {
            XposedEntry.log("Failed to hook StatusBarIconView.updateIconColor", t)
        }
    }

    private fun hookGetIcon(
        module: XposedModule,
        statusBarIconViewClass: Class<*>,
        classLoader: ClassLoader
    ) {
        try {
            val statusBarIconClass = XposedCompat.findClass(
                "com.android.internal.statusbar.StatusBarIcon",
                classLoader
            )
            val method = XposedCompat.findMethodBestMatch(
                statusBarIconViewClass, "getIcon", statusBarIconClass
            )
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 旧版 beforeHookedMethod + param.result = drawable 的等价写法：
                    // 替换返回值时不调用 chain.proceed()
                    try {
                        val thisObj = chain.thisObject
                        val statusBarIcon = chain.getArg(0)
                        val sysuiContext = try {
                            XposedCompat.getObjectField(thisObj, "mContext") as? Context
                        } catch (_: Throwable) {
                            null
                        } ?: return@intercept chain.proceed()

                        val sbn = try {
                            XposedCompat.getObjectField(thisObj, "mNotification")
                        } catch (_: Throwable) {
                            null
                        }

                        var appContext: Context? = null
                        if (sbn != null) {
                            appContext = try {
                                XposedCompat.callMethod(sbn, "getPackageContext", sysuiContext) as? Context
                            } catch (_: Throwable) {
                                null
                            }
                        }
                        if (appContext == null) appContext = sysuiContext

                        setNotificationIcon(statusBarIcon, appContext, chain)
                            ?: chain.proceed()
                    } catch (t: Throwable) {
                        XposedEntry.log("error in getIcon hook", t)
                        chain.proceed()
                    }
                }
        } catch (t: Throwable) {
            XposedEntry.log("Failed to hook getIcon(statusBarIcon)", t)
        }
    }

    private fun removeTintForStatusbarIcon(icon: Any?, isNotification: Boolean? = null) {
        try {
            if (icon == null) return

            val isNotif = isNotification ?: try {
                XposedCompat.getObjectField(icon, "mNotification") != null
            } catch (_: Throwable) {
                false
            }
            if (!isNotif) return

            val statusBarIcon = try {
                XposedCompat.getObjectField(icon, "mIcon")
            } catch (_: Throwable) {
                null
            } ?: return

            val pkgName = try {
                XposedCompat.getObjectField(statusBarIcon, "pkg") as? String
            } catch (_: Throwable) {
                null
            } ?: return

            val context = try {
                XposedCompat.getObjectField(icon, "mContext") as? Context
            } catch (_: Throwable) {
                null
            } ?: return

            if (IconManager.shouldReplaceApp(context, pkgName)) {
                try {
                    (icon as? ImageView)?.imageTintList = null
                } catch (_: Throwable) {
                }

                try {
                    (icon as? ImageView)?.clearColorFilter()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 返回替换后的图标；返回 null 表示不替换，继续走原方法。
     */
    private fun setNotificationIcon(
        statusBarIcon: Any?,
        context: Context,
        chain: XposedInterface.Chain
    ): Drawable? {
        try {
            if (statusBarIcon == null) return null

            val pkgName = XposedCompat.getObjectField(statusBarIcon, "pkg") as? String ?: return null

            if (!IconManager.shouldReplaceApp(context, pkgName)) {
                return null
            }

            return IconManager.getCachedAppIcon(context, pkgName) ?: return null
        } catch (_: Throwable) {
            return null
        }
    }
}
