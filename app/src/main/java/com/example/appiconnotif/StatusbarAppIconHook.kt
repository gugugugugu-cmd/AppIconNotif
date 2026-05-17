package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.os.Build
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

    // 用于防重入的 Tag Key (使用系统未使用的 id)
    private val PROCESSED_TAG = View.generateViewId()

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }

    private fun log(t: Throwable) {
        XposedBridge.log(t)
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("=== StatusbarAppIconHook.hook() started ===")

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
    //  核心样式应用方法（使用 ViewOutlineProvider 圆形裁剪）
    // ===================================================================
    private fun applyUniformStyle(view: View?) {
        if (view !is ImageView) {
            log("[applyUniformStyle] view is not ImageView, skip")
            return
        }

        // 防重入标记
        if (view.getTag(PROCESSED_TAG) == true) {
            log("[applyUniformStyle] Already processed, skip")
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

        // 确保图标是应用原始图标（防止系统覆盖）
        val appIcon = getApplicationIcon(view.context, pkgName)
        if (appIcon != null && view.drawable !== appIcon) {
            view.setImageDrawable(appIcon)
            log("[applyUniformStyle] Reset drawable to app icon for $pkgName")
        } else if (appIcon == null) {
            log("[applyUniformStyle] Cannot get app icon for $pkgName, skip")
            return
        }

        val targetSizePx = dpToPx(view.context, TARGET_ICON_DP)

        // 延迟设置尺寸和裁剪，避免 layoutParams 为 null
        fun applySizeAndClip() {
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
                    log("[applyUniformStyle] layoutParams is null, retry after layout")
                    view.post { applySizeAndClip() }
                    return
                }
            } catch (t: Throwable) {
                log("[applyUniformStyle] LayoutParams error: ${t.message}")
            }

            // 使用 ViewOutlineProvider 实现圆形裁剪
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                view.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val w = view.width
                        val h = view.height
                        if (w > 0 && h > 0) {
                            val radius = minOf(w, h) / 2f
                            outline.setRoundRect(0, 0, w, h, radius)
                        } else {
                            outline.setEmpty()
                        }
                    }
                }
                view.clipToOutline = true
                log("[applyUniformStyle] Round clip (OutlineProvider) set for $pkgName")
            } else {
                // 低版本回退方案：使用 Bitmap 圆形 Drawable
                val fallbackDrawable = createRoundDrawable(view.context, appIcon, targetSizePx)
                if (fallbackDrawable != null) {
                    view.setImageDrawable(fallbackDrawable)
                    log("[applyUniformStyle] Fallback round drawable set for $pkgName")
                } else {
                    log("[applyUniformStyle] Fallback round drawable creation failed for $pkgName")
                }
            }
        }

        applySizeAndClip()

        // 清除所有可能影响外观的属性
        view.setPadding(0, 0, 0, 0)
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.adjustViewBounds = true
        clearTint(view)

        view.invalidate()
        view.setTag(PROCESSED_TAG, true)  // 标记已处理
        log("[applyUniformStyle] === Exiting for $pkgName ===")
    }

    /**
     * 低版本备用方案：将 Drawable 转换为指定大小的圆形 BitmapDrawable
     */
    private fun createRoundDrawable(
        context: Context,
        original: Drawable,
        targetSizePx: Int
    ): Drawable? {
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(targetSizePx, targetSizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val radius = targetSizePx / 2f
            // 先绘制一个透明圆形作为剪裁区域
            paint.color = android.graphics.Color.TRANSPARENT
            canvas.drawCircle(radius, radius, radius, paint)
            paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
            original.setBounds(0, 0, targetSizePx, targetSizePx)
            original.draw(canvas)
            android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
        } catch (t: Throwable) {
            log("[createRoundDrawable] Failed: ${t.message}")
            null
        }
    }

    /**
     * 彻底清除 ImageView 的着色
     */
    private fun clearTint(imageView: ImageView) {
        log("[clearTint] Entering for $imageView")
        try {
            imageView.imageTintList = null
            imageView.clearColorFilter()
            @Suppress("DEPRECATION")
            imageView.setColorFilter(null)
            // 反射清除内部着色字段
            try {
                XposedHelpers.setIntField(imageView, "mCurrentSetColor", 0)
            } catch (_: Throwable) { }
            try {
                XposedHelpers.setObjectField(imageView, "mDrawableColor", 0)
            } catch (_: Throwable) { }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                imageView.imageTintBlendMode = null
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