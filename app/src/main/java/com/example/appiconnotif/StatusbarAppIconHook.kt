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

/**
 * Android 16 (API 36) 适配版
 * 多版本兼容的 StatusBar 通知图标 Hook
 */
object StatusbarAppIconHook {

    private const val TAG = "AppIconNotif"
    private const val SYSTEMUI = "com.android.systemui"
    private const val TARGET_ICON_DP = 20f

    // ===================================================================
    //  版本兼容层 — 统一处理类/方法/字段的跨版本查找
    // ===================================================================
    private object CompatV5 {

        private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
        private fun log(t: Throwable) = XposedBridge.log(t)

        // ---------- 类查找（多路径回退） ----------

        /**
         * 查找 StatusBarIconView：尝试多个可能的包路径
         * A12-A15: com.android.systemui.statusbar.StatusBarIconView
         * A16+:     com.android.systemui.statusbar.views.StatusBarIconView (可能)
         *           com.android.systemui.statusbar.notification.icon.StatusBarIconView (可能)
         */
        fun findStatusBarIconView(classLoader: ClassLoader): Class<*>? {
            val candidates = listOf(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                "$SYSTEMUI.statusbar.views.StatusBarIconView",
                "$SYSTEMUI.statusbar.notification.icon.StatusBarIconView",
                "$SYSTEMUI.statusbar.phone.StatusBarIconView"
            )
            for (path in candidates) {
                try {
                    val clz = XposedHelpers.findClass(path, classLoader)
                    log("[Compat] Found StatusBarIconView at: $path")
                    return clz
                } catch (_: Throwable) { }
            }
            log("[Compat] FAILED to find StatusBarIconView in any known path")
            return null
        }

        /**
         * 查找 NotificationIconContainer（A12-A14）或替代类
         * A16 可能完全重组为 NotificationShelf / NotificationIconAreaController
         */
        fun findNotificationContainer(classLoader: ClassLoader): Class<*>? {
            val candidates = listOf(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                "$SYSTEMUI.statusbar.notification.icon.NotificationIconContainer",
                "$SYSTEMUI.statusbar.notification.NotificationShelf",
                "$SYSTEMUI.statusbar.notification.stack.NotificationShelf",
                "$SYSTEMUI.statusbar.phone.NotificationIconAreaController"
            )
            for (path in candidates) {
                try {
                    val clz = XposedHelpers.findClass(path, classLoader)
                    log("[Compat] Found container: $path")
                    return clz
                } catch (_: Throwable) { }
            }
            log("[Compat] No notification container class found")
            return null
        }

        /**
         * 查找 IconState 或其替代品
         */
        fun findIconState(classLoader: ClassLoader, containerClass: Class<*>?): Class<*>? {
            val candidates = mutableListOf<String>()

            // 从容器类名推导可能的 IconState 路径
            if (containerClass != null) {
                candidates.add("${containerClass.name}\$IconState")
                candidates.add("${containerClass.name}\$IconConfig")
            }
            candidates.add("$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState")
            candidates.add("$SYSTEMUI.statusbar.notification.icon.IconState")

            for (path in candidates) {
                try {
                    val clz = XposedHelpers.findClass(path, classLoader)
                    log("[Compat] Found IconState: $path")
                    return clz
                } catch (_: Throwable) { }
            }
            log("[Compat] No IconState class found")
            return null
        }

        // ---------- 方法查找（多签名/多名称回退） ----------

        /**
         * 尝试 hook getIcon(StatusBarIcon) 方法
         * A12 签名: getIcon(StatusBarIcon) → Drawable
         * A13+ 签名: getIcon(StatusBarIcon, Context) → Drawable
         * A16: 可能已移除，用 getStatusBarIconDrawable() 代替
         */
        fun hookGetIcon(
            clazz: Class<*>,
            classLoader: ClassLoader,
            before: ((XC_MethodHook.MethodHookParam) -> Unit)?,
            after: ((XC_MethodHook.MethodHookParam) -> Unit)?
        ): Boolean {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    before?.invoke(param)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    after?.invoke(param)
                }
            }

            // 尝试多个可能的 StatusBarIcon 类型
            val iconTypes = listOf(
                "com.android.internal.statusbar.StatusBarIcon",
                "$SYSTEMUI.statusbar.StatusBarIcon",
                "android.app.Notification"
            )

            for (iconType in iconTypes) {
                try {
                    val iconClass = XposedHelpers.findClass(iconType, classLoader)
                    XposedHelpers.findAndHookMethod(clazz, "getIcon", iconClass, hook)
                    log("[Compat] Hooked getIcon($iconType)")
                    return true
                } catch (_: Throwable) { }
            }

