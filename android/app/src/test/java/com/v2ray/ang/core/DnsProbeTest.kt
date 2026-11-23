package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class DnsProbeTest {
    private val response = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
    @Test fun acceptsMatchingSuccessfulAnswer() { assertTrue(DnsProbe.isSuccessfulResponse(response, 0x1234)) }
    @Test fun rejectsDifferentTransaction() { assertFalse(DnsProbe.isSuccessfulResponse(response, 0x1235)) }
    @Test fun rejectsErrorResponse() { assertFalse(DnsProbe.isSuccessfulResponse(response.clone().apply { this[3] = 0x83.toByte() }, 0x1234)) }
    @Test fun rejectsQueryAndEmptyAnswer() {
        assertFalse(DnsProbe.isSuccessfulResponse(response.clone().apply { this[2] = 1 }, 0x1234))
        assertFalse(DnsProbe.isSuccessfulResponse(response.clone().apply { this[7] = 0 }, 0x1234))
    }
}
