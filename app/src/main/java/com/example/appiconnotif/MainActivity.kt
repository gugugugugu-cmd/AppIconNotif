package com.example.appiconnotif

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.TreeSet

class MainActivity : Activity() {

    private lateinit var adapter: AppListAdapter
    private val selectedPackages = TreeSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
        selectedPackages.addAll(prefs.getStringSet(Config.KEY_TARGET_PACKAGES, emptySet()) ?: emptySet())

        val listView = findViewById<ListView>(R.id.app_list)
        val emptyView = findViewById<TextView>(R.id.empty_view)
        listView.emptyView = emptyView  // 列表为空时显示 emptyView

        val apps = loadApps()

        adapter = AppListAdapter(this, apps) { item, checked ->
            if (checked) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }

            // 使用 commit() 确保立即写入磁盘
            prefs.edit()
                .putStringSet(Config.KEY_TARGET_PACKAGES, selectedPackages)
                .commit()

            Toast.makeText(this, "配置已保存，重启 SystemUI 后生效", Toast.LENGTH_SHORT).show()
        }

        listView.adapter = adapter
    }

    private fun loadApps(): List<AppItem> {
        val packageManager = packageManager

        return packageManager.getInstalledApplications(0)
            .filter { appInfo ->
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp =
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                !isSystemApp && !isUpdatedSystemApp &&
                    appInfo.packageName != packageName
            }
            .map { appInfo ->
                AppItem(
                    label = appInfo.loadLabel(packageManager).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(packageManager),
                    checked = selectedPackages.contains(appInfo.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}