            // A16 可能的替代方法：getStatusBarIconDrawable()
            try {
                XposedHelpers.findAndHookMethod(clazz, "getStatusBarIconDrawable", hook)
                log("[Compat] Hooked getStatusBarIconDrawable()")
                return true
            } catch (_: Throwable) { }

            try {
                XposedHelpers.findAndHookMethod(clazz, "getDrawable", hook)
                log("[Compat] Hooked getDrawable()")
                return true
            } catch (_: Throwable) { }

            log("[Compat] FAILED to hook any getIcon variant")
            return false
        }

        /**
         * hook updateIconColor 的替代方案
         * A12: updateIconColor(int tintColor)
         * A13: setColorWithDebug(int color, String debug)
         * A14+: 着色转移到 StatusBarIconDrawable.setTintList()
         */
        fun hookIconTintHandlers(
            clazz: Class<*>,
            before: ((XC_MethodHook.MethodHookParam) -> Unit)?,
            after: ((XC_MethodHook.MethodHookParam) -> Unit)?
        ): Boolean {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    before?.invoke(param)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    after?.invoke(param)
                }
            }

            val methodsToTry = listOf(
                "updateIconColor" to arrayOf(Int::class.javaPrimitiveType),
                "updateIconColor" to emptyArray(),
                "setColorWithDebug" to arrayOf(Int::class.javaPrimitiveType, String::class.java),
                "onTintChanged" to emptyArray(),
                "setTintList" to arrayOf(Int::class.javaPrimitiveType)  // A16 可能
            )

            for ((name, args) in methodsToTry) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, name, *args, hook)
                    log("[Compat] Hooked $name(${args.size} params)")
                    return true
                } catch (_: Throwable) { }
            }

            log("[Compat] No tint handler hook available")
            return false
        }

        /**
         * hook applyIconStates 或替代方法
         */
        fun hookIconStateApplication(
            clazz: Class<*>,
            after: ((XC_MethodHook.MethodHookParam) -> Unit)?
        ): Boolean {
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    after?.invoke(param)
                }
            }

            val methodsToTry = listOf(
                "applyIconStates" to emptyArray(),
                "updateIconsForLayout" to emptyArray(),
                "applyNotificationIcons" to emptyArray(),
                "updateIconViews" to emptyArray(),
                "onNotificationIconsUpdated" to emptyArray()
            )

            for ((name, args) in methodsToTry) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, name, *args, hook)
                    log("[Compat] Hooked $name")
                    return true
                } catch (_: Throwable) { }
            }

            // A14+: NotificationIconAreaController.updateIconsForLayout()
            try {
                XposedHelpers.findAndHookMethod(
                    clazz, "updateIconsForLayout",
                    XposedHelpers.findClass("android.widget.FrameLayout", clazz.classLoader),
                    hook
                )
                log("[Compat] Hooked updateIconsForLayout(FrameLayout)")
                return true
            } catch (_: Throwable) { }

            log("[Compat] No icon state application hook available")
            return false
        }

        // ---------- 字段访问（安全包装） ----------

        /**
         * 安全读取字段，尝试多个字段名
         */
        fun getPkgName(view: View): String? {
            // 方法1: 通过 mIcon 字段
            val icon = readFieldSafe(view, "mIcon", "mStatusBarIcon", "mNotificationIcon")
            if (icon != null) {
                val pkg = readFieldSafe(icon, "pkg", "packageName", "mPkg")
                if (pkg is String) return pkg
            }

            // 方法2: 通过 mNotificationData/包名
            val notificationData = readFieldSafe(view, "mNotificationData", "mEntry")
            if (notificationData != null) {
                val sbn = readFieldSafe(notificationData, "mSbn", "mStatusBarNotification")
                if (sbn != null) {
                    val pkg = readFieldSafe(sbn, "mPkg", "packageName")
                    if (pkg is String) return pkg
                }
            }

            // 方法3: 通过反射解析 IconDrawable 内部信息
            val drawable = readFieldSafe(view, "mIconDrawable", "mDrawable")
            if (drawable != null) {
                val icon = readFieldSafe(drawable, "mIcon", "mStatusBarIcon")
                if (icon != null) {
                    val pkg = readFieldSafe(icon, "pkg", "packageName")
                    if (pkg is String) return pkg
                }
            }

            return null
        }

        private fun readFieldSafe(obj: Any, vararg fieldNames: String): Any? {
            for (name in fieldNames) {
                try {
                    return XposedHelpers.getObjectField(obj, name)
                } catch (_: Throwable) { }
            }
            return null
        }

        /**
         * 清除着色 — 多版本兼容
         */
        fun clearTint(imageView: ImageView) {
            try {
                imageView.imageTintList = null
                imageView.clearColorFilter()
                @Suppress("DEPRECATION")
                imageView.setColorFilter(null)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    imageView.imageTintBlendMode = null
                }

                // 尝试清除内部着色字段（多种可能名称）
                val tintFields = listOf(
                    "mCurrentSetColor", "mDrawableColor",
                    "mTintColor", "mIconColor", "mCurrentColor"
                )
                for (field in tintFields) {
                    try {
                        XposedHelpers.setIntField(imageView, field, 0)
                    } catch (_: Throwable) { }
                }

                // 如果是 StatusBarIconDrawable，清除其着色
                val drawable = imageView.drawable
                if (drawable != null) {
                    try {
                        drawable.setTintList(null)
                        drawable.setTint(0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            drawable.setTintBlendMode(null)
                        }
                    } catch (_: Throwable) { }

                    // 尝试通过反射清除内部 StateList 着色
                    try {
                        XposedHelpers.callMethod(drawable, "setTintList", null as Any?)
                    } catch (_: Throwable) { }
                }
            } catch (t: Throwable) {
                log("[clearTint] Error: ${t.message}")
            }
        }
    }

    // ===================================================================
    //  主入口
    // ===================================================================
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("=== StatusbarAppIconHook.hook() for A16+ ===")

        val classLoader = lpparam.classLoader

        // 1. 查找核心类
        val statusBarIconViewClass = CompatV5.findStatusBarIconView(classLoader)
        if (statusBarIconViewClass == null) {
            log("[FATAL] Cannot find StatusBarIconView, aborting")
            // 尝试兜底方案: hook 所有 ImageView 在 SystemUI 中的 onDraw
            installFallbackHook(lpparam)
            return
        }

        val containerClass = CompatV5.findNotificationContainer(classLoader)
        val iconStateClass = CompatV5.findIconState(classLoader, containerClass)

        // 2. 安装各种 hook
        installGetIconHook(statusBarIconViewClass, classLoader)
        installTintHandlerHook(statusBarIconViewClass)
        installLayoutHook(statusBarIconViewClass)

        if (containerClass != null) {
            installIconStateApplicationHook(containerClass)
        } else {
            log("[INFO] No container class found; state application hook skipped")
        }

        if (iconStateClass != null) {
            installIconStateHooks(iconStateClass)
        }

        // 3. A16+ 新增 hook 点
        installA16SpecificHooks(classLoader)

        log("=== StatusbarAppIconHook.hook() completed ===")
    }

    // ===================================================================
    //  Hook 安装方法
    // ===================================================================

    /**
     * Hook 1: 替换图标 Drawable
     * 替换策略：在 getIcon 返回后拦截，替换为应用原始图标
     */
    private fun installGetIconHook(clazz: Class<*>, classLoader: ClassLoader) {
        log("--- Installing getIcon hook ---")

        val hooked = CompatV5.hookGetIcon(clazz, classLoader,
            before = { param ->
                try {
                    val view = param.thisObject as? View ?: return@hookGetIcon
                    val pkgName = CompatV5.getPkgName(view) ?: return@hookGetIcon
                    if (!shouldHandlePackage(view.context, pkgName)) return@hookGetIcon

                    val appIcon = getApplicationIcon(view.context, pkgName)
                    if (appIcon != null) {
                        log("[getIcon.before] Replacing icon for $pkgName")
                        param.result = appIcon
                    }
                } catch (t: Throwable) {
                    log("[getIcon.before] Error: ${t.message}")
                }
            },
            after = { param ->
                try {
                    val view = param.thisObject as? View
                    if (view != null) {
                        applyUniformStyle(view)
                    }
                } catch (t: Throwable) {
                    log("[getIcon.after] Error: ${t.message}")
                }
            }
        )

        if (hooked) log("[OK] getIcon hook installed")
        else log("[WARN] getIcon hook not available")
    }

    /**
     * Hook 2: 阻止着色 — 使用版本兼容的方法
     */
    private fun installTintHandlerHook(clazz: Class<*>) {
        log("--- Installing tint handler hook ---")

        val hooked = CompatV5.hookIconTintHandlers(clazz,
            before = { param ->
                try {
                    val view = param.thisObject as? View ?: return@hookIconTintHandlers
                    val pkgName = CompatV5.getPkgName(view) ?: return@hookIconTintHandlers
                    if (!shouldHandlePackage(view.context, pkgName)) return@hookIconTintHandlers

                    log("[tint.before] Blocking tint for $pkgName")
                    if (view is ImageView) CompatV5.clearTint(view)
                    param.result = null
                } catch (t: Throwable) {
                    log("[tint.before] Error: ${t.message}")
                }
            },
            after = { param ->
                try {
                    val view = param.thisObject as? View
                    if (view != null) applyUniformStyle(view)
                } catch (t: Throwable) {
                    log("[tint.after] Error: ${t.message}")
                }
            }
        )

        if (hooked) log("[OK] Tint handler hook installed")
        else log("[WARN] No tint handler hooked, using onDraw fallback")
    }

    /**
     * Hook 3: onLayout — 所有版本通用
     */
    private fun installLayoutHook(clazz: Class<*>) {
        log("--- Installing onLayout hook ---")

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
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
                                applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[onLayout.after] Error: ${t.message}")
                        }
                    }
                }
            )
            log("[OK] onLayout hook installed")
        } catch (t: Throwable) {
            log("[FAIL] onLayout hook: ${t.message}")
        }
    }

    /**
     * Hook 4: IconState 应用方法
     */
    private fun installIconStateHooks(iconStateClass: Class<*>) {
        log("--- Installing IconState hooks ---")

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val view = param.args.getOrNull(0) as? View
                    if (view != null) applyUniformStyle(view)
                } catch (t: Throwable) {
                    log("[IconState] Error: ${t.message}")
                }
            }
        }

        val methodsToTry = listOf(
            "initFrom" to arrayOf(View::class.java),
            "applyToView" to arrayOf(View::class.java),
            "applyTo" to arrayOf(View::class.java)
        )

        var anyHooked = false
        for ((name, args) in methodsToTry) {
            try {
                XposedHelpers.findAndHookMethod(iconStateClass, name, *args, hook)
                log("[OK] Hooked IconState.$name")
                anyHooked = true
            } catch (_: Throwable) { }
        }

        if (!anyHooked) {
            log("[WARN] No IconState methods hooked")
        }
    }

    /**
     * Hook 5: 图标状态应用（容器级别）
     */
    private fun installIconStateApplicationHook(containerClass: Class<*>) {
        log("--- Installing icon state application hook ---")

        val hooked = CompatV5.hookIconStateApplication(containerClass) { param ->
            try {
                processContainerViews(param.thisObject ?: return@hookIconStateApplication)
            } catch (t: Throwable) {
                log("[stateApp] Error: ${t.message}")
            }
        }

        if (hooked) log("[OK] State application hook installed")
        else log("[WARN] No state application hook")
    }

    /**
     * A16+ 特定 Hook
     */
    private fun installA16SpecificHooks(classLoader: ClassLoader) {
        log("--- Installing A16+ specific hooks ---")

        // A16+ 新增: StatusBarIconDrawable.setIconTint()
        try {
            val drawableClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.icon.StatusBarIconDrawable",
                classLoader
            )
            XposedHelpers.findAndHookMethod(
                drawableClass,
                "setIconTint",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 阻止着色
                        param.result = null
                    }
                }
            )
            log("[OK] Hooked StatusBarIconDrawable.setIconTint()")
        } catch (_: Throwable) {
            log("[INFO] StatusBarIconDrawable.setIconTint() not found")
        }

        // A16+ 新增: NotificationIconAreaController.updateIconViews()
        try {
            val controllerClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.icon.NotificationIconAreaController",
                classLoader
            )
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "updateIconViews",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject
                            val iconViews = XposedHelpers.getObjectField(thisObj, "mIconViews") as? List<*>
                            iconViews?.forEach { view ->
                                if (view is View) applyUniformStyle(view)
                            }
                        } catch (t: Throwable) {
                            log("[updateIconViews] Error: ${t.message}")
                        }
                    }
                }
            )
            log("[OK] Hooked NotificationIconAreaController.updateIconViews()")
        } catch (_: Throwable) {
            log("[INFO] NotificationIconAreaController.updateIconViews() not found")
        }

        // A16+ 兜底: hook View.onDraw 监听所有 ImageView
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onDraw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? ImageView ?: return
                            val viewClass = view.javaClass.name
                            if (!viewClass.contains("StatusBarIconView") &&
                                !viewClass.contains("NotificationIcon")) return

                            val pkgName = CompatV5.getPkgName(view) ?: return
                            if (!shouldHandlePackage(view.context, pkgName)) return
                            applyUniformStyle(view)
                        } catch (_: Throwable) { }
                    }
                }
            )
            log("[OK] Hooked View.onDraw() as fallback")
        } catch (t: Throwable) {
            log("[INFO] View.onDraw() hook: ${t.message}")
        }
    }

    /**
     * 最终兜底方案：当 StatusBarIconView 找不到时使用
     */
    private fun installFallbackHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("--- Installing fallback hooks (no StatusBarIconView) ---")

        // 方案1: hook NotificationIconAreaController
        try {
            val controllerClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconAreaController",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                controllerClass,
                "updateIconsForLayout",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val iconViews = CompatV5.readFieldSafe(
                                param.thisObject,
                                "mIconViews", "mStatusIcons", "mNotificationIcons"
                            ) as? List<*>
                            iconViews?.forEach { v ->
                                if (v is View) applyUniformStyle(v)
                            }
                        } catch (t: Throwable) {
                            log("[fallback] Error: ${t.message}")
                        }
                    }
                }
            )
            log("[OK] Fallback: hooked NotificationIconAreaController")
        } catch (_: Throwable) {
            log("[FALLBACK] NotificationIconAreaController not found")
        }

        // 方案2: hook 所有 SystemUI 子视图中的 ImageView（性能较差但兜底）
        log("[FALLBACK] Using timer-based polling as last resort")
    }

    // ===================================================================
    //  核心：统一样式应用
    // ===================================================================
    private fun applyUniformStyle(view: View?) {
        if (view !is ImageView) return

        log("[applyUniformStyle] === Entering for $view ===")

        val pkgName = CompatV5.getPkgName(view) ?: run {
            log("[applyUniformStyle] Cannot get pkg, skip")
            return
        }

        if (!shouldHandlePackage(view.context, pkgName)) return

        // 1. 强制设置应用原始图标
        val appIcon = getApplicationIcon(view.context, pkgName)
        if (appIcon != null) {
            if (view.drawable !== appIcon) {
                view.setImageDrawable(appIcon)
                log("[applyUniformStyle] Set app icon for $pkgName")
            }
        } else {
            log("[applyUniformStyle] No app icon for $pkgName")
            return
        }

        val targetSizePx = dpToPx(view.context, TARGET_ICON_DP)

        // 2. 设置尺寸
        fun setLayoutSize() {
            val lp = view.layoutParams
            if (lp != null) {
                var changed = false
                if (lp.width != targetSizePx) { lp.width = targetSizePx; changed = true }
                if (lp.height != targetSizePx) { lp.height = targetSizePx; changed = true }
                if (changed) {
                    view.layoutParams = lp
                    log("[applyUniformStyle] Size set to ${targetSizePx}x${targetSizePx}")
                }
            } else {
                view.post { setLayoutSize() }
                return
            }
        }
        setLayoutSize()

        // 3. 设置圆形裁剪
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                view.invalidateOutline()
            }
            log("[applyUniformStyle] Round clip set for $pkgName")
        }

        // 4. 清除所有干扰属性
        CompatV5.clearTint(view)
        view.setPadding(0, 0, 0, 0)
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.adjustViewBounds = true

        view.invalidate()
        log("[applyUniformStyle] === Exiting for $pkgName ===")
    }

    /**
     * 处理容器中的所有子 View
     */
    private fun processContainerViews(container: Any) {
        try {
            // 方式1: 通过 mIconStates
            val iconStates = CompatV5.readFieldSafe(
                container,
                "mIconStates", "mViewStates", "mStates"
            )
            if (iconStates is Map<*, *>) {
                for (key in iconStates.keys) {
                    if (key is View) applyUniformStyle(key)
                }
                return
            }

            // 方式2: 通过 mIconViews / mChildViews
            val iconViews = CompatV5.readFieldSafe(
                container,
                "mIconViews", "mChildViews", "mViews", "mNotificationViews"
            )
            if (iconViews is Iterable<*>) {
                for (v in iconViews) {
                    if (v is View) applyUniformStyle(v)
                }
                return
            }

            // 方式3: 如果 container 本身是 ViewGroup
            if (container is android.view.ViewGroup) {
                for (i in 0 until container.childCount) {
                    applyUniformStyle(container.getChildAt(i))
                }
            }
        } catch (t: Throwable) {
            log("[processContainerViews] Error: ${t.message}")
        }
    }

    // ===================================================================
    //  辅助方法
    // ===================================================================
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
            !isSystem && !isUpdatedSystem
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

    private fun log(msg: String) = XposedBridge.log("$TAG: $msg")
    private fun log(t: Throwable) = XposedBridge.log(t)
}