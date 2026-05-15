package com.example.appiconnotif

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.*

class SettingsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var searchEditText: EditText
    private lateinit var hideSystemCheckBox: CheckBox
    private lateinit var adapter: AppListAdapter

    private val allApps = mutableListOf<AppInfo>()
    private var displayApps = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        searchEditText = EditText(this).apply {
            hint = "🔍 搜索应用名或包名"
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = filterAndDisplay()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        mainLayout.addView(searchEditText)

        hideSystemCheckBox = CheckBox(this).apply {
            text = "隐藏系统应用"
            setOnCheckedChangeListener { _, _ -> filterAndDisplay() }
        }
        mainLayout.addView(hideSystemCheckBox)

        listView = ListView(this)
        mainLayout.addView(listView)

        setContentView(mainLayout)
        loadApps()
    }

    private fun loadApps() {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(0)
        allApps.clear()
        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString()
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val icon = app.loadIcon(pm)
            allApps.add(AppInfo(app.packageName, label, isSystem, icon))
        }
        allApps.sortBy { it.label.toLowerCase(Locale.getDefault()) }
        filterAndDisplay()
    }

    private fun filterAndDisplay() {
        val query = searchEditText.text.toString().trim().toLowerCase(Locale.getDefault())
        val hideSystem = hideSystemCheckBox.isChecked

        displayApps.clear()
        for (app in allApps) {
            if (hideSystem && app.isSystem) continue
            if (query.isNotEmpty() &&
                !app.label.toLowerCase(Locale.getDefault()).contains(query) &&
                !app.packageName.toLowerCase(Locale.getDefault()).contains(query)
            ) continue
            displayApps.add(app)
        }

        if (adapter == null) {
            adapter = AppListAdapter(this, displayApps)
            listView.adapter = adapter
        } else {
            adapter.updateList(displayApps)
        }
    }

    inner class AppListAdapter(
        private val context: Context,
        private var apps: List<AppInfo>
    ) : BaseAdapter() {

        fun updateList(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun getCount(): Int = apps.size
        override fun getItem(pos: Int): AppInfo = apps[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val linearLayout = if (convertView is LinearLayout) {
                convertView.apply { removeAllViews() }
            } else {
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(20, 20, 20, 20)
                    layoutParams = AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }

            val app = getItem(pos)

            // 应用图标
            val iconView = ImageView(context).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(100, 100)
            }
            // 文字区域
            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(context).apply {
                text = app.label
                textSize = 16f
            }
            val pkgView = TextView(context).apply {
                text = app.packageName
                textSize = 12f
                setTextColor(Color.GRAY)
            }
            textLayout.addView(nameView)
            textLayout.addView(pkgView)

            // 开关
            val switch = Switch(context).apply {
                isChecked = PerAppConfig.isReplacementEnabled(app.packageName)
                setOnCheckedChangeListener { _, isChecked ->
                    PerAppConfig.setReplacementEnabled(app.packageName, isChecked, context)
                    Toast.makeText(context, "已保存：${app.label}", Toast.LENGTH_SHORT).show()
                }
            }

            linearLayout.addView(iconView)
            linearLayout.addView(textLayout)
            linearLayout.addView(switch)
            return linearLayout
        }
    }
}

data class AppInfo(val packageName: String, val label: String, val isSystem: Boolean, val icon: android.graphics.drawable.Drawable)