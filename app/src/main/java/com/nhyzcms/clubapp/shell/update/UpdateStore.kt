package com.nhyzcms.clubapp.shell.update

import android.content.Context
import android.content.SharedPreferences

/**
 * 清单缓存与更新状态（SharedPreferences）。
 *
 * 设计取舍：清单里的 `startRoute` 与 `flags` **先落盘、下次冷启动生效**，
 * 而不是"拉到了就立刻跳页"。原因是首屏不能等网络：冷启动时直接用上次缓存的值
 * （首次安装用内置默认值），后台再拉新清单更新缓存。代价是配置变更晚一次启动生效，
 * 这在"3 M 带宽、无 CDN"的约束下是划算的。
 */
class UpdateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次成功拉到清单的时刻（用于前台节流）。 */
    var lastCheckAtMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
        }

    /** 上次成功解析出的起始路由；null = 用内置默认。 */
    var startRoute: String?
        get() = prefs.getString(KEY_START_ROUTE, null)
        set(value) {
            prefs.edit().putString(KEY_START_ROUTE, value).apply()
        }

    /** 已展示过的公告 id（同一条公告只弹一次）。 */
    var dismissedNoticeId: String?
        get() = prefs.getString(KEY_NOTICE_ID, null)
        set(value) {
            prefs.edit().putString(KEY_NOTICE_ID, value).apply()
        }

    /**
     * 未完成下载的 sha256。只有它和清单里的 sha256 一致时才允许对上次的
     * `.part` 文件做断点续传（否则那是另一个版本的残留，续上去必然校验失败）。
     */
    var pendingSha256: String?
        get() = prefs.getString(KEY_PENDING_SHA, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_PENDING_SHA).apply()
            else prefs.edit().putString(KEY_PENDING_SHA, value).apply()
        }

    /** 当前生效的开关集合（内置默认 + 上次清单里写的值）。 */
    fun flags(): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        for ((name, defaultValue) in UpdateManifest.DEFAULT_FLAGS) {
            result[name] = prefs.getBoolean(flagKey(name), defaultValue)
        }
        return result
    }

    /** 落盘清单里的开关（只写清单里出现过的键）。 */
    fun saveFlags(flags: Map<String, Boolean>) {
        val editor = prefs.edit()
        for ((name, value) in flags) {
            if (name.isNotBlank()) editor.putBoolean(flagKey(name), value)
        }
        editor.apply()
    }

    private fun flagKey(name: String): String = KEY_FLAG_PREFIX + name

    private companion object {
        const val PREFS_NAME = "clubapp-update"
        const val KEY_LAST_CHECK = "lastCheckAtMs"
        const val KEY_START_ROUTE = "startRoute"
        const val KEY_NOTICE_ID = "noticeId"
        const val KEY_PENDING_SHA = "pendingSha256"
        const val KEY_FLAG_PREFIX = "flag."
    }
}
