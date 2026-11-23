import java.net.URI

val urls = listOf(
    "vless://6cd78ec7-b715-471c-92b4-0c3842caea4b@104.25.122.72:80?security=none&type=ws&path=/eyJqdW5rIjoiVXRIT2UwaDUiLCJwcm90b2NvbCI6InZsIiwibW9kZSI6InByZWZpeCIsInBhbmVsSVBzIjpbIlsyNjAyOmZjNTk6YjA6NjQ6Ol0iXX0=&host=billowing-cloud-d339.sospanel.workers.dev&ed=2560&packetEncoding=xudp&encryption=none# By EbraSha ",
    "vless://1a5e2e63-64e2-4737-95b6-c2ee60c979a1@162.159.21.178:8880?type=ws&host=wild-mode-4ca6.234-8f6.workers.dev&path=/pyip=ProxyIP.JP.CMLiussss.net# By EbraSha ",
    "vless://2ff7eec6-da48-46ab-b770-5c83400a528c@104.17.170.132:8880?&security=none&type=ws&headerType=&host=icy-glade-ad0f.412.workers.dev&path=%2Fpyip%3DProxyIP.KR.CMLiussss.net# By EbraSha "
)

urls.forEach { str ->
    try {
        val fixed = str.replace(" ", "%20").replace("|", "%7C")
        val uri = URI(fixed)
        println("Success: ${uri.host}")
    } catch (e: Exception) {
        println("Fail: $str -> ${e.message}")
    }
}
