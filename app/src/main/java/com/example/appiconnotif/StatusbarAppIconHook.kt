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

    private const val TAG = "AppIconNotif"
    private const val SYSTEMUI = "com.android.systemui"
    private const val TARGET_ICON_DP = 20f

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun log(t: Throwable) {
        XposedBridge.log(t)
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("=== StatusbarAppIconHook.hook() started ===")

        // ========== 1. 查找 StatusBarIconView 类 ==========
        var statusBarIconViewClass: Class<*>? = null
        try {
            statusBarIconViewClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                lpparam.classLoader
            )
            log("[OK] Found class: StatusBarIconView -> $statusBarIconViewClass")
        } catch (t: Throwable) {
            log("[FAIL] Cannot find class: $SYSTEMUI.statusbar.StatusBarIconView")
            log(t)
            return
        }

        // ========== 2. 查找 NotificationIconContainer 类 ==========
        var notificationIconContainerClass: Class<*>? = null
        try {
            notificationIconContainerClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )
            log("[OK] Found class: NotificationIconContainer -> $notificationIconContainerClass")
        } catch (t: Throwable) {
            log("[WARN] Cannot find NotificationIconContainer, some hooks will be skipped")
            log(t)
        }

        // ========== 3. 查找 IconState 类 ==========
        var iconStateClass: Class<*>? = null
        try {
            iconStateClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState",
                lpparam.classLoader
            )
            log("[OK] Found class: IconState -> $iconStateClass")
        } catch (t: Throwable) {
            log("[WARN] Cannot find IconState, some hooks will be skipped")
            log(t)
        }

        // ========== 4. 安装各个 Hook ==========
        hookGetIcon(statusBarIconViewClass, lpparam)
        hookUpdateIconColor(statusBarIconViewClass)
        hookOnLayout(statusBarIconViewClass)

        if (notificationIconContainerClass != null) {
            hookApplyIconStates(notificationIconContainerClass)
        } else {
            log("[SKIP] hookApplyIconStates - NotificationIconContainer not available")
        }

        if (iconStateClass != null) {
            hookIconState(iconStateClass)
        } else {
            log("[SKIP] hookIconState - IconState not available")
        }

        log("=== StatusbarAppIconHook.hook() completed ===")
    }

    // ===================================================================
    //  Hook 1: getIcon(StatusBarIcon)  → 替换为应用原始图标
    // ===================================================================
    private fun hookGetIcon(
        statusBarIconViewClass: Class<*>,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        log("--- Installing hook: getIcon(StatusBarIcon) ---")

        try {
            val statusBarIconClass = XposedHelpers.findClass(
                "com.android.internal.statusbar.StatusBarIcon",
                lpparam.classLoader
            )
            log("[OK] Found class: com.android.internal.statusbar.StatusBarIcon")

            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "getIcon",
                statusBarIconClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject
                            val statusBarIcon = param.args[0]

                            if (thisObj == null || statusBarIcon == null) {
                                log("[getIcon.before] thisObj or statusBarIcon is null, skip")
                                return
                            }

                            val pkgName = getPkgNameFromStatusBarIcon(statusBarIcon)
                            if (pkgName == null) {
                                log("[getIcon.before] Cannot extract pkg from StatusBarIcon, skip")
                                return
                            }

                            // 获取 Context，如果从 View 取不到则尝试从 mContext 字段取
                            val context: Context? = if (thisObj is View) {
                                thisObj.context
                            } else {
                                try {
                                    XposedHelpers.getObjectField(thisObj, "mContext") as? Context
                                } catch (_: Throwable) {
                                    null
                                }
                            }

                            if (context == null) {
                                log("[getIcon.before] Cannot get context for pkg=$pkgName, skip")
                                return
                            }

                            log("[getIcon.before] pkg=$pkgName, checking shouldHandlePackage...")

                            if (!shouldHandlePackage(context, pkgName)) {
                                log("[getIcon.before] pkg=$pkgName -> shouldHandlePackage=false, skip")
                                return
                            }

                            val appIcon = getApplicationIcon(context, pkgName)
                            if (appIcon == null) {
                                log("[getIcon.before] Failed to get application icon for pkg=$pkgName")
                                return
                            }

                            log("[getIcon.before] SUCCESS: Replacing icon for pkg=$pkgName with app icon")
                            param.result = appIcon
                        } catch (t: Throwable) {
                            log("[getIcon.before] Exception occurred")
                            log(t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View
                            if (view != null) {
                                log("[getIcon.after] Calling applyUniformStyle for view: $view")
                                applyUniformStyle(view)
                            } else {
                                log("[getIcon.after] param.thisObject is not a View, skip applyUniformStyle")
                            }
                        } catch (t: Throwable) {
                            log("[getIcon.after] Exception in applyUniformStyle")
                            log(t)
                        }
                    }
                }
            )

            log("[OK] Hook getIcon(StatusBarIcon) installed successfully")
        } catch (t: Throwable) {
            log("[FAIL] Failed to install hook: getIcon(StatusBarIcon)")
            log(t)
        }
    }

    // ===================================================================
    //  Hook 2: updateIconColor()  → 阻止着色 & 重新应用样式
    // ===================================================================
    private fun hookUpdateIconColor(statusBarIconViewClass: Class<*>) {
        log("--- Installing hook: updateIconColor() ---")

        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "updateIconColor",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View
                            if (view == null) return

                            val pkgName = getPkgNameFromView(view)
                            if (pkgName == null) {
                                log("[updateIconColor.before] Cannot get pkg from view, skip")
                                return
                            }

                            val context = view.context
                            if (!shouldHandlePackage(context, pkgName)) {
                                log("[updateIconColor.before] pkg=$pkgName -> shouldHandlePackage=false, skip")
                                return
                            }

                            log("[updateIconColor.before] Blocking tint for pkg=$pkgName")

                            // 清除着色
                            if (view is ImageView) {
                                clearTint(view)
                            } else {
                                log("[updateIconColor.before] view is not ImageView, skip clearTint")
                            }

                            // 阻止系统着色
                            param.result = null
                        } catch (t: Throwable) {
                            log("[updateIconColor.before] Exception")
                            log(t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View
                            if (view != null) {
                                log("[updateIconColor.after] Calling applyUniformStyle")
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[updateIconColor.after] Exception")
                            log(t)
                        }
                    }
                }
            )

            log("[OK] Hook updateIconColor() installed successfully")
        } catch (t: Throwable) {
            log("[FAIL] Failed to install hook: updateIconColor()")
            log(t)
        }
    }

    // ===================================================================
    //  Hook 3: applyIconStates()  → 批量应用后修正样式
    // ===================================================================
    private fun hookApplyIconStates(notificationIconContainerClass: Class<*>) {
        log("--- Installing hook: applyIconStates() ---")

        try {
            XposedHelpers.findAndHookMethod(
                notificationIconContainerClass,
                "applyIconStates",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject
                            log("[applyIconStates.after] Called on: $thisObj")

                            @Suppress("UNCHECKED_CAST")
                            val iconStates = XposedHelpers.getObjectField(
                                thisObj,
                                "mIconStates"
                            ) as? HashMap<View, Any>

                            if (iconStates == null) {
                                log("[applyIconStates.after] mIconStates is null or not HashMap")
                                return
                            }

                            log("[applyIconStates.after] mIconStates size = ${iconStates.size}")

                            for ((index, view) in iconStates.keys.withIndex()) {
                                log("[applyIconStates.after] [$index] Processing view: $view")
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[applyIconStates.after] Exception")
                            log(t)
                        }
                    }
                }
            )

            log("[OK] Hook applyIconStates() installed successfully")
        } catch (t: Throwable) {
            log("[FAIL] Failed to install hook: applyIconStates()")
            log(t)
        }
    }

    // ===================================================================
    //  Hook 4: IconState.applyToView / initFrom  → 单个状态应用后修正样式
    // ===================================================================
    private fun hookIconState(iconStateClass: Class<*>) {
        log("--- Installing hook: IconState.applyToView & initFrom ---")

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val view = param.args[0] as? View
                    if (view != null) {
                        log("[IconState.after] Called on view: $view")
                        applyUniformStyle(view)
                    } else {
                        log("[IconState.after] arg[0] is not a View")
                    }
                } catch (t: Throwable) {
                    log("[IconState.after] Exception")
                    log(t)
                }
            }
        }

        // Hook initFrom
        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "initFrom",
                View::class.java,
                hook
            )
            log("[OK] Hook IconState.initFrom(View) installed")
        } catch (t: Throwable) {
            log("[FAIL] Failed to hook IconState.initFrom")
            log(t)
        }

        // Hook applyToView
        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "applyToView",
                View::class.java,
                hook
            )
            log("[OK] Hook IconState.applyToView(View) installed")
        } catch (t: Throwable) {
            log("[FAIL] Failed to hook IconState.applyToView")
            log(t)
        }
    }

    // ===================================================================
    //  Hook 5: onLayout  → 布局确定后重新应用样式
    // ===================================================================
    private fun hookOnLayout(statusBarIconViewClass: Class<*>) {
        log("--- Installing hook: onLayout() ---")

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
                            val view = param.thisObject as? View
                            if (view != null) {
                                log("[onLayout.after] view=$view, width=${view.width}, height=${view.height}")
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[onLayout.after] Exception")
                            log(t)
                        }
                    }
                }
            )

            log("[OK] Hook onLayout() installed successfully")
        } catch (t: Throwable) {
            log("[FAIL] Failed to install hook: onLayout()")
            log(t)
        }
    }

    // ===================================================================
    //  核心样式应用方法
    // ===================================================================
    private fun applyUniformStyle(view: View?) {
        if (view == null) {
            log("[applyUniformStyle] view is null, skip")
            return
        }

        log("[applyUniformStyle] === Entering applyUniformStyle for view=$view ===")

        // 1) 必须是 ImageView
        if (view !is ImageView) {
            log("[applyUniformStyle] view is not ImageView (type=${view.javaClass.name}), skip")
            return
        }

        // 2) 获取包名
        val pkgName = getPkgNameFromView(view)
        if (pkgName == null) {
            log("[applyUniformStyle] Cannot get pkg from view, skip")
            return
        }
        log("[applyUniformStyle] pkg=$pkgName")

        // 3) 判断是否处理
        val context = view.context
        if (!shouldHandlePackage(context, pkgName)) {
            log("[applyUniformStyle] shouldHandlePackage=false for pkg=$pkgName, skip")
            return
        }

        log("[applyUniformStyle] Processing pkg=$pkgName")

        // 4) 设置应用原始图标
        val appIcon = getApplicationIcon(context, pkgName)
        if (appIcon != null) {
            try {
                view.setImageDrawable(appIcon)
                log("[applyUniformStyle] App icon set successfully for pkg=$pkgName")
            } catch (t: Throwable) {
                log("[applyUniformStyle] Failed to setImageDrawable for pkg=$pkgName")
                log(t)
            }
        } else {
            log("[applyUniformStyle] getApplicationIcon returned null for pkg=$pkgName")
        }

        // 5) 设置 20dp 固定大小
        val targetSizePx = dpToPx(context, TARGET_ICON_DP)
        log("[applyUniformStyle] targetSizePx=$targetSizePx (${TARGET_ICON_DP}dp)")

        try {
            val lp = view.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.width != targetSizePx) {
                    log("[applyUniformStyle] Width changing from ${lp.width} to $targetSizePx")
                    lp.width = targetSizePx
                    changed = true
                }
                if (lp.height != targetSizePx) {
                    log("[applyUniformStyle] Height changing from ${lp.height} to $targetSizePx")
                    lp.height = targetSizePx
                    changed = true
                }
                if (changed) {
                    view.layoutParams = lp
                    log("[applyUniformStyle] LayoutParams updated")
                } else {
                    log("[applyUniformStyle] LayoutParams already correct: $targetSizePx x $targetSizePx")
                }
            } else {
                log("[applyUniformStyle] layoutParams is null, cannot set size")
            }
        } catch (t: Throwable) {
            log("[applyUniformStyle] Exception while setting LayoutParams")
            log(t)
        }

        // 6) 缩放模式
        try {
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.adjustViewBounds = false
            log("[applyUniformStyle] scaleType set to CENTER_CROP")
        } catch (t: Throwable) {
            log("[applyUniformStyle] Failed to set scaleType")
            log(t)
        }

        // 7) 清除 padding 和 background
        try {
            view.setPadding(0, 0, 0, 0)
            view.background = null
            log("[applyUniformStyle] Padding cleared, background set to null")
        } catch (t: Throwable) {
            log("[applyUniformStyle] Failed to clear padding/background")
            log(t)
        }

        // 8) 清除着色
        clearTint(view)

        // 9) 圆形裁剪
        try {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    val w = if (v.width > 0) v.width else targetSizePx
                    val h = if (v.height > 0) v.height else targetSizePx
                    val radius = minOf(w, h) / 2f
                    outline.setRoundRect(0, 0, w, h, radius)
                    log("[applyUniformStyle.outlineProvider] outline set: w=$w, h=$h, radius=$radius")
                }
            }
            view.clipToOutline = true
            log("[applyUniformStyle] clipToOutline enabled")
        } catch (t: Throwable) {
            log("[applyUniformStyle] Failed to set outlineProvider/clipToOutline")
            log(t)
        }

        // 10) 刷新
        try {
            view.invalidate()
            log("[applyUniformStyle] invalidate() called")
        } catch (t: Throwable) {
            log("[applyUniformStyle] invalidate() failed")
            log(t)
        }

        log("[applyUniformStyle] === Exiting applyUniformStyle for pkg=$pkgName ===")
    }

    private fun clearTint(imageView: ImageView) {
        log("[clearTint] Entering clearTint for $imageView")

        try {
            imageView.imageTintList = null
            log("[clearTint] imageTintList set to null")
        } catch (t: Throwable) {
            log("[clearTint] Failed to clear imageTintList")
            log(t)
        }

        try {
            imageView.clearColorFilter()
            log("[clearTint] clearColorFilter() called")
        } catch (t: Throwable) {
            log("[clearTint] Failed to clearColorFilter")
            log(t)
        }

        try {
            @Suppress("DEPRECATION")
            imageView.setColorFilter(null)
            log("[clearTint] setColorFilter(null) called")
        } catch (t: Throwable) {
            log("[clearTint] Failed to setColorFilter(null)")
            log(t)
        }

        // 尝试清除 mCurrentSetColor
        try {
            XposedHelpers.setIntField(imageView, "mCurrentSetColor", 0)
            log("[clearTint] mCurrentSetColor (int) set to 0")
        } catch (_: Throwable) {
            try {
                XposedHelpers.setObjectField(imageView, "mCurrentSetColor", 0)
                log("[clearTint] mCurrentSetColor (Object) set to 0")
            } catch (_: Throwable) {
                log("[clearTint] Cannot clear mCurrentSetColor (field may not exist)")
            }
        }

        log("[clearTint] Exiting clearTint")
    }

    // ===================================================================
    //  辅助方法
    // ===================================================================
    private fun getPkgNameFromView(view: View): String? {
        return try {
            val statusBarIcon = XposedHelpers.getObjectField(view, "mIcon")
            if (statusBarIcon == null) {
                log("[getPkgNameFromView] mIcon field is null")
                return null
            }
            val pkg = getPkgNameFromStatusBarIcon(statusBarIcon)
            if (pkg == null) {
                log("[getPkgNameFromView] Cannot extract pkg from mIcon")
            }
            pkg
        } catch (t: Throwable) {
            log("[getPkgNameFromView] Cannot access mIcon field on view: $view")
            log(t)
            null
        }
    }

    private fun getPkgNameFromStatusBarIcon(statusBarIcon: Any?): String? {
        if (statusBarIcon == null) {
            log("[getPkgNameFromStatusBarIcon] statusBarIcon is null")
            return null
        }
        return try {
            val pkg = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
            if (pkg == null) {
                log("[getPkgNameFromStatusBarIcon] pkg field is null or not String")
            }
            pkg
        } catch (t: Throwable) {
            log("[getPkgNameFromStatusBarIcon] Cannot access pkg field")
            log(t)
            null
        }
    }

    private fun getApplicationIcon(context: Context, pkgName: String): Drawable? {
        log("[getApplicationIcon] Trying to get app icon for pkg=$pkgName")
        return try {
            val drawable = context.packageManager.getApplicationIcon(pkgName)
            log("[getApplicationIcon] SUCCESS: Got app icon for pkg=$pkgName")
            drawable
        } catch (t: Throwable) {
            log("[getApplicationIcon] FAILED: Cannot get app icon for pkg=$pkgName")
            log(t)
            null
        }
    }

    // ===================================================================
    //  包名过滤策略
    // ===================================================================
    private fun shouldHandlePackage(context: Context, pkgName: String): Boolean {
        // 排除 android 和 SystemUI
        if (pkgName == "android") {
            log("[shouldHandlePackage] pkg=$pkgName -> excluded (android core)")
            return false
        }
        if (pkgName == SYSTEMUI) {
            log("[shouldHandlePackage] pkg=$pkgName -> excluded (SystemUI itself)")
            return false
        }

        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp =
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            val result = !isSystemApp && !isUpdatedSystemApp

            if (result) {
                log("[shouldHandlePackage] pkg=$pkgName -> THIRD PARTY -> will handle")
            } else {
                log("[shouldHandlePackage] pkg=$pkgName -> SYSTEM APP -> will skip (isSystemApp=$isSystemApp, isUpdatedSystemApp=$isUpdatedSystemApp)")
            }

            result
        } catch (t: Throwable) {
            log("[shouldHandlePackage] pkg=$pkgName -> PackageManager error, skip")
            log(t)
            false
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
        log("[dpToPx] ${dp}dp = ${px}px")
        return px
    }
}