package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
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

        // 查找 StatusBarIconView 类
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

        // 查找 NotificationIconContainer 类
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

        // 查找 IconState 类
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

        // 安装各个 Hook
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
                            if (thisObj == null || statusBarIcon == null) return

                            val pkgName = getPkgNameFromStatusBarIcon(statusBarIcon)
                            if (pkgName == null) return

                            val context: Context? = if (thisObj is View) {
                                thisObj.context
                            } else {
                                try {
                                    XposedHelpers.getObjectField(thisObj, "mContext") as? Context
                                } catch (_: Throwable) { null }
                            }
                            if (context == null) return

                            if (!shouldHandlePackage(context, pkgName)) return

                            val appIcon = getApplicationIcon(context, pkgName)
                            if (appIcon == null) return

                            log("[getIcon.before] SUCCESS: Replacing icon for pkg=$pkgName with app icon")
                            param.result = appIcon
                        } catch (t: Throwable) {
                            log("[getIcon.before] Exception: ${t.message}")
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View
                            if (view != null) {
                                log("[getIcon.after] Calling applyUniformStyle for view: $view")
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[getIcon.after] Exception: ${t.message}")
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
                            val view = param.thisObject as? View ?: return
                            val pkgName = getPkgNameFromView(view) ?: return
                            if (!shouldHandlePackage(view.context, pkgName)) return

                            log("[updateIconColor.before] Blocking tint for pkg=$pkgName")
                            if (view is ImageView) clearTint(view)
                            param.result = null
                        } catch (t: Throwable) {
                            log("[updateIconColor.before] Exception: ${t.message}")
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
                            log("[updateIconColor.after] Exception: ${t.message}")
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
                            @Suppress("UNCHECKED_CAST")
                            val iconStates = XposedHelpers.getObjectField(thisObj, "mIconStates") as? HashMap<View, Any>
                            if (iconStates == null) {
                                log("[applyIconStates.after] mIconStates is null")
                                return
                            }
                            log("[applyIconStates.after] mIconStates size = ${iconStates.size}")
                            for (view in iconStates.keys) {
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[applyIconStates.after] Exception: ${t.message}")
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
    //  Hook 4: IconState.applyToView / initFrom
    // ===================================================================
    private fun hookIconState(iconStateClass: Class<*>) {
        log("--- Installing hook: IconState methods ---")

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val view = param.args[0] as? View
                    if (view != null) {
                        log("[IconState.after] Called on view: $view")
                        applyUniformStyle(view)
                    }
                } catch (t: Throwable) {
                    log("[IconState.after] Exception: ${t.message}")
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(iconStateClass, "initFrom", View::class.java, hook)
            log("[OK] Hook IconState.initFrom(View) installed")
        } catch (t: Throwable) {
            log("[FAIL] Failed to hook IconState.initFrom")
        }
        try {
            XposedHelpers.findAndHookMethod(iconStateClass, "applyToView", View::class.java, hook)
            log("[OK] Hook IconState.applyToView(View) installed")
        } catch (t: Throwable) {
            log("[FAIL] Failed to hook IconState.applyToView")
        }
    }

    // ===================================================================
    //  Hook 5: onLayout
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
                            if (view != null && view.width > 0 && view.height > 0) {
                                log("[onLayout.after] view=$view, size=${view.width}x${view.height}")
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[onLayout.after] Exception: ${t.message}")
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
    //  核心样式应用方法（圆形 Drawable 方案 - 修复版）
    // ===================================================================
    private fun applyUniformStyle(view: View?) {
        if (view !is ImageView) {
            log("[applyUniformStyle] view is not ImageView, skip")
            return
        }

        log("[applyUniformStyle] === Entering for view=$view ===")

        val pkgName = getPkgNameFromView(view) ?: run {
            log("[applyUniformStyle] Cannot get pkg, skip")
            return
        }
        if (!shouldHandlePackage(view.context, pkgName)) {
            log("[applyUniformStyle] shouldHandlePackage false for $pkgName, skip")
            return
        }

        // 获取应用原始图标
        val appIcon = getApplicationIcon(view.context, pkgName)
        if (appIcon == null) {
            log("[applyUniformStyle] getApplicationIcon returned null for $pkgName")
            return
        }

        val targetSizePx = dpToPx(view.context, TARGET_ICON_DP)

        // 转换为圆形 Drawable（修复版本）
        val roundDrawable = createRoundDrawable(view.context, appIcon, targetSizePx)
        if (roundDrawable != null) {
            view.setImageDrawable(roundDrawable)
            log("[applyUniformStyle] Round drawable set for $pkgName")
        } else {
            // 回退：设置原始图标
            view.setImageDrawable(appIcon)
            log("[applyUniformStyle] Fallback to original drawable for $pkgName")
        }

        // 缩放模式（圆形 Drawable 已填满，但保留确保）
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.adjustViewBounds = true

        // 固定尺寸（增加延迟处理）
        try {
            val lp = view.layoutParams
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
                if (changed) view.layoutParams = lp
            } else {
                log("[applyUniformStyle] layoutParams is null, will retry after layout")
                view.post { applyUniformStyle(view) }
                return
            }
        } catch (t: Throwable) {
            log("[applyUniformStyle] LayoutParams error: ${t.message}")
        }

        // 清除 padding 和 background
        view.setPadding(0, 0, 0, 0)
        view.background = null

        // 清除着色
        clearTint(view)

        // 关闭系统裁剪，避免干扰
        view.clipToOutline = false

        // 刷新
        view.invalidate()
        log("[applyUniformStyle] === Exiting for $pkgName ===")
    }

    /**
     * 将原始 Drawable 转换为指定大小的圆形 Drawable。
     * 修复：使用 DST_IN 模式正确绘制圆形图标，避免黑色背景。
     */
    private fun createRoundDrawable(
        context: Context,
        original: Drawable,
        targetSizePx: Int
    ): Drawable? {
        return try {
            // 创建一个空 Bitmap
            val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // 绘制圆形剪裁（透明背景）
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = targetSizePx / 2f
            // 先绘制一个圆形（这里不填充颜色，仅用于剪裁）
            paint.color = android.graphics.Color.TRANSPARENT
            canvas.drawCircle(radius, radius, radius, paint)
            // 设置混合模式：只保留圆形区域内的内容
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            // 绘制原始图标
            original.setBounds(0, 0, targetSizePx, targetSizePx)
            original.draw(canvas)
            // 可选：用透明背景填充圆形外部（上面已经透明）
            BitmapDrawable(context.resources, bitmap)
        } catch (t: Throwable) {
            log("[createRoundDrawable] Failed: ${t.message}")
            null
        }
    }

    private fun clearTint(imageView: ImageView) {
        log("[clearTint] Entering for $imageView")
        try {
            imageView.imageTintList = null
            imageView.clearColorFilter()
            @Suppress("DEPRECATION")
            imageView.setColorFilter(null)
            // 尝试清除内部着色字段
            try {
                XposedHelpers.setIntField(imageView, "mCurrentSetColor", 0)
            } catch (_: Throwable) {
                try {
                    XposedHelpers.setObjectField(imageView, "mCurrentSetColor", 0)
                } catch (_: Throwable) { }
            }
            log("[clearTint] Tint cleared")
        } catch (t: Throwable) {
            log("[clearTint] Error: ${t.message}")
        }
    }

    // ===================================================================
    //  辅助方法
    // ===================================================================
    private fun getPkgNameFromView(view: View): String? {
        return try {
            val statusBarIcon = XposedHelpers.getObjectField(view, "mIcon")
            getPkgNameFromStatusBarIcon(statusBarIcon)
        } catch (t: Throwable) {
            log("[getPkgNameFromView] Failed: ${t.message}")
            null
        }
    }

    private fun getPkgNameFromStatusBarIcon(statusBarIcon: Any?): String? {
        if (statusBarIcon == null) return null
        return try {
            XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
        } catch (t: Throwable) {
            log("[getPkgNameFromStatusBarIcon] Failed: ${t.message}")
            null
        }
    }

    private fun getApplicationIcon(context: Context, pkgName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(pkgName)
        } catch (t: Throwable) {
            log("[getApplicationIcon] Failed for $pkgName: ${t.message}")
            null
        }
    }

    private fun shouldHandlePackage(context: Context, pkgName: String): Boolean {
        if (pkgName == "android" || pkgName == SYSTEMUI) return false
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val result = !isSystem && !isUpdatedSystem
            if (result) log("[shouldHandlePackage] $pkgName -> THIRD PARTY")
            else log("[shouldHandlePackage] $pkgName -> SYSTEM APP, skip")
            result
        } catch (t: Throwable) {
            log("[shouldHandlePackage] Error for $pkgName: ${t.message}")
            false
        }
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
        ).toInt()
    }
}