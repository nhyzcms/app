package com.nhyzcms.clubapp.shell.push

import com.nhyzcms.clubapp.shell.PermissionsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区块 5 里"不碰 Android 框架"的那部分策略：未读接口的地址收口、响应解析、
 * 以及通知权限的版本判定（Android 13 起才需要申请）。
 */
class PushPolicyTest {

    private val origin = "https://8.153.145.175"

    @Test
    fun acceptsUrlUnderSiteOrigin() {
        assertTrue(UnreadApi.isAllowed(origin + "/messaging/conversations/unread_count/", origin))
    }

    @Test
    fun rejectsForeignHost() {
        assertFalse(UnreadApi.isAllowed("https://evil.example/messaging/conversations/unread_count/", origin))
    }

    @Test
    fun rejectsPrefixThatIsNotAPathBoundary() {
        // 前缀相同的"孪生域名"必须被拒：https://8.153.145.175.evil.example
        assertFalse(UnreadApi.isAllowed("https://8.153.145.175.evil.example/x", origin))
        assertFalse(UnreadApi.isAllowed("https://8.153.145.1755/x", origin))
    }

    @Test
    fun rejectsCleartextAndEmptyPrefix() {
        assertFalse(UnreadApi.isAllowed("http://8.153.145.175/messaging/", origin))
        assertFalse(UnreadApi.isAllowed(origin + "/messaging/", ""))
    }

    @Test
    fun parsesTotalCount() {
        assertEquals(3, UnreadApi.parseTotal("{\"total\":3}"))
        assertEquals(0, UnreadApi.parseTotal("{\"total\":0}"))
        assertEquals(12, UnreadApi.parseTotal("{\"total\":\"12\"}"))
    }

    @Test
    fun rejectsUnparsableTotal() {
        assertNull(UnreadApi.parseTotal(null))
        assertNull(UnreadApi.parseTotal(""))
        assertNull(UnreadApi.parseTotal("not json"))
        assertNull(UnreadApi.parseTotal("{}"))
        assertNull(UnreadApi.parseTotal("[1,2]"))
        assertNull(UnreadApi.parseTotal("{\"total\":-1}"))
    }

    @Test
    fun notificationPermissionIsRequiredOnlyFromApi33() {
        assertEquals(PermissionsPolicy.POST_NOTIFICATIONS, PermissionsPolicy.notificationPermission(33))
        assertEquals(PermissionsPolicy.POST_NOTIFICATIONS, PermissionsPolicy.notificationPermission(34))
        assertNull(PermissionsPolicy.notificationPermission(32))
        assertNull(PermissionsPolicy.notificationPermission(26))
    }
}
