package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import java.net.URI

object Ikev2Fmt : FmtBase() {
    fun parse(text: String): ProfileItem {
        val uri = URI(Utils.fixIllegalUrl(text.trim()))
        require(uri.scheme == "ikev2" && !uri.host.isNullOrBlank()) { "Invalid IKEv2 host" }
        require(uri.port == -1 || uri.port == 500) { "IKEv2 uses UDP 500/4500" }
        val credentials = uri.rawUserInfo?.split(':', limit = 2).orEmpty()
        val query = getQueryParam(uri)
        return ProfileItem.create(EConfigType.IKEV2).apply {
            server = uri.host
            username = credentials.getOrNull(0)?.let(Utils::decodeURIComponent)
            password = credentials.getOrNull(1)?.let(Utils::decodeURIComponent)
            require(!username.isNullOrBlank() && !password.isNullOrBlank()) { "IKEv2 username and password are required" }
            sni = query["identity"] ?: username
            remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifBlank { "IKEv2" }
        }
    }
    fun toUri(profile: ProfileItem): String = Utils.encodeURIComponent(profile.username.orEmpty()) + ":" +
        Utils.encodeURIComponent(profile.password.orEmpty()) + "@" + profile.server.orEmpty().let { if (it.contains(':')) "[$it]" else it } +
        "?identity=" + Utils.encodeURIComponent(profile.sni ?: profile.username.orEmpty()) + "#" + Utils.encodeURIComponent(profile.remarks)
}
