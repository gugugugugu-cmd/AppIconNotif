package com.example.appiconnotif

object Config {
    const val MODULE_PACKAGE = "com.example.appiconnotif"
    const val TARGET_SYSTEMUI = "com.android.systemui"

    /**
     * libxposed 框架托管的远程配置组名。
     * App 端（XposedService.getRemotePreferences）与模块端
     * （XposedModule.getRemotePreferences）使用同一 group name 访问。
     */
    const val REMOTE_PREFS_GROUP = "app_icon_notif_config"

    const val KEY_TARGET_PACKAGES = "target_packages"
}
