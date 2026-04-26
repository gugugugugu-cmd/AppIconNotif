package com.example.appiconnotif

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object StatusbarAppIconHook {

    private const val SYSTEMUI = "com.android.systemui"

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

            hookApplyIconStates(notificationIconContainerClass)
            hookIconState(iconStateClass)
            hookUpdateIconColor(statusBarIconViewClass)
            hookGetIcon(statusBarIconViewClass, lpparam)

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
                            @Suppress("UNCHECKED_CAST")
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
                    val isNotification = try {
                        XposedHelpers.getObjectField(icon, "mNotification") != null
                    } catch (_: Throwable) {
                        false
                    }
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
                            val isNotification = try {
                                XposedHelpers.getObjectField(param.thisObject, "mNotification") != null
                            } catch (_: Throwable) {
                                false
                            }

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

                            val sysuiContext = try {
                                XposedHelpers.getObjectField(thisObj, "mContext") as Context
                            } catch (_: Throwable) {
                                return
                            }

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
                                param
                            )
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )

            log("Hooked StatusBarIconView.getIcon(statusBarIcon)")
        } catch (t: Throwable) {
            log("Failed to hook getIcon(statusBarIcon)")
            log(t)
        }
    }

    private fun removeTintForStatusbarIcon(icon: View, isNotification: Boolean) {
        try {
            val statusBarIcon = try {
                XposedHelpers.getObjectField(icon, "mIcon")
            } catch (_: Throwable) {
                null
            } ?: return

            val pkgName = try {
                XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
            } catch (_: Throwable) {
                null
            } ?: return

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
                    (icon as? ImageView)?.imageTintList = null
                } catch (_: Throwable) {
                }

                try {
                    (icon as? ImageView)?.clearColorFilter()
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
        param: XC_MethodHook.MethodHookParam
    ) {
        try {
            if (statusBarIcon == null) return

            val pkgName = try {
                XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
            } catch (_: Throwable) {
                null
            } ?: return

            if (pkgName.contains("com.android") || pkgName.contains("systemui")) {
                return
            }

            val icon: Drawable = try {
                context.packageManager.getApplicationIcon(pkgName)
            } catch (_: Throwable) {
                return
            }

            param.result = icon
        } catch (t: Throwable) {
            log(t)
        }
    }
}