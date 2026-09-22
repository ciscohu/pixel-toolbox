/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.example.pixeltoolbox.services.push

import android.content.Context
import android.content.pm.PackageManager
import com.example.pixeltoolbox.shizuku.ShizukuUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ManagedPushApp(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean,
    val iconBitmap: android.graphics.Bitmap? = null
)

object UnifiedPushManager {

    const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"

    /**
     * 检测设备上是否已安装小米推送框架 (com.xiaomi.xmsf)
     */
    fun isXmsfInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(XMSF_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            val res = ShizukuUtils.executeCommandOrNull("pm list packages $XMSF_PACKAGE")
            !res.isNullOrBlank() && res.contains(XMSF_PACKAGE)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 实时检测底层统一推送进程与服务是否真正处于运行中
     */
    suspend fun isPushServiceRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isXmsfInstalled(context)) return@withContext false

        // Do not trust only the app's SharedPreferences flag: the package/service may have
        // failed to install or start. Prefer the real process/service state.
        val pid = ShizukuUtils.executeCommandOrNull("pidof $XMSF_PACKAGE")?.trim()
        if (!pid.isNullOrBlank()) return@withContext true

        val serviceDump = ShizukuUtils.executeCommandOrNull(
            "dumpsys activity services $XMSF_PACKAGE/.push.service.XMPushService"
        ).orEmpty()
        val serviceActive = serviceDump.contains("ServiceRecord") &&
            serviceDump.contains(XMSF_PACKAGE)

