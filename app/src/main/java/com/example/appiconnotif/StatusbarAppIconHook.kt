package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
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
            hookOnLayout(statusBarIconViewClass)   // 新增：布局完成后再次应用样式
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
                                applyCircularStyleToIconView(icon)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
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
                    applyCircularStyleToIconView(icon)
                } catch (_: Throwable) {
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
        } catch (t: Throwable) {
            log("Failed to hook IconState.initFrom")
            log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "applyToView",
                View::class.java,
                hook
            )
        } catch (t: Throwable) {
            log("Failed to hook IconState.applyToView")
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
                                param.result = null
                                // 重新应用圆形样式（防止被系统覆盖）
                                val iconView = thisObj as? View
                                applyCircularStyleToIconView(iconView)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook StatusBarIconView.updateIconColor")
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

                            setNotificationIcon(statusBarIcon, appContext, param)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook getIcon(statusBarIcon)")
            log(t)
        }
    }

    // 新增：监听 onLayout，确保视图尺寸确定后圆形裁剪生效
    private fun hookOnLayout(statusBarIconViewClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "onLayout",
                Boolean::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyCircularStyleToIconView(param.thisObject as? View)
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook onLayout")
            log(t)
        }
    }

    private fun removeTintForStatusbarIcon(icon: View, isNotification: Boolean) {
        try {
            val statusBarIcon = XposedHelpers.getObjectField(icon, "mIcon")
            val pkgName = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return

            val context = (icon as? ImageView)?.context ?: return

            if (isNotification && isThirdPartyApp(context, pkgName)) {
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

            val pkgName =
                XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return

            if (!isThirdPartyApp(context, pkgName)) {
                return
            }

            val icon: Drawable = try {
                context.packageManager.getApplicationIcon(pkgName)
            } catch (_: Throwable) {
                return
            }

            param.result = icon
        } catch (_: Throwable) {
        }
    }

    // ==================== 圆形 + 20dp 样式处理 ====================
    private fun applyCircularStyleToIconView(view: View?) {
        if (view !is ImageView) return

        // 获取包名并判断是否为需要替换的应用
        val pkgName = try {
            val statusBarIcon = XposedHelpers.getObjectField(view, "mIcon")
            XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
        } catch (_: Throwable) {
            null
        } ?: return

        val context = view.context
        if (!isThirdPartyApp(context, pkgName)) return

        // 强制设置宽高为 20dp
        val targetSizePx = dpToPx(context, 20f)
        val lp = view.layoutParams
        if (lp != null && (lp.width != targetSizePx || lp.height != targetSizePx)) {
            lp.width = targetSizePx
            lp.height = targetSizePx
            view.layoutParams = lp
        }

        // 缩放类型：居中裁剪，防止变形
        view.scaleType = ImageView.ScaleType.CENTER_CROP

        // 圆形裁剪
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    val radius = view.width / 2f
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                } else {
                    outline.setRoundRect(0, 0, 1, 1, 0.5f)
                }
            }
        }
        view.clipToOutline = true

        // 清除背景与着色
        view.background = null
        view.imageTintList = null
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
    // ================================================================

    /**
     * 判断是否为第三方应用（已放宽限制，并支持强制生效包名）
     */
    private fun isThirdPartyApp(context: Context, pkgName: String): Boolean {
        // 强制对特定包名生效（用户可根据需要修改此列表）
        val forcePackages = setOf(
            "com.huawei.appmarket",   // 华为应用市场
            "com.kuyo.accelerator",   // Kuyo 加速器
            // 可继续添加其他需要生效的包名
        )
        if (pkgName in forcePackages) {
            log("Force enabling icon style for: $pkgName")
            return true
        }

        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            // 只排除真正的系统核心（systemui 和 android 本身），其它都当作第三方处理
            val isSystemCore = pkgName == "android" || pkgName == "com.android.systemui"
            !isSystemCore
        } catch (_: Throwable) {
            false
        }
    }
}