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
    private const val TARGET_ICON_DP = 20f

    private fun log(msg: String) {
        XposedBridge.log("AppIconNotif: $msg")
    }

    private fun log(t: Throwable) {
        XposedBridge.log(t)
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val statusBarIconViewClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                lpparam.classLoader
            )

            val notificationIconContainerClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )

            val iconStateClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState",
                lpparam.classLoader
            )

            hookGetIcon(statusBarIconViewClass, lpparam)
            hookUpdateIconColor(statusBarIconViewClass)
            hookOnLayout(statusBarIconViewClass)
            hookApplyIconStates(notificationIconContainerClass)
            hookIconState(iconStateClass)

            log("StatusbarAppIconHook hooks installed")
        } catch (t: Throwable) {
            log("Failed to initialize StatusbarAppIconHook")
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
                            val thisObj = param.thisObject as? View ?: return
                            val statusBarIcon = param.args[0] ?: return

                            val context = thisObj.context
                            val pkgName = getPkgNameFromStatusBarIcon(statusBarIcon) ?: return

                            if (!shouldHandlePackage(context, pkgName)) return

                            val appIcon = getApplicationIcon(context, pkgName) ?: return
                            param.result = appIcon
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            applyUniformStyle(param.thisObject as? View)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook StatusBarIconView.getIcon")
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
                            val imageView = param.thisObject as? ImageView ?: return
                            val pkgName = getPkgNameFromView(imageView) ?: return

                            if (!shouldHandlePackage(imageView.context, pkgName)) return

                            clearTint(imageView)
                            param.result = null
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            applyUniformStyle(param.thisObject as? View)
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

    private fun hookApplyIconStates(notificationIconContainerClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                notificationIconContainerClass,
                "applyIconStates",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val iconStates = XposedHelpers.getObjectField(
                                param.thisObject,
                                "mIconStates"
                            ) as? HashMap<View, Any> ?: return

                            for (view in iconStates.keys) {
                                applyUniformStyle(view)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook NotificationIconContainer.applyIconStates")
            log(t)
        }
    }

    private fun hookIconState(iconStateClass: Class<*>) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val view = param.args[0] as? View ?: return
                    applyUniformStyle(view)
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

    private fun hookOnLayout(statusBarIconViewClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            applyUniformStyle(param.thisObject as? View)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook StatusBarIconView.onLayout")
            log(t)
        }
    }

    private fun applyUniformStyle(view: View?) {
        val imageView = view as? ImageView ?: return
        val context = imageView.context
        val pkgName = getPkgNameFromView(imageView) ?: return

        if (!shouldHandlePackage(context, pkgName)) return

        val appIcon = getApplicationIcon(context, pkgName)
        if (appIcon != null) {
            try {
                imageView.setImageDrawable(appIcon)
            } catch (_: Throwable) {
            }
        }

        val targetSizePx = dpToPx(context, TARGET_ICON_DP)

        try {
            val lp = imageView.layoutParams
            if (lp != null) {
                var changed = false

                if (lp.width != targetSizePx) {
                    lp.width = targetSizePx
                    changed = true
                }
                if (lp.height != targetSizePx) {
                    lp.height = targetSizePx
                    changed = true
                }

                if (changed) {
                    imageView.layoutParams = lp
                }
            }
        } catch (_: Throwable) {
        }

        try {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView.adjustViewBounds = false
        } catch (_: Throwable) {
        }

        try {
            imageView.setPadding(0, 0, 0, 0)
        } catch (_: Throwable) {
        }

        try {
            imageView.background = null
        } catch (_: Throwable) {
        }

        clearTint(imageView)

        try {
            imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = if (view.width > 0) view.width else targetSizePx
                    val h = if (view.height > 0) view.height else targetSizePx
                    val radius = minOf(w, h) / 2f
                    outline.setRoundRect(0, 0, w, h, radius)
                }
            }
            imageView.clipToOutline = true
        } catch (_: Throwable) {
        }

        try {
            imageView.invalidate()
        } catch (_: Throwable) {
        }
    }

    private fun clearTint(imageView: ImageView) {
        try {
            imageView.imageTintList = null
        } catch (_: Throwable) {
        }

        try {
            imageView.clearColorFilter()
        } catch (_: Throwable) {
        }

        try {
            @Suppress("DEPRECATION")
            imageView.setColorFilter(null)
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.setIntField(imageView, "mCurrentSetColor", 0)
        } catch (_: Throwable) {
            try {
                XposedHelpers.setObjectField(imageView, "mCurrentSetColor", 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun getPkgNameFromView(view: View): String? {
        return try {
            val statusBarIcon = XposedHelpers.getObjectField(view, "mIcon")
            getPkgNameFromStatusBarIcon(statusBarIcon)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getPkgNameFromStatusBarIcon(statusBarIcon: Any?): String? {
        if (statusBarIcon == null) return null
        return try {
            XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun getApplicationIcon(context: Context, pkgName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(pkgName)
        } catch (_: Throwable) {
            null
        }
    }

    private fun shouldHandlePackage(context: Context, pkgName: String): Boolean {
        if (pkgName == "android" || pkgName == SYSTEMUI) return false

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

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}