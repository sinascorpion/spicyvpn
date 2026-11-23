import java.net.URI;

public class TestURI {
    public static void main(String[] args) {
        String[] urls = {
            "vless://2ff7eec6-da48-46ab-b770-5c83400a528c@104.17.170.132:8880?&security=none&type=ws&headerType=&host=icy-glade-ad0f.412.workers.dev&path=%2Fpyip%3DProxyIP.KR.CMLiussss.net"
        };

        for (String str : urls) {
            try {
                String fixed = str.replace(" ", "%20").replace("|", "%7C");
                URI uri = new URI(fixed);
                System.out.println("Success: " + uri.getHost() + " / " + uri.getRawFragment());
            } catch (Exception e) {
                System.out.println("Fail: " + str + " -> " + e.getMessage());
            }
        }
    }
}
