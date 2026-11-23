package com.v2ray.ang.core

import com.example.spicyvpn.ui.main.expandCidr
import org.junit.Assert.*
import org.junit.Test

class ScannerCidrTest {
    @Test fun normalizesSubnetStart() {
        val values = expandCidr("192.0.2.45/24")
        assertEquals(256, values.size)
        assertEquals("192.0.2.0", values.first())
        assertEquals("192.0.2.255", values.last())
    }
    @Test fun largeSubnetsHaveBoundedSamples() { assertEquals(512, expandCidr("0.0.0.0/0").size) }
    @Test fun invalidOctetsAreRejected() { assertTrue(expandCidr("999.1.2.3/24").isEmpty()) }
}
