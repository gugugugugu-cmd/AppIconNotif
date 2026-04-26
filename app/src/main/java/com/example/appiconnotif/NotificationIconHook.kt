package com.example.appiconnotif

import android.app.Notification
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

        log("Loaded into: ${lpparam.packageName}")

        try {
            hookNotificationHeaderViewWrapper(lpparam)
        } catch (t: Throwable) {
            log("hookNotificationHeaderViewWrapper failed")
            log(t)
        }

        try {
            hookNotificationRowIconView(lpparam)
        } catch (t: Throwable) {
            log("hookNotificationRowIconView failed")
            log(t)
        }

        try {
            StatusbarAppIconHook.hook(lpparam)
        } catch (t: Throwable) {
            log("StatusbarAppIconHook failed")
            log(t)
        }
    }

    private fun hookNotificationHeaderViewWrapper(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wrapperClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                lpparam.classLoader
            )

            var hooked = false
            wrapperClass.declaredMethods.forEach { method ->
                if (method.name != "onContentUpdated") return@forEach

                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val row = param.args.firstOrNull() ?: return

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

                            val mIcon = try {
                                XposedHelpers.getObjectField(param.thisObject, "mIcon") as? ImageView
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            val context = mIcon.context
                            val appIcon = try {
                                context.packageManager.getApplicationIcon(pkgName)
                            } catch (_: Throwable) {
                                log("Failed to get app icon for $pkgName")
                                return
                            }

                            val imageIconTagId = context.resources.getIdentifier(
                                "image_icon_tag",
                                "id",
                                SYSTEMUI
                            )

                            applyOriginalAppIcon(mIcon, appIcon)

                            if (imageIconTagId != 0) {
                                mIcon.setTag(imageIconTagId, notification.smallIcon)
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
                })

                hooked = true
                log("Hooked NotificationHeaderViewWrapper.$method")
            }

            if (!hooked) {
                log("No onContentUpdated method found in NotificationHeaderViewWrapper")
            }
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
                                clearIconStyling(iconView)
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
                    "setImageIcon",
                    Icon::class.java,
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
                    Icon::class.java,
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

    private fun applyOriginalAppIcon(
        imageView: ImageView,
        drawable: android.graphics.drawable.Drawable
    ) {
        try {
            clearIconStyling(imageView)

            try {
                imageView.setImageIcon(null)
            } catch (_: Throwable) {
            }

            imageView.setImageDrawable(drawable)
            imageView.invalidate()
        } catch (t: Throwable) {
            log(t)
        }
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
            imageView.setLayerType(ImageView.LAYER_TYPE_NONE, null)
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
}