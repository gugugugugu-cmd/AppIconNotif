# AppIconNotif

一个基于 **libxposed API 102** 的 LSPosed 模块，用于将通知图标替换为应用原始图标。

## 功能介绍

- 在通知中显示应用原始图标
- 在状态栏通知区域显示应用原始图标
- 让第三方应用通知图标保持彩色显示
- **仅对第三方应用生效**
- 不修改系统通知，避免显示异常

## 工作原理

默认情况下，Android 通知图标通常会显示为小型单色图标。

本模块通过 Hook SystemUI，将这些通知图标替换为对应应用的原始图标，从而让通知更容易识别，也更加直观。

## 使用的 libxposed API 102 特性

| 旧版（XposedBridgeApi-82） | 现版本（libxposed API 102） |
| --- | --- |
| `assets/xposed_init` 声明入口 | `META-INF/xposed/java_init.list` 声明入口 |
| `IXposedHookLoadPackage#handleLoadPackage` | `XposedModule#onPackageReady` |
| manifest `xposedmodule` / `xposedscope` meta-data | `META-INF/xposed/module.prop` + `scope.list` |
| `XC_MethodHook` 回调式 Hook | `hook(method).intercept { chain -> ... }` 拦截链 |
| `XposedHelpers` 反射工具 | 自带 `XposedCompat` 轻量反射工具 |
| crossbowffs `RemotePreferences` Provider | 框架托管配置：App 端 `XposedService.getRemotePreferences()` 写入，模块端 `XposedModule.getRemotePreferences()` 只读 |

## 推荐作用域

模块通过 `META-INF/xposed/scope.list` 静态声明作用域（`staticScope=true`）：

- `com.android.systemui`

## 使用要求

- Android 8.0 及以上（libxposed API 102 要求 minSdk 26）
- 支持 libxposed API 102 的 LSPosed / Xposed 框架
- 已 Root 或具备可加载 LSPosed 模块的环境

## 安装方法

1. 安装 APK
2. 在 LSPosed 中启用模块（作用域已通过 scope.list 静态声明为 `com.android.systemui`）
3. 重启 SystemUI 或重启手机

## 注意事项

- 本模块**只处理第三方应用通知**
- 系统应用和系统通知默认不处理，以避免白图标、白块等显示异常
- 不同 ROM 的 SystemUI 实现不同，兼容性可能存在差异
- 从旧版本升级后，由于配置存储从 RemotePreferences 迁移到框架托管配置，需要在模块界面重新勾选目标应用

## 项目用途

本项目的目的是将原本难以辨认的单色通知小图标，替换成应用自身图标，让通知更醒目、更容易区分。

## 免责声明

请自行承担使用风险。  
本模块通过运行时 Hook 修改 SystemUI 行为，不同设备、Android 版本和 ROM 上的表现可能不同。
