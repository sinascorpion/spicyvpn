package com.v2ray.ang.fmt

import org.junit.Assert.*
import org.junit.Test

class AetherImportTest {
    @Test fun preservesLinks() {
        val link = "aether://?protocol=gool&scan=balanced&ip=v4&outer=162.159.195.45%3A908&inner=162.159.192.148%3A945#Test"
        assertEquals(link, AetherFmt.parse(link)!!.nativeConfig)
    }
    @Test fun importsAethonAndroidBackup() {
        val link = AetherFmt.normalize("""{"format":"Aethon Backup v1","protocol":2,"goolMode":"masque","transport":1,"scan":1,"ip":0,"h2Fragment":true}""")
        assertTrue(link.contains("protocol=gool"))
        assertTrue(link.contains("h2=1"))
        assertTrue(link.contains("scan=turbo"))
        assertTrue(link.contains("ip=v4"))
        assertTrue(link.contains("fragment=true"))
    }
    @Test fun importsDesktopBasicSettings() {
        assertTrue(AetherFmt.normalize("""{"_format":"aethon-windows-backup","protocol":"masque","transport":"h2"}""").contains("h2=1"))
    }
    @Test fun rejectsUnknownProtocol() {
        assertNull(AetherFmt.parse("""{"protocol":"not-a-protocol"}"""))
    }
}
