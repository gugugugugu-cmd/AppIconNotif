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
 * Android 16 适配 — StatusBar 通知图标 Hook
 * FIX: 移除所有复杂 lambda 传参，改用纯匿名内部类避免类型推断失败
 * FIX: 修复函数体损坏问题
 */
object StatusbarAppIconHook {

    private const val TAG = "AppIconNotif"
    private const val SYSTEMUI = "com.android.systemui"
    private const val TARGET_ICON_DP = 20f

    // ===================================================================
    //  类/方法/字段 兼容查找
    // ===================================================================
    private object Finder {

        fun log(msg: String) = XposedBridge.log("$TAG: $msg")

        // ---------- 类查找 ----------

        fun statusBarIconView(cl: ClassLoader): Class<*>? {
            for (p in listOf(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                "$SYSTEMUI.statusbar.views.StatusBarIconView",
                "$SYSTEMUI.statusbar.notification.icon.StatusBarIconView"
            )) {
                try { return XposedHelpers.findClass(p, cl).also { log("[OK] SBIView: $p") } }
                catch (_: Throwable) { }
            }
            log("[FAIL] StatusBarIconView not found")
            return null
        }

        fun notificationContainer(cl: ClassLoader): Class<*>? {
            for (p in listOf(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                "$SYSTEMUI.statusbar.notification.icon.NotificationIconContainer",
                "$SYSTEMUI.statusbar.notification.NotificationShelf",
                "$SYSTEMUI.statusbar.phone.NotificationIconAreaController"
            )) {
                try { return XposedHelpers.findClass(p, cl).also { log("[OK] Container: $p") } }
                catch (_: Throwable) { }
            }
            return null
        }

        fun iconState(cl: ClassLoader, container: Class<*>?): Class<*>? {
            val list = mutableListOf<String>()
            if (container != null) {
                list.add("${container.name}\$IconState")
                list.add("${container.name}\$IconConfig")
            }
            list.add("$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState")
            list.add("$SYSTEMUI.statusbar.notification.icon.IconState")
            for (p in list) {
                try { return XposedHelpers.findClass(p, cl).also { log("[OK] IconState: $p") } }
                catch (_: Throwable) { }
            }
            return null
        }

        // ---------- 字段访问 ----------

        fun pkgName(view: View): String? {
            // 通过 mIcon → pkg
            val icon = field(view, "mIcon", "mStatusBarIcon", "mNotificationIcon") ?: return null
            val pkg = field(icon, "pkg", "packageName", "mPkg")
            return pkg as? String
        }

        fun field(obj: Any, vararg names: String): Any? {
            for (n in names) {
                try { return XposedHelpers.getObjectField(obj, n) } catch (_: Throwable) { }
            }
            return null
        }

        fun clearTint(iv: ImageView) {
            try {
                iv.imageTintList = null
                iv.clearColorFilter()
                @Suppress("DEPRECATION")
                iv.setColorFilter(null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) iv.imageTintBlendMode = null
                for (f in listOf("mCurrentSetColor", "mDrawableColor", "mTintColor", "mIconColor")) {
                    try { XposedHelpers.setIntField(iv, f, 0) } catch (_: Throwable) { }
                }
                iv.drawable?.let { d ->
                    try { d.setTintList(null); d.setTint(0) } catch (_: Throwable) { }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try { d.setTintBlendMode(null) } catch (_: Throwable) { }
                    }
                }
            } catch (t: Throwable) { log("[clearTint] ${t.message}") }
        }

        // ---------- 简化版 Hook 方法：纯匿名内部类，无 lambda 传参 ----------

