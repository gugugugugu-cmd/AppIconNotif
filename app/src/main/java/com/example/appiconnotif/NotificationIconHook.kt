package com.example.appiconnotif

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        private fun toMonochromeBitmap(bitmap: Bitmap): Bitmap {
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
            val canvas = Canvas(result)
            val paint = android.graphics.Paint()
            val cm = ColorMatrix()
            cm.setSaturation(0f)
            val filter = ColorMatrixColorFilter(cm)
            paint.colorFilter = filter
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            return result
        }

        private fun toMonochromeDrawable(drawable: Drawable, resources: Resources): Drawable {
            val bitmap = drawableToBitmap(drawable)
            val monoBitmap = toMonochromeBitmap(bitmap)
            return BitmapDrawable(resources, monoBitmap)
        }

        private fun shouldConvertToMonochrome(): Boolean {
            // 可改为从配置读取，默认 true（与 SystemUIHooker 中 AUTO_FIX 行为一致）
            return true
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEMUI) return

        hookNotificationHeaderViewWrapper(lpparam)
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

    private fun applyOriginalAppIcon(
        imageView: ImageView,
        drawable: Drawable
    ) {
        clearIconStyling(imageView)

        try {
            imageView.setImageIcon(null)
        } catch (_: Throwable) {
        }

        val finalDrawable = if (shouldConvertToMonochrome()) {
            toMonochromeDrawable(drawable, imageView.resources)
        } else {
            drawable
        }

        imageView.setImageDrawable(finalDrawable)
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