package com.v2ray.ang.fmt

import org.junit.Assert.*
import org.junit.Test

class MihomoFmtTest {
    @Test fun preservesNativeAmneziaOptions() {
        val config = MihomoFmt.readConfig("""
            proxies:
              - name: awg
                type: wireguard
                amnezia-wg-option:
                  jc: 5
                  s1: 16
                  h1: 123
            rules: [MATCH,awg]
        """.trimIndent())
        val proxy = (config["proxies"] as List<*>).first() as Map<*, *>
        assertEquals(16, (proxy["amnezia-wg-option"] as Map<*, *>)["s1"])
    }
    @Test fun rejectsDuplicateKeys() {
        assertNull(MihomoFmt.parse("proxies: []\nproxies: []"))
    }
    @Test fun rejectsUnrelatedYaml() {
        assertNull(MihomoFmt.parse("message: hello"))
    }
    @Test fun acceptsProxyProviders() {
        assertNotNull(MihomoFmt.parse("proxy-providers: {}\nrules: [MATCH,DIRECT]"))
    }
}
