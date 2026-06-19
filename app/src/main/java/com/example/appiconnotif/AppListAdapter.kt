package com.example.appiconnotif

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView

class AppListAdapter(
    private val context: Context,
    private val apps: List<AppItem>,
    private val onCheckedChanged: (AppItem, Boolean) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = apps.size

    override fun getItem(position: Int): AppItem = apps[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_app, parent, false)

        val item = getItem(position)

        val iconView = view.findViewById<ImageView>(R.id.app_icon)
        val labelView = view.findViewById<TextView>(R.id.app_label)
        val packageView = view.findViewById<TextView>(R.id.app_package)
        val checkBox = view.findViewById<CheckBox>(R.id.app_checkbox)

        iconView.setImageDrawable(item.icon)
        labelView.text = item.label
        packageView.text = item.packageName

        checkBox.setOnCheckedChangeListener(null)
        checkBox.isChecked = item.checked

        val clickListener = View.OnClickListener {
            val newChecked = !item.checked
            item.checked = newChecked
            checkBox.isChecked = newChecked
            onCheckedChanged(item, newChecked)
        }

        view.setOnClickListener(clickListener)
        checkBox.setOnClickListener(clickListener)

        return view
    }
}