package com.theplugos.local;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.json.*;
import javax.net.ssl.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real loopback HTTPS and independent terminal credentials; no WAN service in the path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TransportTest {
    Ledger ledger; HubServer server;ApiClient manager,cashier,kitchen;String ms,cs,ks,cashierToken;JSONObject base;SSLContext pinned;
    @Before public void setup()throws Exception {
        android.content.Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("transport.db");
        SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");Vault v=new Vault(){public String seal(String s){return Crypto.encrypt(key,s);}public String open(String s){return Crypto.decrypt(key,s);}};
        ledger=new Ledger(c,v,"transport.db");JSONObject local=ledger.initialize("Transport training","987654","123456","654321");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=getClass().getResourceAsStream("/localhost-test.p12")){keys.load(in,"test-only-password".toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-only-password".toCharArray());SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);server=new HubServer(ledger,tls);
        String pin=Crypto.hash(keys.getCertificate("fixture").getEncoded());pinned=Crypto.pinnedTls(pin);base=Json.o("host","127.0.0.1","fingerprint",pin);
        Json.put(local,"host","127.0.0.1");Json.put(local,"fingerprint",pin);manager=new ApiClient(local);try{ms=manager.post("/v1/login","",Json.o("staffId","manager","pin","987654")).optString("session");}catch(Exception e){throw new AssertionError("Server transport: "+server.lastTransportError,server.lastProblem==null?e:server.lastProblem);}
        cashier=pair("cashier");kitchen=pair("kitchen");cs=cashier.post("/v1/login","",Json.o("staffId","cashier","pin","123456")).optString("session");ks=kitchen.post("/v1/login","",Json.o("staffId","kitchen","pin","654321")).optString("session");
    }
    ApiClient pair(String role)throws Exception{String code=manager.post("/v1/pair-code",ms,Json.o("role",role)).optString("code");JSONObject paired=new ApiClient(base).post("/v1/pair","",Json.o("code",code));Json.put(paired,"host","127.0.0.1");Json.put(paired,"fingerprint",base.optString("fingerprint"));if(role.equals("cashier"))cashierToken=paired.optString("deviceToken");return new ApiClient(paired);}
    @After public void close(){if(server!=null)server.close();if(ledger!=null)ledger.close();}
    JSONObject cmd(JSONObject o,String action){return Json.o("commandId",Ledger.uuid(),"type",action,"orderId",o.optString("id"),"expectedVersion",o.optLong("version"));}
    JSONObject submit(){return Json.o("commandId",Ledger.uuid(),"type","submit","orderId",Ledger.uuid(),"items",new JSONArray().put(Json.o("productId","burger","quantity",2,"modifier","Standard")),"note","Real HTTPS test");}
    @Test public void completeOrderCycleOverIndependentHttpsClients()throws Exception {
        JSONObject o=cashier.post("/v1/commands",cs,submit());assertFalse(o.optBoolean("deliveredToKitchen"));
        JSONObject snapshot=kitchen.post("/v1/snapshot",ks,Json.o());assertEquals(1,Json.arr(snapshot,"orders").length());
        kitchen.post("/v1/delivered",ks,Json.o("orderIds",new JSONArray().put(o.optString("id"))));
        o=kitchen.post("/v1/commands",ks,cmd(o,"acknowledge"));o=kitchen.post("/v1/commands",ks,cmd(o,"prepare"));o=kitchen.post("/v1/commands",ks,cmd(o,"ready"));
        assertEquals("Ready",Json.at(Json.arr(cashier.post("/v1/snapshot",cs,Json.o()),"orders"),0).optString("state"));
        o=cashier.post("/v1/commands",cs,cmd(o,"collect"));assertEquals("Collected",o.optString("state"));assertEquals(9000,o.optLong("totalCents"));
    }
    @Test public void wrongCertificatePinCannotEnroll()throws Exception{JSONObject wrong=Json.o("host","127.0.0.1","fingerprint","0000000000000000000000000000000000000000000000000000000000000000");try{new ApiClient(wrong).post("/v1/pair","",Json.o("code","invalid"));fail();}catch(SSLException expected){}}
    @Test public void lostSocketResponseThenRetryCreatesOneOrder()throws Exception {
        JSONObject command=submit();String raw=command.toString();
        // Commit a command while deliberately never reading the response.
        try(SSLSocket socket=(SSLSocket)pinned.getSocketFactory().createSocket("127.0.0.1",8443)){
            byte[] body=raw.getBytes(StandardCharsets.UTF_8);
            String head="POST /v1/commands HTTP/1.1\r\nHost: localhost\r\nX-Device-Token: "+cashierToken+"\r\nX-Staff-Session: "+cs+"\r\nContent-Length: "+body.length+"\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(ledger.count("orders")==0&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(1,ledger.count("orders"));
        }
        JSONObject retried=cashier.raw("/v1/commands",cs,raw);assertEquals(command.optString("orderId"),retried.optString("id"));assertEquals(1,ledger.count("orders"));assertEquals(1,ledger.count("receipts"));

    }
    @Test public void boundedParserRejectsChunkedAndOversizedBodies()throws Exception {
        for(String headers:new String[]{"Transfer-Encoding: chunked\r\nContent-Length: 2","Content-Length: 40000"}){
            try(SSLSocket socket=(SSLSocket)pinned.getSocketFactory().createSocket("127.0.0.1",8443)){
                socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /v1/pair HTTP/1.1\r\nHost: localhost\r\n"+headers+"\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
                String reply=new String(Bytes.read(socket.getInputStream(),4096),StandardCharsets.UTF_8);assertTrue(reply.contains(headers.contains("chunked")?"400 Result":"413 Result"));
            }
        }
    }
}
