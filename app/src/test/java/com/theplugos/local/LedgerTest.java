package com.theplugos.local;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.json.*;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class LedgerTest {
    Context context; Ledger hub; Vault vault; String managerToken,managerSession,cashToken,cashSession,kitchenToken,kitchenSession;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); context.deleteDatabase("branch.db");
        SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");vault=new Vault(){public String seal(String s){return Crypto.encrypt(key,s);}public String open(String s){return Crypto.decrypt(key,s);}};
        hub=new Ledger(context,vault);JSONObject initial=hub.initialize("Training shop","987654","123456","654321");managerToken=initial.optString("deviceToken");managerSession=hub.login(managerToken,"manager","987654").optString("session");
        cashToken=hub.pair(hub.issuePairCode(managerToken,managerSession,"cashier")).optString("deviceToken");cashSession=hub.login(cashToken,"cashier","123456").optString("session");
        kitchenToken=hub.pair(hub.issuePairCode(managerToken,managerSession,"kitchen")).optString("deviceToken");kitchenSession=hub.login(kitchenToken,"kitchen","654321").optString("session");
    }
    @After public void close(){hub.close();}
    JSONObject submit(){return Json.o("commandId",Ledger.uuid(),"type","submit","orderId",Ledger.uuid(),"items",new JSONArray().put(Json.o("productId","chips","quantity",2,"modifier","No salt")).put(Json.o("productId","burger","quantity",1,"modifier","Standard")),"note","No chilli for child");}
    JSONObject order(){return Json.at(Json.arr(hub.snapshot(cashToken,cashSession),"orders"),0);}
    JSONObject next(JSONObject o,String type){return Json.o("commandId",Ledger.uuid(),"type",type,"orderId",o.optString("id"),"expectedVersion",o.optLong("version"));}
    JSONObject kitchen(JSONObject cmd){return hub.command(kitchenToken,kitchenSession,cmd.toString());}
    JSONObject create(){return hub.command(cashToken,cashSession,submit().toString());}
    void delivery(JSONObject o){hub.delivered(kitchenToken,kitchenSession,new JSONArray().put(o.optString("id")));}
    void fault(int status,Runnable r){try{r.run();fail("Expected rejection "+status);}catch(Fault f){assertEquals(status,f.status);}}
    @Test public void fullOfflineLoopSeparatesDeliveryAcknowledgementAndCollection(){
        JSONObject received=create();assertEquals(9500,received.optLong("totalCents"));assertEquals("Received",received.optString("state"));assertFalse(received.optBoolean("deliveredToKitchen"));assertEquals(0,received.optLong("acknowledgedAt"));
        delivery(received);assertTrue(order().optBoolean("deliveredToKitchen"));assertEquals(0,order().optLong("acknowledgedAt"));
        JSONObject ack=kitchen(next(received,"acknowledge"));assertEquals("Received",ack.optString("state"));assertTrue(ack.optLong("acknowledgedAt")>0);
        JSONObject preparing=kitchen(next(ack,"prepare"));JSONObject ready=kitchen(next(preparing,"ready"));assertEquals("Ready",ready.optString("state"));assertEquals("unpaid_training",ready.optString("paymentStatus"));
        JSONObject collected=hub.command(cashToken,cashSession,next(ready,"collect").toString());assertEquals("Collected",collected.optString("state"));assertEquals(1,hub.count("orders"));assertEquals(5,hub.count("receipts"));assertEquals(hub.count("events"),hub.count("outbox"));
    }
    @Test public void lostSubmitReplyRepeatedTwentyTimesProducesOneOrderAndOneEffect(){JSONObject cmd=submit();String first=hub.command(cashToken,cashSession,cmd.toString()).toString();long events=hub.count("events");for(int i=0;i<20;i++)assertEquals(first,hub.command(cashToken,cashSession,cmd.toString()).toString());assertEquals(1,hub.count("orders"));assertEquals(1,hub.count("receipts"));assertEquals(events,hub.count("events"));}
    @Test public void acceptedOrderSurvivesDatabaseReopenAndRetry(){JSONObject cmd=submit();String first=hub.command(cashToken,cashSession,cmd.toString()).toString();hub.close();hub=new Ledger(context,vault);assertEquals(first,hub.command(cashToken,cashSession,cmd.toString()).toString());assertEquals(1,hub.count("orders"));}
    @Test public void readyRemainsVisibleAcrossRestartsWithoutAlertDelivery(){JSONObject o=create();delivery(o);o=kitchen(next(o,"acknowledge"));o=kitchen(next(o,"prepare"));o=kitchen(next(o,"ready"));hub.close();hub=new Ledger(context,vault);assertEquals("Ready",order().optString("state"));assertEquals(o.optLong("version"),order().optLong("version"));}
    @Test public void allEffectsRollbackWhenReceiptCommitFails(){hub.getWritableDatabase().execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END");long events=hub.count("events");try{create();fail();}catch(android.database.sqlite.SQLiteException expected){}assertEquals(0,hub.count("orders"));assertEquals(0,hub.count("receipts"));assertEquals(events,hub.count("events"));assertEquals(events,hub.count("outbox"));hub.getWritableDatabase().execSQL("DROP TRIGGER fail_receipt");assertEquals("000001",create().optString("number"));}
    @Test public void sameCommandIdWithChangedPayloadIsRejected(){JSONObject cmd=submit();hub.command(cashToken,cashSession,cmd.toString());Json.put(cmd,"note","Changed after timeout");fault(409,()->hub.command(cashToken,cashSession,cmd.toString()));assertEquals(1,hub.count("orders"));}
    @Test public void sameOrderIdDifferentCommandIsRejected(){JSONObject cmd=submit();hub.command(cashToken,cashSession,cmd.toString());Json.put(cmd,"commandId",Ledger.uuid());fault(409,()->hub.command(cashToken,cashSession,cmd.toString()));assertEquals(1,hub.count("orders"));}
    @Test public void kitchenCannotSubmitAndCashierCannotPrepare(){fault(403,()->kitchen(submit()));JSONObject o=create();fault(403,()->hub.command(cashToken,cashSession,next(o,"prepare").toString()));}
    @Test public void staleVersionCannotSkipCurrentWork(){JSONObject o=create();delivery(o);kitchen(next(o,"acknowledge"));fault(409,()->kitchen(next(o,"prepare")));assertEquals("Received",order().optString("state"));}
    @Test public void noPreparingBeforeHumanAckOrAckBeforeDurableDelivery(){JSONObject o=create();fault(409,()->kitchen(next(o,"acknowledge")));delivery(o);fault(409,()->kitchen(next(o,"prepare")));}
    @Test public void noReadyToCollectedShortcut(){JSONObject o=create();fault(409,()->kitchen(next(o,"ready")));fault(409,()->hub.command(cashToken,cashSession,next(o,"collect").toString()));}
    @Test public void duplicateKitchenDeliveryReceiptsAreHarmless(){JSONObject o=create();for(int i=0;i<10;i++)delivery(o);assertEquals(1,hub.count("deliveries"));assertEquals(1,order().optLong("version"));}
    @Test public void humanAckRetryDoesNotRepeatAnEffect(){JSONObject o=create();delivery(o);JSONObject cmd=next(o,"acknowledge");String first=kitchen(cmd).toString();for(int i=0;i<5;i++)assertEquals(first,kitchen(cmd).toString());assertEquals(2,order().optLong("version"));}
    @Test public void cancelRequiresManagerReasonAndNeverErasesHistory(){JSONObject o=create(),cmd=next(o,"cancel");Json.put(cmd,"reason","Customer changed mind");fault(403,()->hub.command(cashToken,cashSession,cmd.toString()));JSONObject cancelled=hub.command(managerToken,managerSession,cmd.toString());assertEquals("Cancelled",cancelled.optString("state"));assertEquals(1,hub.count("orders"));assertEquals("Customer changed mind",cancelled.optString("cancelReason"));}
    @Test public void preparedOrderCannotBeReturnedToStockThroughPilotCancellation(){JSONObject o=create();delivery(o);o=kitchen(next(o,"acknowledge"));o=kitchen(next(o,"prepare"));JSONObject cmd=next(o,"cancel");Json.put(cmd,"reason","Must record actual outcome in M4");fault(409,()->hub.command(managerToken,managerSession,cmd.toString()));}
    @Test public void pinLockSurvivesReopen(){for(int i=0;i<5;i++)fault(401,()->hub.login(cashToken,"cashier","000000"));hub.close();hub=new Ledger(context,vault);fault(429,()->hub.login(cashToken,"cashier","123456"));}
    @Test public void enrollmentCodeSingleUseAndExpiry(){String code=hub.issuePairCode(managerToken,managerSession,"kitchen");hub.pair(code);fault(403,()->hub.pair(code));String expired=hub.issuePairCode(managerToken,managerSession,"kitchen");hub.getWritableDatabase().execSQL("UPDATE pair_codes SET expires=0");fault(403,()->hub.pair(expired));}
    @Test public void revokedDeviceAndOtherDeviceSessionRejected(){fault(401,()->hub.snapshot(kitchenToken,cashSession));hub.getWritableDatabase().execSQL("UPDATE devices SET revoked=1 WHERE token_hash=?",new Object[]{Crypto.hash(cashToken)});fault(401,()->hub.snapshot(cashToken,cashSession));}
    @Test public void unknownProductAndClientPricesCannotChangeHubTotal(){JSONObject cmd=submit();Json.put(Json.at(Json.arr(cmd,"items"),0),"unitCents",1);assertEquals(9500,hub.command(cashToken,cashSession,cmd.toString()).optLong("totalCents"));JSONObject invalid=submit();Json.put(Json.at(Json.arr(invalid,"items"),0),"productId","injected");fault(400,()->hub.command(cashToken,cashSession,invalid.toString()));}
    @Test public void sensitiveOrderAndReceiptsEncryptedAtRest(){create();assertFalse(hub.value("SELECT payload FROM orders").contains("No chilli"));assertFalse(hub.value("SELECT result FROM receipts").contains("Burger"));assertFalse(hub.value("SELECT secret FROM staff LIMIT 1").contains("hash"));}
    @Test public void concurrentRequestsStillHaveSingleReceipt()throws Exception{JSONObject cmd=submit();ExecutorService pool=Executors.newFixedThreadPool(6);try{List<Future<String>> results=new ArrayList<>();for(int i=0;i<12;i++)results.add(pool.submit(()->hub.command(cashToken,cashSession,cmd.toString()).optString("id")));String id=results.get(0).get();for(Future<String> r:results)assertEquals(id,r.get());assertEquals(1,hub.count("orders"));}finally{pool.shutdownNow();}}
    @Test public void expiredSessionsCannotOperateAndBranchHasNoKitchenConnectionUntilPoll(){assertFalse(hub.snapshot(cashToken,cashSession).optBoolean("kitchenConnected"));hub.snapshot(kitchenToken,kitchenSession);assertTrue(hub.snapshot(cashToken,cashSession).optBoolean("kitchenConnected"));hub.getWritableDatabase().execSQL("UPDATE sessions SET expires=0");fault(401,()->create());}
    @Test public void clientDraftAndStablePendingSurviveAtomicStateReopen(){LocalState local=new LocalState(context,vault);String cmd=submit().toString();local.put("pending",cmd);local.put("draft",Json.o("items",new JSONArray(),"note","unfinished"));LocalState reopened=new LocalState(context,vault);assertEquals(cmd,reopened.read().optString("pending"));assertEquals("unfinished",reopened.read().optJSONObject("draft").optString("note"));local.put("pending","");}
    @Test public void authenticatedEncryptionRejectsModifiedCiphertext(){String secret=vault.seal("order notes");String[] parts=secret.split("\\.");byte[] bytes=android.util.Base64.decode(parts[1],0);bytes[0]^=1;try{vault.open(parts[0]+"."+Crypto.b64(bytes));fail();}catch(IllegalStateException expected){}}
    @Test public void separateBranchDoesNotAcceptAnotherBranchDeviceOrReadItsOrders(){
        create();context.deleteDatabase("other-branch.db");Ledger other=new Ledger(context,vault,"other-branch.db");
        try{JSONObject local=other.initialize("Other training business","987654","123456","654321");String token=local.optString("deviceToken"),session=other.login(token,"manager","987654").optString("session");assertEquals(0,Json.arr(other.snapshot(token,session),"orders").length());fault(401,()->other.snapshot(cashToken,cashSession));fault(401,()->hub.snapshot(token,session));assertNotEquals(Json.text(local,"branchId"),Json.text(hub.snapshot(cashToken,cashSession),"branchId"));}finally{other.close();}
    }
    @Test public void interruptedAtomicFileRenameRecoversPendingInsteadOfResetting(){
        LocalState local=new LocalState(context,vault);String pending=submit().toString();local.put("pending",pending);
        java.io.File base=new java.io.File(context.getFilesDir(),"terminal.enc"),backup=new java.io.File(context.getFilesDir(),"terminal.enc.bak");
        assertTrue(base.renameTo(backup));assertEquals(pending,new LocalState(context,vault).read().optString("pending"));local.put("pending","");
    }
}
