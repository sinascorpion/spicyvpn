package com.v2ray.ang.dto

data class IPAPIInfo(
    @com.google.gson.annotations.SerializedName(value = "ip", alternate = ["IP"])
    var ip: String? = null,          // ipinfo.io, ip-api.com, Tor check
    var clientIp: String? = null,     // speed.cloudflare.com
    var ip_addr: String? = null,      // some other APIs
    var query: String? = null,        // ip-api.com
    var country: String? = null,      // ipinfo.io (2-letter code), ip-api.com (full name)
    var country_name: String? = null,
    var country_code: String? = null, // ip-api.com
    var countryCode: String? = null,
    var location: LocationBean? = null
) {
    companion object {
        fun fromTrace(content: String): IPAPIInfo? {
            val fields = content.lineSequence().mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trim()
            }.toMap()
            val address = fields["ip"]?.takeIf { it.isNotBlank() } ?: return null
            val country = fields["loc"]?.takeIf { it.matches(Regex("[A-Z]{2}")) }
            return IPAPIInfo(ip = address, country_code = country)
        }
    }

    data class LocationBean(
        var country_code: String? = null
    )

    /** Returns the best available 2-letter ISO country code. */
    fun resolveCountryCode(): String? = listOf(
        country_code, countryCode, location?.country_code,
        // 'country' from ipinfo.io is already a 2-letter code
        country?.takeIf { it.length == 2 }
    ).firstOrNull { !it.isNullOrBlank() }

    /** Returns the best available IP address. */
    fun resolveIp(): String? = listOf(ip, clientIp, ip_addr, query)
        .firstOrNull { !it.isNullOrBlank() }
}
