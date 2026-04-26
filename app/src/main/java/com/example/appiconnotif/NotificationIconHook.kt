package com.example.appiconnotif

import android.app.Notification
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NotificationIconHook : IXposedHookLoadPackage {

    companion object {
        private const val SYSTEMUI = "com.android.systemui"

        private fun log(msg: String) {
            XposedBridge.log("AppIconNotif: $msg")
        }

        private fun log(t: Throwable) {
            XposedBridge.log(t)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI) return

        log("Loaded into: ${lpparam.packageName}")

        hookNotificationHeaderViewWrapper(lpparam)
        hookNotificationRowIconView(lpparam)
    }

    private fun hookNotificationHeaderViewWrapper(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wrapperClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                lpparam.classLoader
            )

            val rowClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                wrapperClass,
                "onContentUpdated",
                rowClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.args[0]

                            val entry = try {
                                XposedHelpers.callMethod(row, "getEntry")
                            } catch (_: Throwable) {
                                try {
                                    XposedHelpers.getObjectField(row, "mEntry")
                                } catch (_: Throwable) {
                                    XposedHelpers.getObjectField(row, "mEntryAdapter")
                                }
                            }

                            val sbn = try {
                                XposedHelpers.callMethod(entry, "getSbn")
                            } catch (_: Throwable) {
                                XposedHelpers.getObjectField(entry, "mSbn")
                            }

                            val notification = XposedHelpers.callMethod(sbn, "getNotification") as Notification
                            val pkgName = XposedHelpers.callMethod(sbn, "getPackageName") as? String
                                ?: return

                            val iconDrawable = try {
                                val context = (param.thisObject as android.view.View).context
                                context.packageManager.getApplicationIcon(pkgName)
                            } catch (_: Throwable) {
                                log("Failed to get app icon for $pkgName")
                                return
                            }

                            val mIcon = try {
                                XposedHelpers.getObjectField(param.thisObject, "mIcon") as ImageView
                            } catch (_: Throwable) {
                                return
                            }

                            val context = mIcon.context
                            val imageIconTagId = context.resources.getIdentifier(
                                "image_icon_tag",
                                "id",
                                SYSTEMUI
                            )

                            mIcon.setImageDrawable(iconDrawable)

                            if (imageIconTagId != 0) {
                                mIcon.setTag(imageIconTagId, notification.smallIcon)
                            }

                            try {
                                val workProfileImage =
                                    XposedHelpers.getObjectField(param.thisObject, "mWorkProfileImage") as? ImageView
                                if (workProfileImage != null) {
                                    workProfileImage.setImageIcon(notification.smallIcon)
                                    if (imageIconTagId != 0) {
                                        workProfileImage.setTag(imageIconTagId, notification.smallIcon)
                                    }
                                }
                            } catch (_: Throwable) {
                            }

                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            )

            log("Hooked NotificationHeaderViewWrapper.onContentUpdated")
        } catch (t: Throwable) {
            log("Failed to hook NotificationHeaderViewWrapper")
            log(t)
        }
    }

    private fun hookNotificationRowIconView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rowIconClass = XposedHelpers.findClass(
                "com.android.internal.widget.NotificationRowIconView",
                lpparam.classLoader
            )

            val cleanStyleHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val iconView = param.thisObject as? ImageView ?: return
                        iconView.post {
                            try {
                                iconView.setPadding(0, 0, 0, 0)
                                iconView.background = ColorDrawable(Color.TRANSPARENT)
                            } catch (t: Throwable) {
                                log(t)
                            }
                        }
                    } catch (t: Throwable) {
                        log(t)
                    }
                }
            }

            try {
                XposedHelpers.findAndHookMethod(
                    rowIconClass,
                    "onFinishInflate",
                    cleanStyleHook
                )
                log("Hooked NotificationRowIconView.onFinishInflate")
            } catch (t: Throwable) {
                log("Failed to hook onFinishInflate")
                log(t)
            }

            try {
                XposedHelpers.findAndHookMethod(
                    rowIconClass,
                    "setImageIcon",
                    android.graphics.drawable.Icon::class.java,
                    cleanStyleHook
                )
                log("Hooked NotificationRowIconView.setImageIcon")
            } catch (t: Throwable) {
                log("Failed to hook setImageIcon")
                log(t)
            }

            try {
                XposedHelpers.findAndHookMethod(
                    rowIconClass,
                    "setImageIconAsync",
                    android.graphics.drawable.Icon::class.java,
                    cleanStyleHook
                )
                log("Hooked NotificationRowIconView.setImageIconAsync")
            } catch (t: Throwable) {
                log("Failed to hook setImageIconAsync")
                log(t)
            }

        } catch (t: Throwable) {
            log("Failed to hook NotificationRowIconView")
            log(t)
        }
    }
}