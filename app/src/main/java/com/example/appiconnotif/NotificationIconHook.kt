package com.example.appiconnotif

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.os.Build
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

        hookNotificationHeaderViewWrapper(lpparam)
        hookNotificationRowIconView(lpparam)
        StatusbarAppIconHook.hook(lpparam)
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

                            val notification =
                                XposedHelpers.callMethod(sbn, "getNotification") as Notification
                            val pkgName =
                                XposedHelpers.callMethod(sbn, "getPackageName") as? String ?: return

                            val iconView = try {
                                XposedHelpers.getObjectField(param.thisObject, "mIcon") as ImageView
                            } catch (_: Throwable) {
                                return
                            }

                            if (!isThirdPartyApp(iconView.context, pkgName)) return

                            val appIcon = try {
                                iconView.context.packageManager.getApplicationIcon(pkgName)
                            } catch (_: Throwable) {
                                return
                            }

                            val imageIconTagId = iconView.context.resources.getIdentifier(
                                "image_icon_tag",
                                "id",
                                SYSTEMUI
                            )

                            applyOriginalAppIcon(iconView, appIcon)

                            if (imageIconTagId != 0) {
                                iconView.setTag(imageIconTagId, notification.smallIcon)
                            }

                            try {
                                val workProfileImage =
                                    XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "mWorkProfileImage"
                                    ) as? ImageView

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
                    val iconView = param.thisObject as? ImageView ?: return
                    iconView.post {
                        try {
                            clearIconStyling(iconView)
                        } catch (t: Throwable) {
                            log(t)
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                rowIconClass,
                "setImageIcon",
                Icon::class.java,
                cleanStyleHook
            )

            XposedHelpers.findAndHookMethod(
                rowIconClass,
                "setImageIconAsync",
                Icon::class.java,
                cleanStyleHook
            )
        } catch (t: Throwable) {
            log("Failed to hook NotificationRowIconView")
            log(t)
        }
    }

    private fun applyOriginalAppIcon(
        imageView: ImageView,
        drawable: android.graphics.drawable.Drawable
    ) {
        clearIconStyling(imageView)

        try {
            imageView.setImageIcon(null)
        } catch (_: Throwable) {
        }

        imageView.setImageDrawable(drawable)
        imageView.invalidate()
    }

    private fun clearIconStyling(imageView: ImageView) {
        imageView.setPadding(0, 0, 0, 0)
        imageView.background = ColorDrawable(Color.TRANSPARENT)

        try {
            imageView.imageTintList = null
        } catch (_: Throwable) {
        }

        try {
            imageView.backgroundTintList = null
        } catch (_: Throwable) {
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                imageView.imageTintMode = null
                imageView.backgroundTintMode = null
            }
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
            XposedHelpers.setObjectField(imageView, "mApplyCircularCrop", false)
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.callMethod(imageView, "setApplyCircularCrop", false)
        } catch (_: Throwable) {
        }
    }

    private fun isThirdPartyApp(context: Context, pkgName: String): Boolean {
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
}