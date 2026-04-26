package com.example.appiconnotif

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = """
                App Icon Notification

                这是一个 LSPosed 模块。

                功能：
                将通知中的小图标替换为应用图标。

                使用方法：
                1. 安装本应用
                2. 在 LSPosed 中启用模块
                3. 勾选作用域为 SystemUI
                4. 重启系统界面或重启手机
            """.trimIndent()
            textSize = 16f
            setPadding(40, 60, 40, 60)
        }

        setContentView(tv)
    }
}