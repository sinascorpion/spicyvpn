package com.example.spicyvpn

import com.example.spicyvpn.ui.main.DnsOnlyProfile
import org.junit.Assert.*
import org.junit.Test

class DnsOnlyProfileTest {
    @Test fun acceptsEverySupportedTransport() {
        for (address in listOf("1.1.1.1", "2001:4860:4860::8888", "udp://1.1.1.1:5353", "tcp://dns.google:53", "tls://dns.google", "https://dns.google/dns-query")) {
            assertEquals(address, DnsOnlyProfile.normalize(address))
        }
        assertEquals("tls://dns.google", DnsOnlyProfile.normalize("dot://dns.google"))
        assertEquals("https://dns.google/dns-query", DnsOnlyProfile.normalize("doh://dns.google/dns-query"))
    }
    @Test fun rejectsInvalidResolvers() {
        for (address in listOf("", "ssh://1.1.1.1", "https://user:pass@dns.google/", "tcp://1.1.1.1:99999", "tls://dns.google/path")) {
            assertTrue(runCatching { DnsOnlyProfile.normalize(address) }.isFailure)
        }
    }
}
