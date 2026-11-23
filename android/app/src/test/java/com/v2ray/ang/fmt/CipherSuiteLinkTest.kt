package com.v2ray.ang.fmt

import org.junit.Assert.*
import org.junit.Test

class CipherSuiteLinkTest {
    private val endpoint = "vless://00000000-0000-0000-0000-000000000001@example.com:443?encryption=none&type=tcp"
    private val suite = "TLS_RSA_WITH_AES_128_GCM_SHA256"

    @Test fun plainXrayLinkDoesNotAcquireTlsOrCipherSettings() {
        val profile = requireNotNull(VlessFmt.parse(endpoint + "&security=none"))
        assertNull(profile.security)
        assertNull(profile.cipherSuites)
    }

    @Test fun importsCommonCipherSuiteQueryNames() {
        for (name in listOf("cs", "cipherSuite", "cipherSuites", "ciphersuite", "ciphersuit", "cipher_suite")) {
            val profile = requireNotNull(VlessFmt.parse(endpoint + "&security=tls&$name=$suite"))
            assertEquals(name, suite, profile.cipherSuites)
            assertEquals("tls", profile.security)
            val restored = requireNotNull(VlessFmt.parse("vless://" + VlessFmt.toUri(profile)))
            assertEquals(suite, restored.cipherSuites)
        }
    }
}
