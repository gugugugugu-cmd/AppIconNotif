package com.example.appiconnotif

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import java.util.TreeSet

class MainActivity : Activity() {

    private lateinit var adapter: AppListAdapter
    private val selectedPackages = TreeSet<String>()
    private lateinit var appList: MutableList<AppItem>
    private lateinit var filteredList: MutableList<AppItem>
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        // 设置 Toolbar 作为 ActionBar
        setActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        val prefs = getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
        selectedPackages.addAll(prefs.getStringSet(Config.KEY_TARGET_PACKAGES, emptySet()) ?: emptySet())

        listView = findViewById(R.id.app_list)
        emptyView = findViewById(R.id.empty_view)
        listView.emptyView = emptyView

        // 加载所有应用（含系统应用标记，但过滤掉系统应用）
        appList = loadApps()
        filteredList = appList.toMutableList()

        adapter = AppListAdapter(this, filteredList) { item, checked ->
            if (checked) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }

            prefs.edit()
                .putStringSet(Config.KEY_TARGET_PACKAGES, selectedPackages)
                .commit()

            Toast.makeText(this, "配置已保存，重启 SystemUI 后生效", Toast.LENGTH_SHORT).show()
        }

        listView.adapter = adapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as? SearchView
        searchView?.let {
            it.queryHint = "搜索应用名称或包名"
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    filterApps(newText?.trim() ?: "")
                    return true
                }
            })
            // 展开搜索框时自动获取焦点
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    filterApps("")
                    return true
                }
            })
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> {
                val allChecked = filteredList.all { it.checked }
                val newChecked = !allChecked
                // 更新所有可见项的状态
                filteredList.forEach { it.checked = newChecked }
                // 同步更新 selectedPackages
                if (newChecked) {
                    filteredList.forEach { selectedPackages.add(it.packageName) }
                } else {
                    filteredList.forEach { selectedPackages.remove(it.packageName) }
                }
                // 保存配置
                val prefs = getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putStringSet(Config.KEY_TARGET_PACKAGES, selectedPackages)
                    .commit()
                adapter.notifyDataSetChanged()
                Toast.makeText(this, if (newChecked) "已全选" else "已取消全选", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            filteredList.clear()
            filteredList.addAll(appList)
        } else {
            val lower = query.lowercase()
            filteredList.clear()
            filteredList.addAll(appList.filter {
                it.label.lowercase().contains(lower) ||
                        it.packageName.lowercase().contains(lower)
            })
        }
        // 保留之前的选中状态（已保存在 AppItem 中）
        adapter.notifyDataSetChanged()
        listView.invalidate()
    }

    private fun loadApps(): MutableList<AppItem> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { appInfo ->
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                !isSystem && !isUpdatedSystem && appInfo.packageName != packageName
            }
            .map { appInfo ->
                AppItem(
                    label = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    checked = selectedPackages.contains(appInfo.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toMutableList()
    }
}