package com.v2ray.ang.fmt

import org.junit.Assert.*
import org.junit.Test

class OpenConnectFmtTest {
    @Test fun exportRoundTripPreservesCredentialsAndCertificate() {
        val original = OpenConnectFmt.parse("openconnect://alice:p%40ss@example.com:444/vpn?group=staff&servercert=sha256%3Atest&ca=YWJj#Office")
        val restored = OpenConnectFmt.parse("openconnect://" + OpenConnectFmt.toUri(original))
        assertEquals(original.password, restored.password)
        assertEquals(original.pinSHA256, restored.pinSHA256)
        assertEquals(original.nativeConfig, restored.nativeConfig)
        assertEquals(original.path, restored.path)
    }
    @Test fun importsCredentialsAndServerPin() {
        val profile = OpenConnectFmt.parse("openconnect://alice:p%40ss@example.com:444/vpn?group=staff&servercert=sha256%3Atest#Office")
        assertEquals("alice", profile.username)
        assertEquals("p@ss", profile.password)
        assertEquals("444", profile.serverPort)
        assertEquals("/vpn", profile.path)
        assertEquals("staff", profile.serviceName)
        assertEquals("sha256:test", profile.pinSHA256)
        assertEquals("Office", profile.remarks)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidPort() {
        OpenConnectFmt.parse("openconnect://example.com:99999")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownProtocol() {
        OpenConnectFmt.parse("openconnect://example.com?protocol=unsupported")
    }
}
