package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import java.net.URI
import java.net.URLDecoder

object PsiphonFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        try {
            if (!str.startsWith("psiphon://")) {
                return null
            }
            
            // Extract query parameters using split
            val queryStart = str.indexOf('?')
            if (queryStart < 0) return null
            
            val query = str.substring(queryStart + 1).substringBefore('#')
            val fragment = str.substringAfter('#', "").let { 
                if (it.isNotEmpty()) URLDecoder.decode(it, "UTF-8") else "Psiphon"
            }
            
            val params = query.split('&').associate {
                val parts = it.split('=', limit = 2)
                val key = parts.first()
                val value = if (parts.size > 1) URLDecoder.decode(parts.last(), "UTF-8") else ""
                key to value
            }

            // Save parsed keys to MMKV
            val region = params["region"] ?: "Auto"
            val protocol = params["protocol"] ?: "cdn_fronting"
            val aggressive = params["aggressive"] ?: "ON"
            val cdnType = params["cdn_type"] ?: "akamai"
            val cdnSni = params["cdn_sni"] ?: params["sni_server"] ?: ""
            val noSni = params["no_sni"] == "true"
            val skipCert = params["skip_cert"] == "true"
            
            val protoTls = params["proto_tls"] == "true" || params["cdn_protocols"]?.contains("tls") == true
            val protoHttp = params["proto_http"] == "true" || params["cdn_protocols"]?.contains("http") == true
            val protoQuic = params["proto_quic"] == "true" || params["cdn_protocols"]?.contains("quic") == true
            
            val builtin = params["builtin"] == "true" || params["add_builtin"] == "true"

            // Save to MMKV
            MmkvManager.encodeSettings("pref_psiphon_region", region)
            MmkvManager.encodeSettings("pref_psiphon_protocol", protocol)
            MmkvManager.encodeSettings("pref_psiphon_aggressive", aggressive)
            MmkvManager.encodeSettings("pref_psiphon_cdn_type", cdnType)
            MmkvManager.encodeSettings("pref_psiphon_cdn_sni", cdnSni)
            MmkvManager.encodeSettings("pref_psiphon_sni_server", cdnSni)
            MmkvManager.encodeSettings("pref_psiphon_no_sni", noSni)
            MmkvManager.encodeSettings("pref_psiphon_skip_cert", skipCert)
            
            MmkvManager.encodeSettings("pref_psiphon_proto_tls", protoTls)
            MmkvManager.encodeSettings("pref_psiphon_proto_http", protoHttp)
            MmkvManager.encodeSettings("pref_psiphon_proto_quic", protoQuic)
            
            val cdnProtocols = mutableListOf<String>()
            if (protoTls) cdnProtocols.add("tls")
            if (protoHttp) cdnProtocols.add("http")
            if (protoQuic) cdnProtocols.add("quic")
            MmkvManager.encodeSettings("pref_psiphon_cdn_protocols", cdnProtocols.joinToString(","))
            
            MmkvManager.encodeSettings("pref_psiphon_builtin", builtin)
            MmkvManager.encodeSettings("pref_psiphon_add_builtin", builtin)
            
            // Enable psiphonOnly mode automatically when importing a Psiphon config
            MmkvManager.encodeSettings("pref_psiphon_only", true)
            MmkvManager.encodeSettings("pref_psiphon_after", false)

            // Ensure dummy profile is created and returned
            val dummyGuid = MmkvManager.ensurePsiphonDummyProfile()
            val config = MmkvManager.decodeServerConfig(dummyGuid) ?: return null
            config.remarks = fragment
            return config
        } catch (e: Exception) {
            return null
        }
    }

    fun toUri(config: ProfileItem): String {
        val region = MmkvManager.decodeSettingsString("pref_psiphon_region") ?: "Auto"
        val protocol = MmkvManager.decodeSettingsString("pref_psiphon_protocol") ?: "cdn_fronting"
        val aggressive = MmkvManager.decodeSettingsString("pref_psiphon_aggressive") ?: "ON"
        val cdnType = MmkvManager.decodeSettingsString("pref_psiphon_cdn_type") ?: "akamai"
        val cdnSni = MmkvManager.decodeSettingsString("pref_psiphon_cdn_sni") ?: MmkvManager.decodeSettingsString("pref_psiphon_sni_server") ?: ""
        val noSni = MmkvManager.decodeSettingsBool("pref_psiphon_no_sni", false)
        val skipCert = MmkvManager.decodeSettingsBool("pref_psiphon_skip_cert", false)
        
        val protoTls = MmkvManager.decodeSettingsBool("pref_psiphon_proto_tls", true)
        val protoHttp = MmkvManager.decodeSettingsBool("pref_psiphon_proto_http", true)
        val protoQuic = MmkvManager.decodeSettingsBool("pref_psiphon_proto_quic", true)
        
        val builtin = MmkvManager.decodeSettingsBool("pref_psiphon_builtin", true)

        return "psiphon://?region=$region&protocol=$protocol&aggressive=$aggressive" +
                "&cdn_type=$cdnType&cdn_sni=${java.net.URLEncoder.encode(cdnSni, "UTF-8")}" +
                "&no_sni=$noSni&skip_cert=$skipCert" +
                "&proto_tls=$protoTls&proto_http=$protoHttp&proto_quic=$protoQuic" +
                "&builtin=$builtin#${java.net.URLEncoder.encode(config.remarks, "UTF-8")}"
    }
}