        return@withContext serviceActive
    }

    /**
     * 实时检测微信/QQ 厂商推送伪装状态
     */
    suspend fun isTencentSpoofEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        val isSpOn = sp.getBoolean("tencent_spoof_enabled", false)
        val propRes = ShizukuUtils.executeCommandOrNull("getprop ro.miui.ui.version.name")?.trim() == "V140"

        return@withContext isSpOn || propRes
    }

    /**
     * 实时查询设备上支持/已绑定统一推送 (MiPush/FCM) 的 App 列表
     */
    suspend fun getManagedApps(context: Context): List<ManagedPushApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val list = mutableListOf<ManagedPushApp>()

        val result = ShizukuUtils.executeCommandOrNull("pm query-receivers -a com.xiaomi.mipush.RECEIVE_MESSAGE --brief") ?: ""
        val lines = result.lines().map { it.trim() }.filter { it.contains("/") }

        val pkgs = lines.map { it.substringBefore("/") }.toMutableSet()

        val tencentSpoofed = isTencentSpoofEnabled(context)
        if (tencentSpoofed) {
            pkgs.add(WECHAT_PACKAGE)
            pkgs.add(QQ_PACKAGE)
        }

        for (pkg in pkgs) {
            if (pkg == context.packageName || pkg == XMSF_PACKAGE) continue
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val iconBitmap: android.graphics.Bitmap? = try {
                    val drawable = pm.getApplicationIcon(pkg)
                    val bmp = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, 72, 72)
                    drawable.draw(canvas)
                    bmp
                } catch (e: Exception) { null }

                list.add(ManagedPushApp(pkg, label, true, iconBitmap))
            } catch (_: Exception) {}
        }

        list.sortBy { it.appName.lowercase() }
        return@withContext list
    }

    /**
     * 将内置 xmsf.apk 复制到 Shell 可读的 /data/local/tmp 后安装。
     * Pixel Toolbox 自身的 cacheDir 对 Shizuku shell 不可直接读取，因此不能把
     * app 私有缓存路径直接传给 pm install。
     */
    private suspend fun ensureXmsfInstalled(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (isXmsfInstalled(context)) {
            return@withContext Result.success("MiPush Framework 已安装")
        }

        val cacheFile = java.io.File(context.cacheDir, "xmsf.apk")
        val tmpPath = "/data/local/tmp/pixeltoolbox_xmsf.apk"

        try {
            context.assets.open("xmsf.apk").use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!cacheFile.exists() || cacheFile.length() <= 0L) {
                return@withContext Result.failure(Exception("内置 xmsf.apk 为空或不存在"))
            }

            val pushRes = ShizukuUtils.streamFileTo(
                "cat > $tmpPath && chmod 644 $tmpPath",
                cacheFile
            )
            if (pushRes.isFailure) {
                return@withContext Result.failure(
                    Exception("复制 xmsf.apk 到 /data/local/tmp 失败: ${pushRes.exceptionOrNull()?.message}")
                )
            }

            val installRes = ShizukuUtils.executeCommand(
                "pm install -r -g --user 0 $tmpPath"
            )
            // Always clean up the temporary APK. Cleanup failure must not mask install status.
            ShizukuUtils.executeCommand("rm -f $tmpPath 2>/dev/null")

            if (installRes.isFailure) {
                return@withContext Result.failure(
                    Exception("MiPush Framework 安装失败: ${installRes.exceptionOrNull()?.message}")
                )
            }

            val verify = ShizukuUtils.executeCommandOrNull(
                "pm list packages $XMSF_PACKAGE"
            ).orEmpty()
            if (!verify.contains("package:$XMSF_PACKAGE")) {
                return@withContext Result.failure(
                    Exception("pm install 已执行，但系统未检测到 $XMSF_PACKAGE")
                )
            }

            return@withContext Result.success("MiPush Framework 安装成功")
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        } finally {
            try { cacheFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * 一键无感开启统一推送托管框架。
     * 流程：确保安装 -> 启用包/权限 -> 启动 XMPushService -> 验证真实运行状态。
     */
    suspend fun enablePushService(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val installRes = ensureXmsfInstalled(context)
        if (installRes.isFailure) {
            return@withContext installRes
        }

        // Best-effort permission/background preparation. Some appops do not exist on every
        // Android build, so non-critical failures should not abort the whole flow.
        val prepCommands = listOf(
            "pm enable --user 0 $XMSF_PACKAGE",
            "pm grant $XMSF_PACKAGE android.permission.POST_NOTIFICATIONS 2>/dev/null || true",
            "cmd notification set_notifications_enabled $XMSF_PACKAGE true 2>/dev/null || true",
            "cmd appops set $XMSF_PACKAGE POST_NOTIFICATION allow 2>/dev/null || true",
            "cmd appops set $XMSF_PACKAGE RUN_IN_BACKGROUND allow 2>/dev/null || true",
            "cmd appops set $XMSF_PACKAGE WAKE_LOCK allow 2>/dev/null || true",
            "cmd appops set $XMSF_PACKAGE AUTO_START allow 2>/dev/null || true",
            "dumpsys deviceidle whitelist +$XMSF_PACKAGE 2>/dev/null || true",
            "pm disable --user 0 $XMSF_PACKAGE/top.trumeet.mipushframework.wizard.WelcomeActivity 2>/dev/null || true"
        )
        for (cmd in prepCommands) {
            val result = ShizukuUtils.executeCommand(cmd)
            if (cmd.startsWith("pm enable") && result.isFailure) {
                return@withContext Result.failure(
                    Exception("MiPush Framework 启用失败: ${result.exceptionOrNull()?.message}")
                )
            }
        }

        val startRes = ShizukuUtils.executeCommand(
            "am startservice --user 0 -n $XMSF_PACKAGE/.push.service.XMPushService"
        )
        if (startRes.isFailure) {
            return@withContext Result.failure(
                Exception("XMPushService 启动失败: ${startRes.exceptionOrNull()?.message}")
            )
        }

        // Give the service a brief moment to create its process before verification.
        kotlinx.coroutines.delay(800)

        val installed = isXmsfInstalled(context)
        val running = isPushServiceRunning(context)
        if (!installed) {
            return@withContext Result.failure(Exception("MiPush Framework 安装后校验失败"))
        }
        if (!running) {
            val startOutput = startRes.getOrNull().orEmpty()
            return@withContext Result.failure(
                Exception("MiPush Framework 已安装，但 XMPushService 未进入运行状态。startservice 输出: $startOutput")
            )
        }

        ShizukuUtils.executeCommand("settings put global pixeltoolbox_mipush_enabled 1")
        context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("mipush_enabled", true)
            .apply()

        return@withContext Result.success("MiPush Framework 已安装并启动托管")
    }

    /**
     * 关闭统一推送服务保活
     */
    suspend fun disablePushService(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "pm disable $XMSF_PACKAGE 2>/dev/null",
            "am force-stop $XMSF_PACKAGE 2>/dev/null",
            "settings put global pixeltoolbox_mipush_enabled 0"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("mipush_enabled", false).apply()

        return@withContext ShizukuUtils.executeCommand(cmds)
    }

    /**
     * 显式安装入口：复用与“开启托管”完全相同的可靠安装流程。
     */
    suspend fun installBuiltinXmsf(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val installRes = ensureXmsfInstalled(context)
        if (installRes.isFailure) return@withContext installRes
        return@withContext enablePushService(context)
    }

    /**
     * 开启 微信/QQ 伪装
     */
    suspend fun enableTencentSpoof(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "setprop persist.sys.miui.version V140 2>/dev/null",
            "settings put global pixeltoolbox_tencent_mipush 1"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("tencent_spoof_enabled", true).apply()

        return@withContext ShizukuUtils.executeCommand(cmds)
    }

    /**
     * 关闭 微信/QQ 伪装
     */
    suspend fun disableTencentSpoof(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val cmds = listOf(
            "settings put global pixeltoolbox_tencent_mipush 0"
        ).joinToString("; ")

        val sp = context.getSharedPreferences("push_prefs", Context.MODE_PRIVATE)
        sp.edit().putBoolean("tencent_spoof_enabled", false).apply()

        return@withContext ShizukuUtils.executeCommand(cmds)
    }
}
