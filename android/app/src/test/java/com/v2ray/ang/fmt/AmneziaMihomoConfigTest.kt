package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.*
import org.junit.Test

class AmneziaMihomoConfigTest {
    @Test fun keepsVersion31Options() {
        val profile = ProfileItem.create(EConfigType.AMNEZIAWG).apply {
            server = "127.0.0.1"; serverPort = "51820"
            secretKey = "private"; publicKey = "public"; localAddress = "10.0.0.2/32"
            amneziaOptions = mapOf("version" to "3", "s3" to "50", "h1" to "123-456",
                "random-trailers" to "true", "disable-cookies" to "false", "header-protection-key" to "key")
        }
        val map = MihomoFmt.readConfig(AmneziaMihomoConfig.build(profile))
        val options = ((map["proxies"] as List<*>).first() as Map<*, *>)["amnezia-wg-option"] as Map<*, *>
        assertEquals(3, options["version"])
        assertEquals("123-456", options["h1"])
        assertEquals(true, options["random-trailers"])
        assertEquals("key", options["header-protection-key"])
    }
}
