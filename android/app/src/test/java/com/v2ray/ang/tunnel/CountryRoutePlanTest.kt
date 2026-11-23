package com.v2ray.ang.tunnel

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.InetAddress

class CountryRoutePlanTest {
    private fun prefix(value: String) = CountryRoutePlan.Prefix(value.substringBefore('/'), value.substringAfter('/').toInt())
    private fun contains(prefix: CountryRoutePlan.Prefix, ip: String): Boolean {
        val bytes = InetAddress.getByName(ip).address
        if (prefix.bytes.size != bytes.size) return false
        val shift = bytes.size * 8 - prefix.length
        return BigInteger(1, bytes).shiftRight(shift) == BigInteger(1, prefix.bytes).shiftRight(shift)
    }
    @Test fun disabledPreservesRoutes() {
        val route = prefix("0.0.0.0/0")
        assertEquals(listOf(route), CountryRoutePlan.subtract(listOf(route), emptyList()))
    }
    @Test fun countryAndOverlappingRangesAreExcluded() {
        val result = CountryRoutePlan.subtract(listOf(prefix("0.0.0.0/0")),
            listOf(prefix("5.160.0.0/16"), prefix("5.160.1.0/24"), prefix("5.161.0.0/16")))
        assertFalse(result.any { contains(it, "5.160.1.2") })
        assertFalse(result.any { contains(it, "5.161.255.255") })
        assertTrue(result.any { contains(it, "1.1.1.1") })
        assertTrue(result.any { contains(it, "5.159.255.255") })
        assertTrue(result.any { contains(it, "5.162.0.0") })
    }
    @Test fun ipv6AndIpv4RemainSeparate() {
        val result = CountryRoutePlan.subtract(listOf(prefix("::/0")),
            listOf(prefix("5.160.0.0/16"), prefix("2001:db8::/32")))
        assertFalse(result.any { contains(it, "2001:db8::1") })
        assertTrue(result.any { contains(it, "2606:4700::1111") })
    }
    @Test fun completeExclusionAndOutsideExclusion() {
        assertTrue(CountryRoutePlan.subtract(listOf(prefix("10.0.0.0/8")), listOf(prefix("0.0.0.0/0"))).isEmpty())
        assertEquals(listOf(prefix("10.0.0.0/8")), CountryRoutePlan.subtract(listOf(prefix("10.0.0.0/8")), listOf(prefix("5.160.0.0/16"))))
    }
    @Test fun exhaustiveSmallSubnet() {
        val result = CountryRoutePlan.subtract(listOf(prefix("192.0.2.0/24")),
            listOf(prefix("192.0.2.8/29"), prefix("192.0.2.128/27")))
        for (last in 0..255) assertEquals(last !in 8..15 && last !in 128..159,
            result.any { contains(it, "192.0.2.$last") })
    }
}
