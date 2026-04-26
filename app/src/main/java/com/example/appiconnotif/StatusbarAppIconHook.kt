package com.example.appiconnotif

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.util.TypedValue
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object StatusbarAppIconHook {

    private const val SYSTEMUI = "com.android.systemui"
    private const val FRAMEWORK = "android"

    private fun log(msg: String) {
        XposedBridge.log("AppIconNotif: $msg")
    }

    private fun log(t: Throwable) {
        XposedBridge.log(t)
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val notificationIconContainerClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )

            val iconStateClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState",
                lpparam.classLoader
            )

            val statusBarIconViewClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                lpparam.classLoader
            )

            val scalingDrawableWrapperClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.ScalingDrawableWrapper",
                lpparam.classLoader
            )

            val notificationIconAreaControllerClass = try {
                XposedHelpers.findClass(
                    "$SYSTEMUI.statusbar.phone.LegacyNotificationIconAreaControllerImpl",
                    lpparam.classLoader
                )
            } catch (_: Throwable) {
                try {
                    XposedHelpers.findClass(
                        "$SYSTEMUI.statusbar.phone.NotificationIconAreaController",
                        lpparam.classLoader
                    )
                } catch (_: Throwable) {
                    null
                }
            }

            hookApplyIconStates(notificationIconContainerClass)
            hookIconState(iconStateClass)
            hookUpdateIconColor(statusBarIconViewClass)
            hookUpdateTintForIcon(notificationIconAreaControllerClass)
            hookGetIcon(statusBarIconViewClass, scalingDrawableWrapperClass)

            log("StatusbarAppIconHook initialized")
        } catch (t: Throwable) {
            log("Failed to initialize StatusbarAppIconHook")
            log(t)
        }
    }

    private fun hookApplyIconStates(notificationIconContainerClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                notificationIconContainerClass,
                "applyIconStates",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val iconStates =
                                XposedHelpers.getObjectField(param.thisObject, "mIconStates")
                                        as? HashMap<View, Any> ?: return

                            for (icon in iconStates.keys) {
                                removeTintForStatusbarIcon(icon, false)
                            }
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )
            log("Hooked NotificationIconContainer.applyIconStates")
        } catch (t: Throwable) {
            log("Failed to hook applyIconStates")
            log(t)
        }
    }

    private fun hookIconState(iconStateClass: Class<*>) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val icon = param.args[0] as? View ?: return
                    val isNotification = XposedHelpers.getObjectField(icon, "mNotification") != null
                    removeTintForStatusbarIcon(icon, isNotification)
                } catch (t: Throwable) {
                    log(t)
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "initFrom",
                View::class.java,
                hook
            )
            log("Hooked IconState.initFrom")
        } catch (t: Throwable) {
            log("Failed to hook initFrom")
            log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "applyToView",
                View::class.java,
                hook
            )
            log("Hooked IconState.applyToView")
        } catch (t: Throwable) {
            log("Failed to hook applyToView")
            log(t)
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
                            val isNotification =
                                XposedHelpers.getObjectField(param.thisObject, "mNotification") != null
                            if (isNotification) {
                                param.result = null
                            }
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )
            log("Hooked StatusBarIconView.updateIconColor")
        } catch (t: Throwable) {
            log("Failed to hook updateIconColor")
            log(t)
        }
    }

    private fun hookUpdateTintForIcon(controllerClass: Class<*>?) {
        if (controllerClass == null) return

        try {
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "updateTintForIcon",
                View::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.args[0] as? View ?: return
                            removeTintForStatusbarIcon(view, true)

                            try {
                                XposedHelpers.callMethod(view, "setStaticDrawableColor", 0)
                            } catch (_: Throwable) {
                            }

                            try {
                                XposedHelpers.callMethod(view, "setDecorColor", Color.WHITE)
                            } catch (_: Throwable) {
                            }
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )
            log("Hooked updateTintForIcon")
        } catch (t: Throwable) {
            log("Failed to hook updateTintForIcon")
            log(t)
        }
    }

    private fun hookGetIcon(
        statusBarIconViewClass: Class<*>,
        scalingDrawableWrapperClass: Class<*>
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "getIcon",
                Context::class.java,
                Context::class.java,
                XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon", null),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val sysuiContext = param.args[0] as Context
                            val appContext = param.args[1] as Context
                            val statusBarIcon = param.args[2]
                            setNotificationIcon(
                                statusBarIcon,
                                appContext,
                                sysuiContext,
                                param,
                                scalingDrawableWrapperClass
                            )
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )
            log("Hooked StatusBarIconView.getIcon(context, context, statusBarIcon)")
            return
        } catch (t: Throwable) {
            log("3-arg getIcon hook failed, trying fallback")
        }

        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "getIcon",
                XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon", null),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject
                            val statusBarIcon = param.args[0]

                            val sysuiContext = XposedHelpers.getObjectField(thisObj, "mContext") as Context
                            val sbn = try {
                                XposedHelpers.getObjectField(thisObj, "mNotification")
                            } catch (_: Throwable) {
                                null
                            }

                            var appContext: Context? = null
                            if (sbn != null) {
                                appContext = try {
                                    XposedHelpers.callMethod(sbn, "getPackageContext", sysuiContext) as? Context
                                } catch (_: Throwable) {
                                    null
                                }
                            }

                            if (appContext == null) appContext = sysuiContext

                            setNotificationIcon(
                                statusBarIcon,
                                appContext,
                                sysuiContext,
                                param,
                                scalingDrawableWrapperClass
                            )
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )
            log("Hooked StatusBarIconView.getIcon(statusBarIcon)")
        } catch (t: Throwable) {
            log("Failed to hook getIcon")
            log(t)
        }
    }

    private fun removeTintForStatusbarIcon(icon: View, isNotification: Boolean) {
        try {
            val statusBarIcon = XposedHelpers.getObjectField(icon, "mIcon")
            val pkgName = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return

            if (isNotification && !pkgName.contains("systemui")) {
                try {
                    XposedHelpers.setIntField(icon, "mCurrentSetColor", 0)
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.setObjectField(icon, "mCurrentSetColor", 0)
                    } catch (_: Throwable) {
                    }
                }

                try {
                    XposedHelpers.callMethod(icon, "updateIconColor")
                } catch (_: Throwable) {
                }

                try {
                    (icon as? android.widget.ImageView)?.imageTintList = null
                } catch (_: Throwable) {
                }

                try {
                    (icon as? android.widget.ImageView)?.clearColorFilter()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            log(t)
        }
    }

    private fun setNotificationIcon(
        statusBarIcon: Any?,
        context: Context,
        sysuiContext: Context,
        param: XC_MethodHook.MethodHookParam,
        scalingDrawableWrapperClass: Class<*>
    ) {
        try {
            if (statusBarIcon == null) return

            val pkgName = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return
            if (pkgName.contains("com.android") || pkgName.contains("systemui")) return

            var icon: Drawable = try {
                context.packageManager.getApplicationIcon(pkgName)
            } catch (_: Throwable) {
                return
            }

            val res = sysuiContext.resources

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val isLowRamDevice = ActivityManager.isLowRamDeviceStatic()

                val maxIconSize = res.getDimensionPixelSize(
                    res.getIdentifier(
                        if (isLowRamDevice) {
                            "notification_small_icon_size_low_ram"
                        } else {
                            "notification_small_icon_size"
                        },
                        "dimen",
                        FRAMEWORK
                    )
                )

                icon = downscaleDrawable(icon, maxIconSize, maxIconSize)
            }

            val typedValue = TypedValue()
            res.getValue(
                res.getIdentifier(
                    "status_bar_icon_scale_factor",
                    "dimen",
                    SYSTEMUI
                ),
                typedValue,
                true
            )
            val scaleFactor = typedValue.float

            param.result = if (scaleFactor == 1f) {
                icon
            } else {
                scalingDrawableWrapperClass.getConstructor(
                    Drawable::class.java,
                    Float::class.javaPrimitiveType
                ).newInstance(icon, scaleFactor)
            }
        } catch (t: Throwable) {
            log(t)
        }
    }

    private fun downscaleDrawable(drawable: Drawable, width: Int, height: Int): Drawable {
        drawable.setBounds(0, 0, width, height)
        return drawable
    }
}