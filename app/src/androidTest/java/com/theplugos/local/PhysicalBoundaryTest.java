package com.theplugos.local;

import android.test.AndroidTestCase;
import org.json.JSONObject;

/** Runs on Android, never substitutes for the two-device manual acceptance sheet. */
@SuppressWarnings("deprecation")
public final class PhysicalBoundaryTest extends AndroidTestCase {
    public void testKeystoreEncryptedStorageSurvivesReopen() {
        Crypto first=new Crypto();String sealed=first.seal("Physical Keystore check");
        assertEquals("Physical Keystore check",new Crypto().open(sealed));
    }
    public void testPinnedTlsAcceptsOnlyInitializedHub() throws Exception {
        Crypto c=new Crypto();getContext().deleteDatabase("tls-test.db");Ledger ledger=new Ledger(getContext(),c,"tls-test.db");
        ledger.initialize("TLS instrumentation","987654","123456","654321");
        try(HubServer server=new HubServer(ledger)) {
            JSONObject config=Json.o("host","127.0.0.1","fingerprint",Crypto.fingerprint());
            try { new ApiClient(config).post("/v1/pair","",Json.o("code","invalid"));fail("Invalid code must reject"); }
            catch(Fault expected) { assertEquals(403,expected.status); }
            Json.put(config,"fingerprint","0000000000000000000000000000000000000000000000000000000000000000");
            try { new ApiClient(config).post("/v1/pair","",Json.o("code","invalid"));fail("Wrong pin must reject TLS"); }
            catch(javax.net.ssl.SSLException expected) { /* exact hub pin enforced */ }
        } finally { ledger.close();getContext().deleteDatabase("tls-test.db"); }
    }
}
