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
    private lateinit var systemBtn: Button
    private lateinit var thirdPartyBtn: Button
    private var adapter: AppListAdapter? = null

    private val allApps = mutableListOf<AppInfo>()
    private var systemApps = mutableListOf<AppInfo>()
    private var thirdPartyApps = mutableListOf<AppInfo>()
    private var currentDisplayApps = mutableListOf<AppInfo>()
    private var currentMode = MODE_THIRD_PARTY   // 默认显示第三方应用

    companion object {
        private const val MODE_SYSTEM = 0
        private const val MODE_THIRD_PARTY = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        // 搜索框
        searchEditText = EditText(this).apply {
            hint = "🔍 搜索应用名或包名"
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = filterAndDisplay()
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        mainLayout.addView(searchEditText)

        // 分栏按钮区域
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        systemBtn = Button(this).apply {
            text = "系统应用"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                currentMode = MODE_SYSTEM
                updateButtonStyle()
                filterAndDisplay()
            }
        }
        thirdPartyBtn = Button(this).apply {
            text = "第三方应用"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                currentMode = MODE_THIRD_PARTY
                updateButtonStyle()
                filterAndDisplay()
            }
        }
        buttonLayout.addView(systemBtn)
        buttonLayout.addView(thirdPartyBtn)
        mainLayout.addView(buttonLayout)

        // 列表视图
        listView = ListView(this)
        mainLayout.addView(listView)

        setContentView(mainLayout)
        loadApps()
        updateButtonStyle()
    }

    private fun updateButtonStyle() {
        if (currentMode == MODE_SYSTEM) {
            systemBtn.isEnabled = false
            thirdPartyBtn.isEnabled = true
            systemBtn.setBackgroundColor(Color.parseColor("#2196F3"))
            thirdPartyBtn.setBackgroundColor(Color.parseColor("#E0E0E0"))
        } else {
            systemBtn.isEnabled = true
            thirdPartyBtn.isEnabled = false
            systemBtn.setBackgroundColor(Color.parseColor("#E0E0E0"))
            thirdPartyBtn.setBackgroundColor(Color.parseColor("#2196F3"))
        }
    }

    private fun loadApps() {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(0)
        allApps.clear()
        systemApps.clear()
        thirdPartyApps.clear()

        for (app in installedApps) {
            val label = pm.getApplicationLabel(app).toString()
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val icon = app.loadIcon(pm)
            val appInfo = AppInfo(app.packageName, label, isSystem, icon)
            allApps.add(appInfo)
            if (isSystem) {
                systemApps.add(appInfo)
            } else {
                thirdPartyApps.add(appInfo)
            }
        }
        // 各自排序
        systemApps.sortBy { it.label.toLowerCase(Locale.getDefault()) }
        thirdPartyApps.sortBy { it.label.toLowerCase(Locale.getDefault()) }

        filterAndDisplay()
    }

    private fun filterAndDisplay() {
        val query = searchEditText.text.toString().trim().toLowerCase(Locale.getDefault())
        val sourceList = if (currentMode == MODE_SYSTEM) systemApps else thirdPartyApps

        currentDisplayApps.clear()
        for (app in sourceList) {
            if (query.isEmpty() ||
                app.label.toLowerCase(Locale.getDefault()).contains(query) ||
                app.packageName.toLowerCase(Locale.getDefault()).contains(query)
            ) {
                currentDisplayApps.add(app)
            }
        }

        if (adapter == null) {
            adapter = AppListAdapter(this, currentDisplayApps)
            listView.adapter = adapter
        } else {
            adapter?.updateList(currentDisplayApps)
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

            val iconView = ImageView(context).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(100, 100)
            }
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