        /**
         * hook getIcon(StatusBarIcon) — 用匿名内部类替代 lambda
         */
        fun hookGetIcon(clazz: Class<*>, cl: ClassLoader): Boolean {
            // 尝试各种签名
            val iconTypes = listOf(
                "com.android.internal.statusbar.StatusBarIcon",
                "$SYSTEMUI.statusbar.StatusBarIcon"
            )
            for (it in iconTypes) {
                try {
                    val iconCls = XposedHelpers.findClass(it, cl)
                    XposedHelpers.findAndHookMethod(clazz, "getIcon", iconCls, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            val pkg = pkgName(view) ?: return
                            if (!shouldHandle(view.context, pkg)) return
                            val icon = appIcon(view.context, pkg) ?: return
                            log("[getIcon] Replace $pkg")
                            param.result = icon
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.thisObject as? View ?: return
                            applyStyle(v)
                        }
                    })
                    log("[OK] hookGetIcon($it)")
                    return true
                } catch (_: Throwable) { }
            }
            // 兜底
            try {
                XposedHelpers.findAndHookMethod(clazz, "getStatusBarIconDrawable", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val pkg = pkgName(view) ?: return
                        if (!shouldHandle(view.context, pkg)) return
                        val icon = appIcon(view.context, pkg) ?: return
                        param.result = icon
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        applyStyle(v)
                    }
                })
                log("[OK] hookGetIcon(getStatusBarIconDrawable)")
                return true
            } catch (_: Throwable) { }
            log("[FAIL] hookGetIcon failed")
            return false
        }

        /**
         * hook updateIconColor — 纯匿名内部类
         */
        fun hookTint(clazz: Class<*>): Boolean {
            val methods = listOf(
                "updateIconColor" to arrayOf(Int::class.javaPrimitiveType),
                "updateIconColor" to emptyArray(),
                "setColorWithDebug" to arrayOf(Int::class.javaPrimitiveType, String::class.java),
                "onTintChanged" to emptyArray()
            )
            for ((name, args) in methods) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, name, *args, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            val pkg = pkgName(view) ?: return
                            if (!shouldHandle(view.context, pkg)) return
                            log("[tint] Block $pkg")
                            if (view is ImageView) clearTint(view)
                            param.result = null
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val v = param.thisObject as? View ?: return
                            applyStyle(v)
                        }
                    })
                    log("[OK] hookTint($name)")
                    return true
                } catch (_: Throwable) { }
            }
            log("[FAIL] hookTint failed")
            return false
        }

        /**
         * hook 容器类的状态应用方法 — 纯匿名内部类
         */
        fun hookStateApply(containerCls: Class<*>): Boolean {
            val methods = listOf(
                "applyIconStates" to emptyArray(),
                "updateIconsForLayout" to emptyArray(),
                "applyNotificationIcons" to emptyArray(),
                "updateIconViews" to emptyArray()
            )
            for ((name, args) in methods) {
                try {
                    XposedHelpers.findAndHookMethod(containerCls, name, *args, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            processContainer(param.thisObject)
                        }
                    })
                    log("[OK] hookStateApply($name)")
                    return true
                } catch (_: Throwable) { }
            }
            log("[FAIL] hookStateApply failed")
            return false
        }
    }

    // ===================================================================
    //  入口
    // ===================================================================
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        Finder.log("=== StatusbarAppIconHook (A16) ===")

        val cl = lpparam.classLoader
        val sbiCls = Finder.statusBarIconView(cl) ?: run {
            Finder.log("[FATAL] No StatusBarIconView, fallback")
            installFallback(lpparam)
            return
        }

        val containerCls = Finder.notificationContainer(cl)
        val stateCls = Finder.iconState(cl, containerCls)

        Finder.hookGetIcon(sbiCls, cl)
        Finder.hookTint(sbiCls)
        hookLayout(sbiCls)

        if (containerCls != null) Finder.hookStateApply(containerCls)
        else Finder.log("[INFO] No container class")

        if (stateCls != null) hookIconState(stateCls)

        installA16Hooks(cl)
        Finder.log("=== Done ===")
    }

    // ===================================================================
    //  简单 Hook：onLayout（直接匿名内部类）
    // ===================================================================
    private fun hookLayout(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.width > 0 && v.height > 0) applyStyle(v)
                    }
                }
            )
            Finder.log("[OK] hookLayout")
        } catch (t: Throwable) {
            Finder.log("[FAIL] hookLayout: ${t.message}")
        }
    }

    // ===================================================================
    //  简单 Hook：IconState（直接匿名内部类）
    // ===================================================================
    private fun hookIconState(stateCls: Class<*>) {
        for ((name, args) in listOf(
            "initFrom" to arrayOf(View::class.java),
            "applyToView" to arrayOf(View::class.java)
        )) {
            try {
                XposedHelpers.findAndHookMethod(stateCls, name, *args, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.args.getOrNull(0) as? View ?: return
                        applyStyle(v)
                    }
                })
                Finder.log("[OK] hookIconState($name)")
            } catch (_: Throwable) { }
        }
    }

    // ===================================================================
    //  A16+ 特定 Hook（直接匿名内部类）
    // ===================================================================
    private fun installA16Hooks(cl: ClassLoader) {
        Finder.log("--- A16+ hooks ---")

        // 1. StatusBarIconDrawable.setIconTint
        try {
            val drawCls = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.icon.StatusBarIconDrawable", cl
            )
            XposedHelpers.findAndHookMethod(drawCls, "setIconTint",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                }
            )
            Finder.log("[OK] setIconTint blocked")
        } catch (_: Throwable) {
            Finder.log("[INFO] setIconTint not found")
        }

        // 2. NotificationIconAreaController.updateIconViews
        try {
            val ctrlCls = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.icon.NotificationIconAreaController", cl
            )
            XposedHelpers.findAndHookMethod(ctrlCls, "updateIconViews",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val views = Finder.field(param.thisObject,
                            "mIconViews", "mStatusIcons", "mNotificationIcons"
                        ) as? List<*>
                        views?.forEach { v -> if (v is View) applyStyle(v) }
                    }
                }
            )
            Finder.log("[OK] updateIconViews hooked")
        } catch (_: Throwable) {
            Finder.log("[INFO] updateIconViews not found")
        }

        // 3. 兜底: View.onDraw
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onDraw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? ImageView ?: return
                        val cn = v.javaClass.name
                        if (!cn.contains("StatusBarIcon") && !cn.contains("NotificationIcon")) return
                        val pkg = Finder.pkgName(v) ?: return
                        if (!shouldHandle(v.context, pkg)) return
                        applyStyle(v)
                    }
                }
            )
            Finder.log("[OK] View.onDraw fallback")
        } catch (t: Throwable) {
            Finder.log("[INFO] onDraw fallback: ${t.message}")
        }
    }

    // ===================================================================
    //  兜底方案
    // ===================================================================
    private fun installFallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val ctrlCls = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconAreaController",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(ctrlCls, "updateIconsForLayout",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val views = Finder.field(param.thisObject,
                            "mIconViews", "mStatusIcons", "mNotificationIcons"
                        ) as? List<*>
                        views?.forEach { v -> if (v is View) applyStyle(v) }
                    }
                }
            )
            Finder.log("[OK] Fallback hooked")
        } catch (_: Throwable) {
            Finder.log("[FAIL] Fallback not available")
        }
    }

    // ===================================================================
    //  处理容器
    // ===================================================================
    private fun processContainer(container: Any) {
        try {
            val states = Finder.field(container, "mIconStates", "mViewStates", "mStates")
            if (states is Map<*, *>) {
                for (k in states.keys) { if (k is View) applyStyle(k) }
                return
            }
            val views = Finder.field(container,
                "mIconViews", "mChildViews", "mViews", "mNotificationViews"
            )
            if (views is Iterable<*>) {
                for (v in views) { if (v is View) applyStyle(v) }
                return
            }
            if (container is android.view.ViewGroup) {
                for (i in 0 until container.childCount) applyStyle(container.getChildAt(i))
            }
        } catch (t: Throwable) {
            Finder.log("[processContainer] ${t.message}")
        }
    }

    // ===================================================================
    //  核心样式应用
    // ===================================================================
    private fun applyStyle(view: View?) {
        if (view !is ImageView) return

        val pkg = Finder.pkgName(view) ?: return
        if (!shouldHandle(view.context, pkg)) return

        val icon = appIcon(view.context, pkg) ?: return
        if (view.drawable !== icon) {
            view.setImageDrawable(icon)
            Finder.log("[style] Set icon $pkg")
        }

        val sizePx = dpToPx(view.context, TARGET_ICON_DP)

        // 尺寸
        val lp = view.layoutParams
        if (lp != null) {
            var changed = false
            if (lp.width != sizePx) { lp.width = sizePx; changed = true }
            if (lp.height != sizePx) { lp.height = sizePx; changed = true }
            if (changed) view.layoutParams = lp
        } else {
            view.post { applyStyle(view) }
            return
        }

        // 圆形裁剪
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    val w = v.width; val h = v.height
                    if (w > 0 && h > 0) outline.setRoundRect(0, 0, w, h, minOf(w, h) / 2f)
                    else outline.setEmpty()
                }
            }
            view.clipToOutline = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) view.invalidateOutline()
        }

        Finder.clearTint(view)
        view.setPadding(0, 0, 0, 0)
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.adjustViewBounds = true
        view.invalidate()
    }

    // ===================================================================
    //  工具方法
    // ===================================================================
    private fun appIcon(ctx: Context, pkg: String): Drawable? {
        return try { ctx.packageManager.getApplicationIcon(pkg) }
        catch (t: Throwable) { Finder.log("[appIcon] $pkg: ${t.message}"); null }
    }

    private fun shouldHandle(ctx: Context, pkg: String): Boolean {
        if (pkg == "android" || pkg == SYSTEMUI) return false
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            val sys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val upd = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            !sys && !upd
        } catch (t: Throwable) { false }
    }

    private fun dpToPx(ctx: Context, dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.resources.displayMetrics).toInt()
}