package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Test

class XrayBindingCompatTest {
    class CurrentBinding {
        var calls = 0
        fun queryAllOutboundTrafficStats(): String {
            calls++
            return "proxy,uplink,12;custom,downlink,34;"
        }
    }

    class LegacyBinding {
        val calls = mutableListOf<Pair<String, String>>()
        fun queryStats(tag: String, direction: String): Long {
            calls += tag to direction
            return calls.size.toLong()
        }
    }

    @Test
    fun currentBindingPreservesAllTagsAndReadsOnce() {
        val binding = CurrentBinding()
        assertEquals("proxy,uplink,12;custom,downlink,34;", XrayBindingCompat.queryStatsPayload(binding))
        assertEquals(1, binding.calls)
    }

    @Test
    fun legacyBindingReadsEachResettingCounterOnce() {
        val binding = LegacyBinding()
        assertEquals(
            "proxy,uplink,1;proxy,downlink,2;direct,uplink,3;direct,downlink,4;",
            XrayBindingCompat.queryStatsPayload(binding)
        )
        assertEquals(4, binding.calls.distinct().size)
    }
}
