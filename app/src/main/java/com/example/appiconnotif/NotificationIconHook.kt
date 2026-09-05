package com.example.appiconnotif

import android.app.Notification
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

object NotificationIconHook {

    private const val SYSTEMUI = "com.android.systemui"

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        hookNotificationHeaderViewWrapper(module, classLoader)
        StatusbarAppIconHook.hook(module, classLoader)
    }

    /**
     * API 102 说明：XC_MethodHook.afterHookedMethod 的等价写法是
     * 在 interceptor 中先 chain.proceed() 再做后处理。
     */
    private fun hookNotificationHeaderViewWrapper(module: XposedModule, classLoader: ClassLoader) {
        try {
            val wrapperClass = XposedCompat.findClass(
                "$SYSTEMUI.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                classLoader
            )

            val rowClass = try {
                XposedCompat.findClass(
                    "$SYSTEMUI.statusbar.notification.row.ExpandableNotificationRow",
                    classLoader
                )
            } catch (_: Throwable) {
                null
            }

            // rowClass 为 null 时按“任意单参数方法”匹配
            val method = XposedCompat.findMethodBestMatch(
                wrapperClass, "onContentUpdated", rowClass
            )

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    chain.proceed()
                    onContentUpdatedAfter(chain.thisObject, chain.getArg(0))
                    // void 方法显式返回 null
                    null
                }
        } catch (t: Throwable) {
            XposedEntry.log("Failed to hook NotificationHeaderViewWrapper", t)
        }
    }

    private fun onContentUpdatedAfter(wrapper: Any?, row: Any?) {
        try {
            if (wrapper == null || row == null) return

            val entry = try {
                XposedCompat.callMethod(row, "getEntry")
            } catch (_: Throwable) {
                try {
                    XposedCompat.getObjectField(row, "mEntry")
                } catch (_: Throwable) {
                    XposedCompat.getObjectField(row, "mEntryAdapter")
                }
            }

            val sbn = try {
                XposedCompat.callMethod(entry, "getSbn")
            } catch (_: Throwable) {
                XposedCompat.getObjectField(entry, "mSbn")
            } ?: return

            val notification = XposedCompat.callMethod(sbn, "getNotification") as Notification
            val pkgName = XposedCompat.callMethod(sbn, "getPackageName") as? String ?: return

            val iconView = try {
                XposedCompat.getObjectField(wrapper, "mIcon") as? ImageView
            } catch (_: Throwable) {
                null
            } ?: return

            if (!IconManager.shouldReplaceApp(iconView.context, pkgName)) return

            val appIcon = IconManager.getCachedAppIcon(iconView.context, pkgName) ?: return

            val imageIconTagId = iconView.context.resources.getIdentifier(
                "image_icon_tag", "id", SYSTEMUI
            )

            applyOriginalAppIcon(iconView, appIcon)

            if (imageIconTagId != 0) {
                iconView.setTag(imageIconTagId, notification.smallIcon)
            }

            try {
                val workProfileImage = XposedCompat.getObjectField(wrapper, "mWorkProfileImage") as? ImageView
                if (workProfileImage != null) {
                    workProfileImage.setImageIcon(notification.smallIcon)
                    if (imageIconTagId != 0) {
                        workProfileImage.setTag(imageIconTagId, notification.smallIcon)
                    }
                }
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            XposedEntry.log("AppIconNotif: error in NotificationHeaderViewWrapper hook", t)
        }
    }

    private fun applyOriginalAppIcon(imageView: ImageView, drawable: Drawable) {
        clearIconStyling(imageView)
        try {
            imageView.setImageIcon(null)
        } catch (_: Throwable) {
        }
        imageView.setImageDrawable(drawable)
        imageView.invalidate()
    }

    private fun clearIconStyling(imageView: ImageView) {
        try {
            imageView.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (_: Throwable) {
        }

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
            XposedCompat.setObjectField(imageView, "mApplyCircularCrop", false)
        } catch (_: Throwable) {
        }

        try {
            XposedCompat.callMethod(imageView, "setApplyCircularCrop", false)
        } catch (_: Throwable) {
        }
    }
}
