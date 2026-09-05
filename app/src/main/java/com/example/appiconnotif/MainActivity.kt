package com.example.appiconnotif

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import io.github.libxposed.service.XposedService
import java.util.TreeSet

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: AppListAdapter
    private val selectedPackages = TreeSet<String>()
    private lateinit var appList: MutableList<AppItem>
    private lateinit var filteredList: MutableList<AppItem>
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    private val localPrefs: SharedPreferences by lazy {
        getSharedPreferences(Config.REMOTE_PREFS_GROUP, Context.MODE_PRIVATE)
    }

    private val serviceListener: (XposedService?) -> Unit = { service ->
        if (service != null) {
            // 服务通常在 Activity 创建后异步绑定，绑定后把本地持久化配置推送到框架。
            syncToRemote(service)
            Toast.makeText(this, "已连接 Xposed 框架服务", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // 界面始终从本地配置读取，避免 XposedService 异步绑定导致勾选状态消失。
        selectedPackages.addAll(
            localPrefs.getStringSet(Config.KEY_TARGET_PACKAGES, emptySet()) ?: emptySet()
        )

        listView = findViewById(R.id.app_list)
        emptyView = findViewById(R.id.empty_view)
        listView.emptyView = emptyView

        appList = loadApps()
        filteredList = appList.toMutableList()

        adapter = AppListAdapter(this, filteredList) { item, checked ->
            if (checked) {
                selectedPackages.add(item.packageName)
            } else {
                selectedPackages.remove(item.packageName)
            }

            saveSelection()
            refreshAppOrder()
            Toast.makeText(this, "配置已保存，重启 SystemUI 后生效", Toast.LENGTH_SHORT).show()
        }

        listView.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        App.addServiceListener(serviceListener)
    }

    override fun onStop() {
        App.removeServiceListener(serviceListener)
        super.onStop()
    }

    /** 本地同步提交，远程配置只作为 Hook 端读取源。 */
    private fun saveSelection() {
        val snapshot = HashSet(selectedPackages)

        // 关键修复：无论服务是否绑定，都先持久化到本地。
        localPrefs.edit()
            .putStringSet(Config.KEY_TARGET_PACKAGES, snapshot)
            .commit()

        App.xposedService?.let { service ->
            syncToRemote(service, snapshot)
        }
    }

    private fun syncToRemote(service: XposedService, targets: Set<String> = selectedPackages) {
        try {
            service.getRemotePreferences(Config.REMOTE_PREFS_GROUP)
                .edit()
                .putStringSet(Config.KEY_TARGET_PACKAGES, HashSet(targets))
                .commit()
        } catch (t: Throwable) {
            Toast.makeText(this, "远程配置同步失败，已保留本地配置", Toast.LENGTH_LONG).show()
        }
    }

    /** 勾选后立即刷新顺序，已选应用置顶，应用名作为第二排序条件。 */
    private fun refreshAppOrder() {
        val comparator = compareByDescending<AppItem> { it.checked }
            .thenBy { it.label.lowercase() }
            .thenBy { it.packageName }
        appList.sortWith(comparator)
        filteredList.sortWith(comparator)
        adapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as? SearchView
        searchView?.let {
            it.queryHint = "搜索应用名称或包名"
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    filterApps(newText?.trim() ?: "")
                    return true
                }
            })
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

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
                filteredList.forEach { it.checked = newChecked }
                if (newChecked) {
                    filteredList.forEach { selectedPackages.add(it.packageName) }
                } else {
                    filteredList.forEach { selectedPackages.remove(it.packageName) }
                }
                saveSelection()
                refreshAppOrder()
                Toast.makeText(this, if (newChecked) "已全选" else "已取消全选", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun filterApps(query: String) {
        val source = if (query.isEmpty()) {
            appList
        } else {
            val lower = query.lowercase()
            appList.filter {
                it.label.lowercase().contains(lower) ||
                    it.packageName.lowercase().contains(lower)
            }
        }
        filteredList.clear()
        filteredList.addAll(source)
        adapter.notifyDataSetChanged()
        listView.invalidate()
    }

    private fun loadApps(): MutableList<AppItem> {
        val pm = packageManager
        val comparator = compareByDescending<AppItem> { it.checked }
            .thenBy { it.label.lowercase() }
            .thenBy { it.packageName }

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
            .sortedWith(comparator)
            .toMutableList()
    }
}
