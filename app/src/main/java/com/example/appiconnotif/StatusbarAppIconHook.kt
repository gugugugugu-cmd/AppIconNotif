package com.example.appiconnotif

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object StatusbarAppIconHook {

    private const val SYSTEMUI = "com.android.systemui"

    private fun log(msg: String) {
        XposedBridge.log("AppIconNotif: $msg")
    }

    private fun log(t: Throwable) {
        XposedBridge.log(t)
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val notificationIconContainerClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer",
                lpparam.classLoader
            )

            val iconStateClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.phone.NotificationIconContainer\$IconState",
                lpparam.classLoader
            )

            val statusBarIconViewClass = XposedHelpers.findClass(
                "$SYSTEMUI.statusbar.StatusBarIconView",
                lpparam.classLoader
            )

            hookApplyIconStates(notificationIconContainerClass)
            hookIconState(iconStateClass)
            hookUpdateIconColor(statusBarIconViewClass)
            hookGetIcon(statusBarIconViewClass, lpparam)
        } catch (t: Throwable) {
            log("Failed to initialize StatusbarAppIconHook")
            log(t)
        }
    }

    // ========== 统一图标规格 (尺寸 + 圆角) ==========
    /**
     * 将原始应用图标转换为统一尺寸、统一圆角的 Drawable
     * @param context 上下文
     * @param originalDrawable 原始应用图标
     * @param targetSizeDp 目标边长（dp），状态栏推荐 24，通知栏头部推荐 40
     * @param cornerRadiusFactor 圆角半径占边长的比例，默认 0.25
     */
    fun createUniformIconDrawable(
        context: Context,
        originalDrawable: Drawable,
        targetSizeDp: Int = 24,
        cornerRadiusFactor: Float = 0.25f
    ): Drawable {
        val density = context.resources.displayMetrics.density
        val targetSizePx = (targetSizeDp * density).toInt()
        val cornerRadiusPx = (targetSizeDp * cornerRadiusFactor * density).toInt()

        // 创建空白 Bitmap
        val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 清空背景为透明
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 获取原始图标的固有尺寸，若无效则直接拉伸绘制
        var intrinsicW = originalDrawable.intrinsicWidth
        var intrinsicH = originalDrawable.intrinsicHeight
        if (intrinsicW <= 0 || intrinsicH <= 0) {
            // fallback: 强制拉伸至目标尺寸
            originalDrawable.setBounds(0, 0, targetSizePx, targetSizePx)
            originalDrawable.draw(canvas)
            // 直接返回（无圆角，但后续剪裁会加上）
        } else {
            // 计算缩放比例，保持宽高比并居中
            val scale = minOf(targetSizePx.toFloat() / intrinsicW, targetSizePx.toFloat() / intrinsicH)
            val scaledW = (intrinsicW * scale).toInt()
            val scaledH = (intrinsicH * scale).toInt()
            val left = (targetSizePx - scaledW) / 2
            val top = (targetSizePx - scaledH) / 2
            originalDrawable.setBounds(left, top, left + scaledW, top + scaledH)
            originalDrawable.draw(canvas)
        }

        // 应用圆角剪裁 (使用 Path)
        val path = Path()
        path.addRoundRect(
            0f, 0f,
            targetSizePx.toFloat(), targetSizePx.toFloat(),
            cornerRadiusPx.toFloat(), cornerRadiusPx.toFloat(),
            Path.Direction.CW
        )
        canvas.clipPath(path)

        // 重新绘制（clipPath 对已有内容生效，需再次绘制）
        // 由于上面的 draw 已经绘制，clipPath 后再绘制一次可保证边缘圆滑
        // 这里简单重新设置绘制一次（性能影响极小）
        originalDrawable.draw(canvas)

        return BitmapDrawable(context.resources, bitmap)
    }
    // =============================================

    private fun hookApplyIconStates(notificationIconContainerClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                notificationIconContainerClass,
                "applyIconStates",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val iconStates =
                                XposedHelpers.getObjectField(param.thisObject, "mIconStates")
                                        as? HashMap<View, Any> ?: return

                            for (icon in iconStates.keys) {
                                removeTintForStatusbarIcon(icon, false)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook applyIconStates")
            log(t)
        }
    }

    private fun hookIconState(iconStateClass: Class<*>) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val icon = param.args[0] as? View ?: return
                    val isNotification = try {
                        XposedHelpers.getObjectField(icon, "mNotification") != null
                    } catch (_: Throwable) {
                        false
                    }
                    removeTintForStatusbarIcon(icon, isNotification)
                } catch (_: Throwable) {
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "initFrom",
                View::class.java,
                hook
            )
        } catch (t: Throwable) {
            log("Failed to hook IconState.initFrom")
            log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                iconStateClass,
                "applyToView",
                View::class.java,
                hook
            )
        } catch (t: Throwable) {
            log("Failed to hook IconState.applyToView")
            log(t)
        }
    }

    private fun hookUpdateIconColor(statusBarIconViewClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "updateIconColor",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject

                            val isNotification = try {
                                XposedHelpers.getObjectField(thisObj, "mNotification") != null
                            } catch (_: Throwable) {
                                false
                            }
                            if (!isNotification) return

                            val statusBarIcon = try {
                                XposedHelpers.getObjectField(thisObj, "mIcon")
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            val pkgName = try {
                                XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            val context = try {
                                XposedHelpers.getObjectField(thisObj, "mContext") as Context
                            } catch (_: Throwable) {
                                null
                            } ?: return

                            if (isThirdPartyApp(context, pkgName)) {
                                param.result = null
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook StatusBarIconView.updateIconColor")
            log(t)
        }
    }

    private fun hookGetIcon(
        statusBarIconViewClass: Class<*>,
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        try {
            val statusBarIconClass = XposedHelpers.findClass(
                "com.android.internal.statusbar.StatusBarIcon",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                statusBarIconViewClass,
                "getIcon",
                statusBarIconClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val thisObj = param.thisObject
                            val statusBarIcon = param.args[0]

                            val sysuiContext = try {
                                XposedHelpers.getObjectField(thisObj, "mContext") as Context
                            } catch (_: Throwable) {
                                return
                            }

                            val sbn = try {
                                XposedHelpers.getObjectField(thisObj, "mNotification")
                            } catch (_: Throwable) {
                                null
                            }

                            var appContext: Context? = null
                            if (sbn != null) {
                                appContext = try {
                                    XposedHelpers.callMethod(sbn, "getPackageContext", sysuiContext) as? Context
                                } catch (_: Throwable) {
                                    null
                                }
                            }

                            if (appContext == null) appContext = sysuiContext

                            setNotificationIcon(statusBarIcon, appContext, param)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Failed to hook getIcon(statusBarIcon)")
            log(t)
        }
    }

    private fun removeTintForStatusbarIcon(icon: View, isNotification: Boolean) {
        try {
            val statusBarIcon = XposedHelpers.getObjectField(icon, "mIcon")
            val pkgName = XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return

            val context = (icon as? ImageView)?.context ?: return

            if (isNotification && isThirdPartyApp(context, pkgName)) {
                try {
                    XposedHelpers.setIntField(icon, "mCurrentSetColor", 0)
                } catch (_: Throwable) {
                    try {
                        XposedHelpers.setObjectField(icon, "mCurrentSetColor", 0)
                    } catch (_: Throwable) {
                    }
                }

                try {
                    XposedHelpers.callMethod(icon, "updateIconColor")
                } catch (_: Throwable) {
                }

                try {
                    (icon as? ImageView)?.imageTintList = null
                } catch (_: Throwable) {
                }

                try {
                    (icon as? ImageView)?.clearColorFilter()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun setNotificationIcon(
        statusBarIcon: Any?,
        context: Context,
        param: XC_MethodHook.MethodHookParam
    ) {
        try {
            if (statusBarIcon == null) return

            val pkgName =
                XposedHelpers.getObjectField(statusBarIcon, "pkg") as? String ?: return

            if (!isThirdPartyApp(context, pkgName)) {
                return
            }

            val originalIcon: Drawable = try {
                context.packageManager.getApplicationIcon(pkgName)
            } catch (_: Throwable) {
                return
            }

            // 统一规格：状态栏图标 24dp，圆角半径 = 24 * 0.25 = 6dp
            val uniformIcon = createUniformIconDrawable(context, originalIcon, 24, 0.25f)
            param.result = uniformIcon
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