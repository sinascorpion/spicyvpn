package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class PattPresetTest {
    @Test fun semiPythonExpandsToOrderedTls12Suites() {
        val suites = CipherSuitePolicy.normalize("semi-python")!!.split(':')
        assertEquals(10, suites.size)
        assertEquals("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", suites.first())
        assertEquals("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", suites.last())
        assertEquals("unsafe", CipherSuitePolicy.fingerprint("unsafe", suites.joinToString(":")))
    }
}
