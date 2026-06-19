package com.example.appiconnotif

import com.crossbowffs.remotepreferences.RemotePreferenceProvider

class ConfigProvider : RemotePreferenceProvider(
    Config.PREFERENCE_AUTHORITY,
    arrayOf(Config.PREF_NAME)
)