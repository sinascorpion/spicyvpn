package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class CipherSuitePolicyTest {
    private val suite = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
    @Test fun acceptsTls13NamesWithTls12Suites() {
        assertEquals(suite, CipherSuitePolicy.normalize("TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:$suite"))
    }
    @Test fun normalizeAndSelectNativeHandshake() {
        assertEquals(suite, CipherSuitePolicy.normalize(" $suite, $suite "))
        assertEquals("unsafe", CipherSuitePolicy.fingerprint(null, suite))
        assertEquals("unsafe", CipherSuitePolicy.fingerprint("unsafe", suite))
    }
    @Test fun preserveBrowserFingerprintWithoutCustomSuites() {
        assertEquals("chrome", CipherSuitePolicy.fingerprint("chrome", null))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectUnsupportedInsteadOfSilentlyIgnoring() {
        CipherSuitePolicy.normalize("NOT_A_TLS_CIPHER")
    }
    @Test fun explicitListTakesPrecedenceOverBrowserPreset() {
        assertEquals("unsafe", CipherSuitePolicy.fingerprint("chrome", suite))
    }
    @Test fun supportsPattNgLegacyRsaMapping() {
        assertEquals("TLS_RSA_WITH_AES_128_GCM_SHA256",
            CipherSuitePolicy.normalize("TLS_RSA_WITH_AES_128_GCM_SHA256"))
    }
}
