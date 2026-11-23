package com.v2ray.ang.fmt

import org.junit.Assert.*
import org.junit.Test

class Ikev2FmtTest {
    @Test fun roundTripPreservesCredentialsAndIdentity() {
        val profile = Ikev2Fmt.parse("ikev2://alice:p%40ss@example.com?identity=alice%40example.com#Office")
        val restored = Ikev2Fmt.parse("ikev2://" + Ikev2Fmt.toUri(profile))
        assertEquals("p@ss", restored.password)
        assertEquals("alice@example.com", restored.sni)
        assertEquals("example.com", restored.server)
        assertEquals("Office", restored.remarks)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingPassword() {
        Ikev2Fmt.parse("ikev2://alice@example.com")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsupportedPort() {
        Ikev2Fmt.parse("ikev2://alice:password@example.com:443")
    }
}
