package com.theplugos.local;
import org.json.*;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
final class ApiClient {
    private final String host,token; private final SSLSocketFactory tls;
    ApiClient(JSONObject config) throws Exception {
        host=Json.text(config,"host"); token=config.optString("deviceToken","");
        Ledger.require(host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"),400,"Enter the hub IPv4 address");
        tls=Crypto.pinnedTls(Json.text(config,"fingerprint")).getSocketFactory();
    }
    JSONObject post(String path,String session,JSONObject body) throws Exception { return raw(path,session,body.toString()); }
    JSONObject raw(String path,String session,String body) throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://"+host+":8443"+path).openConnection();
        c.setSSLSocketFactory(tls);
        // Certificate pinning authenticates this exact hub independent of DHCP address.
        c.setHostnameVerifier((name,ssl)->name.equals(host)); c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(3000); c.setReadTimeout(4000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("X-Device-Token",token); c.setRequestProperty("X-Staff-Session",session);
        byte[] b=body.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(b.length);
        try { try(OutputStream out=c.getOutputStream()) { out.write(b); }
            int code=c.getResponseCode(); InputStream stream=code==200?c.getInputStream():c.getErrorStream();
            if(stream==null) throw new IOException("Hub connection did not return a result");
            JSONObject result; try(stream) { result=Json.obj(new String(Bytes.read(stream,2*1024*1024),StandardCharsets.UTF_8)); }
            if(code!=200) throw new Fault(code,result.optString("error","Request failed")); return result;
        } finally { c.disconnect(); }
    }
}
