package com.v2ray.ang.tunnel

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TorBridgeConfigTest {
    @Test fun allTransportsSelectTheirRealBinary() {
        val directory = Files.createTempDirectory("tor-bridge-test").toFile()
        try {
            java.io.File(directory, "liblyrebird.so").createNewFile()
            java.io.File(directory, "libconjure.so").createNewFile()
            TorBridgeConfig.modes.filter { it != "none" }.forEach { mode ->
                val result = TorBridgeConfig.build(mode, listOf("$mode 192.0.2.1:443 fingerprint"), directory, false)
                assertTrue(result.contains("UseBridges 1"))
                assertTrue(result.contains("ClientTransportPlugin $mode exec"))
                assertTrue(result.contains("Bridge $mode 192.0.2.1:443 fingerprint"))
                assertTrue(result.contains(if (mode == "conjure") "libconjure.so" else "liblyrebird.so"))
            }
            assertTrue(runCatching { TorBridgeConfig.build("conjure", listOf("conjure 192.0.2.1:443"), directory, true) }.isFailure)
            assertTrue(runCatching { TorBridgeConfig.build("obfs4", listOf("obfs4 192.0.2.1:443\nSocksPort 0.0.0.0:9999"), directory, false) }.isFailure)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
