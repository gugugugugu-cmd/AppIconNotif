package com.example.appiconnotif

import android.app.Notification
import android.widget.ImageView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        hookNotificationHeaderViewWrapper(lpparam)
        StatusbarAppIconHook.hook(lpparam)
    }

    private fun hookNotificationHeaderViewWrapper(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wrapperClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                lpparam.classLoader
            )
            val rowClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                wrapperClass,
                "onContentUpdated",
                rowClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.args[0] ?: return

                            // 兼容性获取 Entry
                            val entry = runCatching { XposedHelpers.callMethod(row, "getEntry") }
                                .recoverCatching { XposedHelpers.getObjectField(row, "mEntry") }
                                .getOrNull() ?: return

                            // 兼容性获取 StatusBarNotification (sbn)
                            val sbn = runCatching { XposedHelpers.callMethod(entry, "getSbn") }
                                .recoverCatching { XposedHelpers.getObjectField(entry, "mSbn") }
                                .getOrNull() ?: return

                            val pkg = XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return
                            val iconView = XposedHelpers.getObjectField(param.thisObject, "mIcon") as? ImageView ?: return
                            
                            // 使用统一的公共工具方法
                            if (!XposedUtils.isThirdPartyApp(iconView.context, pkg)) return

                            // 走缓存获取，显著提升滑动通知栏时的流畅度
                            val icon = XposedUtils.getCachedAppIcon(iconView.context, pkg) ?: return
                            iconView.setImageDrawable(icon)

                            // 恢复/备份原有的 smallIcon 标志，防范 SystemUI 内部逻辑错乱
                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as? Notification ?: return
                            val tagId = iconView.context.resources.getIdentifier("image_icon_tag", "id", "com.android.systemui")
                            if (tagId != 0) {
                                iconView.setTag(tagId, notification.smallIcon)
                            }
                        } catch (_: Throwable) {
                            // 保持静默，防止 SystemUI 崩溃
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }
}
