package com.example.appiconnotif

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val pm = packageManager
        val allApps = pm.getInstalledApplications(0)
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second }

        for ((pkg, label) in allApps) {
            val switch = Switch(this).apply {
                text = "$label ($pkg)"
                isChecked = PerAppConfig.isReplacementEnabled(pkg)
                setOnCheckedChangeListener { _, isChecked ->
                    PerAppConfig.setReplacementEnabled(pkg, isChecked, this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "已保存：$label", Toast.LENGTH_SHORT).show()
                }
            }
            layout.addView(switch)
        }

        setContentView(layout)
    }
}