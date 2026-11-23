package com.example.spicyvpn

import com.example.spicyvpn.ui.main.CustomFolder
import com.example.spicyvpn.ui.main.CustomOrganization
import org.junit.Assert.*
import org.junit.Test

class CustomOrganizationTest {
    @Test fun ordinaryProfilesCanMoveAndPreserveUnrelatedOrder() {
        val result = CustomOrganization(orderedUrls = listOf("other")).move("b", -1, listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c", "other"), result.orderedUrls)
        assertEquals(listOf("b", "a", "c"), result.ordered(listOf("a", "b", "c")) { it })
    }
    @Test fun anyProtocolCanMoveBetweenFolders() {
        val original = CustomOrganization(folders = listOf(CustomFolder("a", "A"), CustomFolder("b", "B")))
        for (url in listOf("mmkv://xray", "openvpn://test", "ssh://test", "dnstt://test")) {
            val next = original.moveToFolder(url, "a").moveToFolder(url, "b")
            assertFalse(url in next.folders[0].urls)
            assertEquals(listOf(url), next.folders[1].urls)
            assertTrue(next.moveToFolder(url, null).folders.all { it.urls.isEmpty() })
        }
    }
    @Test fun pinsHaveStableOrderAndCanMove() {
        val pins = CustomOrganization().pin("a").pin("b").pin("c")
        assertEquals(listOf("a", "c", "b"), pins.movePinned("c", -1).pinnedUrls)
        assertEquals(pins, pins.movePinned("a", -1))
        assertEquals(listOf("a", "c"), pins.pin("b").pinnedUrls)
    }
    @Test fun persistedOrganizationRoundTrips() {
        val layout = CustomOrganization(listOf(CustomFolder("a", "VPN", listOf("mmkv://test"), true)), listOf("ssh://test"))
        val json = com.v2ray.ang.util.JsonUtil.toJson(layout)
        assertEquals(layout, com.v2ray.ang.util.JsonUtil.fromJsonSafe(json, CustomOrganization::class.java))
    }
}
