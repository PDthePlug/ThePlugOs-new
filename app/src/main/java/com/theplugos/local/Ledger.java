package com.theplugos.local;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** The only operational writer. Every public operation serializes on this instance. */
final class Ledger extends SQLiteOpenHelper {
    final Vault vault;
    private final Context context;
    Ledger(Context c,Vault v) { this(c,v,"branch.db"); }
    Ledger(Context c,Vault v,String name) { super(c,name,null,1); context=c; vault=v; setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase d) { d.setForeignKeyConstraintsEnabled(true); d.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase d) {
        try (java.io.InputStream source=context.getAssets().open("001_initial.sql")) { String sql=new String(Bytes.read(source,65536),StandardCharsets.UTF_8); for(String s:sql.split(";")) if(!s.trim().isEmpty()) d.execSQL(s); }
        catch(Exception e) { throw new IllegalStateException("Database initialization failed",e); }
    }
    @Override public void onUpgrade(SQLiteDatabase d,int old,int next) { throw new IllegalStateException("Unsupported migration; preserve database and install supported upgrade"); }
    synchronized boolean initialized() { return value("SELECT value FROM meta WHERE key='branch_id'")==null?false:true; }
    String value(String sql,String...args) { try(Cursor c=getWritableDatabase().rawQuery(sql,args)) { return c.moveToFirst()?c.getString(0):null; } }
    long count(String table) { return Long.parseLong(value("SELECT COUNT(*) FROM "+table)); }
    static void require(boolean ok,int status,String message) { if(!ok) throw new Fault(status,message); }
    static String uuid() { return UUID.randomUUID().toString(); }
    static void id(String s) { require(s.matches("[a-zA-Z0-9_-]{1,80}"),400,"Invalid identifier"); }
    static void pinValid(String p) { require(p.matches("[0-9]{6,12}"),400,"Use a PIN with 6 to 12 digits"); }
    synchronized JSONObject initialize(String branch,String managerPin,String cashierPin,String kitchenPin) {
        require(branch.trim().length()>0&&branch.length()<=80,400,"Enter a branch name");
        pinValid(managerPin); pinValid(cashierPin); pinValid(kitchenPin);
        require(!managerPin.equals(cashierPin)&&!managerPin.equals(kitchenPin)&&!cashierPin.equals(kitchenPin),400,"Use different PINs for each role");
        SQLiteDatabase d=getWritableDatabase(); d.beginTransaction();
        try {
            require(!initialized(),409,"Hub already initialized");
            d.execSQL("INSERT INTO meta VALUES('branch_id',?)",new Object[]{uuid()});
            d.execSQL("INSERT INTO meta VALUES('business_id',?)",new Object[]{uuid()});
            d.execSQL("INSERT INTO meta VALUES('authority_epoch','1')");
            d.execSQL("INSERT INTO meta VALUES('branch_name',?)",new Object[]{vault.seal(branch.trim())});
            addStaff(d,"manager","manager",managerPin); addStaff(d,"cashier","cashier",cashierPin); addStaff(d,"kitchen","kitchen",kitchenPin);
            JSONObject local=addDevice(d,"manager"); d.setTransactionSuccessful(); return local;
        } finally { d.endTransaction(); }
    }
    private void addStaff(SQLiteDatabase d,String staff,String role,String pin) {
        String salt=Crypto.random(); d.execSQL("INSERT INTO staff(id,role,secret) VALUES(?,?,?)",new Object[]{staff,role,vault.seal(Json.o("salt",salt,"hash",Crypto.pin(pin,salt)).toString())});
    }
    private JSONObject addDevice(SQLiteDatabase d,String role) {
        String token=Crypto.random(), device=uuid();
        d.execSQL("INSERT INTO devices(id,token_hash,role) VALUES(?,?,?)",new Object[]{device,Crypto.hash(token),role});
        return Json.o("deviceId",device,"deviceToken",token,"role",role,"branchId",value("SELECT value FROM meta WHERE key='branch_id'"));
    }
    synchronized String issuePairCode(String device,String session,String role) {
        Identity who=auth(device,session); require(who.staffRole.equals("manager")&&who.deviceRole.equals("manager"),403,"Manager sign-in on hub required");
        require(role.equals("cashier")||role.equals("kitchen"),400,"Choose cashier or kitchen");
        String code=uuid(); getWritableDatabase().execSQL("DELETE FROM pair_codes WHERE expires < ?",new Object[]{System.currentTimeMillis()});
        getWritableDatabase().execSQL("INSERT INTO pair_codes VALUES(?,?,?)",new Object[]{Crypto.hash(code),role,System.currentTimeMillis()+300000}); return code;
    }
    synchronized JSONObject pair(String code) {
        SQLiteDatabase d=getWritableDatabase(); d.beginTransaction();
        try {
            String role=value("SELECT role FROM pair_codes WHERE hash=? AND expires>?",Crypto.hash(code),""+System.currentTimeMillis());
            require(role!=null,403,"Pairing code expired or already used");
            JSONObject result=addDevice(d,role); d.execSQL("DELETE FROM pair_codes WHERE hash=?",new Object[]{Crypto.hash(code)});
            event(d,null,Json.o("type","device.enrolled","deviceId",Json.text(result,"deviceId"),"role",role)); d.setTransactionSuccessful(); return result;
        } finally { d.endTransaction(); }
    }
    static final class Identity {
        String deviceId,deviceRole,staffId,staffRole;
    }
    private Identity device(String token) {
        Identity w=new Identity();
        try(Cursor c=getWritableDatabase().rawQuery("SELECT id,role FROM devices WHERE token_hash=? AND revoked=0",new String[]{Crypto.hash(token)})) {
            require(c.moveToFirst(),401,"Device is not enrolled or is revoked"); w.deviceId=c.getString(0); w.deviceRole=c.getString(1);
        }
        return w;
    }
    synchronized JSONObject login(String deviceToken,String staffId,String pin) {
        Identity w=device(deviceToken); id(staffId); SQLiteDatabase d=getWritableDatabase(); long now=System.currentTimeMillis();
        try(Cursor c=d.rawQuery("SELECT role,secret,failures,locked_until FROM staff WHERE id=?",new String[]{staffId})) {
            require(c.moveToFirst(),401,"Invalid staff ID or PIN");
            require(c.getLong(3)<=now,429,"PIN locked. Wait five minutes");
            String role=c.getString(0); JSONObject secret=Json.obj(vault.open(c.getString(1)));
            boolean valid=pin.matches("[0-9]{6,12}")&&MessageDigest.isEqual(Crypto.pin(pin,Json.text(secret,"salt")).getBytes(StandardCharsets.UTF_8),Json.text(secret,"hash").getBytes(StandardCharsets.UTF_8));
            if(!valid) {
                int failures=c.getInt(2)+1; d.execSQL("UPDATE staff SET failures=?,locked_until=? WHERE id=?",new Object[]{failures>=5?0:failures,failures>=5?now+300000:0,staffId}); throw new Fault(401,"Invalid staff ID or PIN");
            }
            require(role.equals(w.deviceRole)||(w.deviceRole.equals("manager")&&role.equals("cashier")),403,"Staff role cannot use this device");
            d.execSQL("UPDATE staff SET failures=0,locked_until=0 WHERE id=?",new Object[]{staffId});
            String session=Crypto.random(); d.execSQL("DELETE FROM sessions WHERE expires<? OR (device_id=? AND staff_id=?)",new Object[]{now,w.deviceId,staffId});
            d.execSQL("INSERT INTO sessions VALUES(?,?,?,?)",new Object[]{Crypto.hash(session),w.deviceId,staffId,now+900000});
            return Json.o("session",session,"role",role,"expiresAt",now+900000);
        }
    }
    private Identity auth(String token,String session) {
        Identity w=device(token);
        try(Cursor c=getWritableDatabase().rawQuery("SELECT s.staff_id,t.role FROM sessions s JOIN staff t ON s.staff_id=t.id WHERE s.token_hash=? AND s.device_id=? AND s.expires>?",new String[]{Crypto.hash(session),w.deviceId,""+System.currentTimeMillis()})) {
            require(c.moveToFirst(),401,"Sign in again"); w.staffId=c.getString(0); w.staffRole=c.getString(1);
        }
        return w;
    }
    synchronized void logout(String token,String session) { Identity w=device(token); getWritableDatabase().execSQL("DELETE FROM sessions WHERE token_hash=? AND device_id=?",new Object[]{Crypto.hash(session),w.deviceId}); }
    synchronized JSONObject command(String token,String session,String raw) {
        Identity w=auth(token,session); JSONObject cmd=Json.obj(raw); String commandId=Json.text(cmd,"commandId"),type=Json.text(cmd,"type"); id(commandId);
        SQLiteDatabase d=getWritableDatabase(); d.beginTransaction();
        try {
            try(Cursor c=d.rawQuery("SELECT device_id,staff_id,request_hash,result FROM receipts WHERE id=?",new String[]{commandId})) {
                if(c.moveToFirst()) {
                    require(c.getString(0).equals(w.deviceId)&&c.getString(1).equals(w.staffId)&&c.getString(2).equals(Crypto.hash(raw)),409,"Command ID already belongs to another request");
                    JSONObject prior=Json.obj(vault.open(c.getString(3))); d.setTransactionSuccessful(); return prior;
                }
            }
            JSONObject result;
            if(type.equals("submit")) {
                require(w.staffRole.equals("cashier")||w.staffRole.equals("manager"),403,"Cashier permission required");
                String orderId=Json.text(cmd,"orderId"); id(orderId);
                require(value("SELECT id FROM orders WHERE id=?",orderId)==null,409,"Order ID already exists");
                JSONArray source=Json.arr(cmd,"items"),items=new JSONArray(); require(source.length()>0&&source.length()<=50,400,"Choose 1 to 50 order lines"); long total=0;
                for(int i=0;i<source.length();i++) {
                    JSONObject line=Json.at(source,i); String product=Json.text(line,"productId"),modifier=Json.text(line,"modifier");
                    long qty=Json.num(line,"quantity"); require(qty>0&&qty<=99,400,"Quantity must be 1 to 99");
                    JSONObject p=Menu.product(product); require(modifier.equals("Standard")||modifier.equals("No salt")||modifier.equals("Extra crispy"),400,"Unknown modifier");
                    long cents=Json.num(p,"unitCents"); total=Math.addExact(total,Math.multiplyExact(qty,cents));
                    items.put(Json.o("productId",product,"name",Json.text(p,"name"),"unitCents",cents,"quantity",qty,"modifier",modifier));
                }
                String note=Json.text(cmd,"note"); require(note.length()<=500,400,"Notes are limited to 500 characters");
                long number=Long.parseLong(value("SELECT COALESCE(MAX(number),0)+1 FROM orders")),now=System.currentTimeMillis();
                JSONObject payload=Json.o("items",items,"note",note,"totalCents",total,"currency","ZAR","paymentStatus","unpaid_training","acknowledgedAt",0,"acknowledgedBy","","createdBy",w.staffId);
                d.execSQL("INSERT INTO orders VALUES(?,?,1,'Received',?,?,?)",new Object[]{orderId,number,vault.seal(payload.toString()),now,now});
                result=order(orderId);
            } else {
                String orderId=Json.text(cmd,"orderId"); result=order(orderId); long version=Json.num(cmd,"expectedVersion");
                require(Json.num(result,"version")==version,409,"Order changed. Refresh before trying again");
                String state=Json.text(result,"state"); JSONObject payload=Json.obj(vault.open(value("SELECT payload FROM orders WHERE id=?",orderId)));
                switch(type) {
                    case "acknowledge":
                        require(w.staffRole.equals("kitchen"),403,"Kitchen permission required");
                        require(state.equals("Received")&&Json.num(payload,"acknowledgedAt")==0,409,"Order already acknowledged or no longer received");
                        require(value("SELECT order_id FROM deliveries WHERE order_id=? AND device_id=?",orderId,w.deviceId)!=null,409,"Save the received order on this kitchen first");
                        Json.put(payload,"acknowledgedAt",System.currentTimeMillis()); Json.put(payload,"acknowledgedBy",w.staffId); break;
                    case "prepare":
                        require(w.staffRole.equals("kitchen"),403,"Kitchen permission required"); require(state.equals("Received")&&Json.num(payload,"acknowledgedAt")>0,409,"Acknowledge this received order first"); state="Preparing"; break;
                    case "ready":
                        require(w.staffRole.equals("kitchen"),403,"Kitchen permission required"); require(state.equals("Preparing"),409,"Only preparing orders can become ready"); state="Ready"; break;
                    case "collect":
                        require(w.staffRole.equals("cashier")||w.staffRole.equals("manager"),403,"Cashier permission required"); require(state.equals("Ready"),409,"Only ready orders can be collected"); state="Collected"; break;
                    case "cancel":
                        require(w.staffRole.equals("manager"),403,"Manager permission required"); require(state.equals("Received"),409,"Pilot only cancels unprepared orders");
                        String reason=Json.text(cmd,"reason").trim(); require(reason.length()>0&&reason.length()<=500,400,"A cancellation reason is required"); Json.put(payload,"cancelReason",reason); state="Cancelled"; break;
                    default: throw new Fault(400,"Unknown command");
                }
                d.execSQL("UPDATE orders SET state=?,version=version+1,payload=?,updated_at=? WHERE id=? AND version=?",new Object[]{state,vault.seal(payload.toString()),System.currentTimeMillis(),orderId,version}); result=order(orderId);
            }
            event(d,Json.text(result,"id"),Json.o("type","order."+type,"commandId",commandId,"deviceId",w.deviceId,"staffId",w.staffId,"order",result));
            d.execSQL("INSERT INTO receipts VALUES(?,?,?,?,?)",new Object[]{commandId,w.deviceId,w.staffId,Crypto.hash(raw),vault.seal(result.toString())});
            d.setTransactionSuccessful(); return result;
        } finally { d.endTransaction(); }
    }
    private void event(SQLiteDatabase d,String orderId,JSONObject payload) {
        String eventId=uuid(); d.execSQL("INSERT INTO events(id,order_id,payload,created_at) VALUES(?,?,?,?)",new Object[]{eventId,orderId,vault.seal(payload.toString()),System.currentTimeMillis()});
        d.execSQL("INSERT INTO outbox(event_id) VALUES(?)",new Object[]{eventId});
    }
    private JSONObject order(String orderId) {
        try(Cursor c=getWritableDatabase().rawQuery("SELECT number,version,state,payload,created_at,updated_at FROM orders WHERE id=?",new String[]{orderId})) {
            require(c.moveToFirst(),404,"Order not found"); JSONObject o=Json.obj(vault.open(c.getString(3)));
            Json.put(o,"id",orderId); Json.put(o,"number",String.format(Locale.ROOT,"%06d",c.getLong(0))); Json.put(o,"version",c.getLong(1)); Json.put(o,"state",c.getString(2)); Json.put(o,"createdAt",c.getLong(4)); Json.put(o,"updatedAt",c.getLong(5));
            Json.put(o,"deliveredToKitchen",value("SELECT order_id FROM deliveries WHERE order_id=? LIMIT 1",orderId)!=null); return o;
        }
    }
    synchronized JSONObject snapshot(String token,String session) {
        Identity w=auth(token,session); long now=System.currentTimeMillis();
        getWritableDatabase().execSQL("UPDATE devices SET last_seen=? WHERE id=?",new Object[]{now,w.deviceId}); JSONArray orders=new JSONArray();
        try(Cursor c=getWritableDatabase().rawQuery("SELECT id FROM orders ORDER BY number DESC",null)) { while(c.moveToNext()) orders.put(order(c.getString(0))); }
        String seen=value("SELECT COALESCE(MAX(last_seen),0) FROM devices WHERE role='kitchen' AND revoked=0");
        return Json.o("orders",orders,"sequence",Long.parseLong(value("SELECT COALESCE(MAX(sequence),0) FROM events")),"branchId",value("SELECT value FROM meta WHERE key='branch_id'"),"branchName",vault.open(value("SELECT value FROM meta WHERE key='branch_name'")),"kitchenConnected",now-Long.parseLong(seen)<5000,"pendingCloudEvents",count("outbox"),"cloudState","not_configured","serverTime",now,"menu",Menu.all());
    }
    synchronized void delivered(String token,String session,JSONArray orderIds) {
        Identity w=auth(token,session); require(w.deviceRole.equals("kitchen")&&w.staffRole.equals("kitchen"),403,"Kitchen device required"); require(orderIds.length()<=2000,400,"Too many delivery receipts");
        SQLiteDatabase d=getWritableDatabase(); d.beginTransaction();
        try { for(int i=0;i<orderIds.length();i++) {
            String orderId=orderIds.optString(i,""); require(value("SELECT id FROM orders WHERE id=?",orderId)!=null,404,"Unknown delivered order");
            d.execSQL("INSERT OR IGNORE INTO deliveries VALUES(?,?,?)",new Object[]{orderId,w.deviceId,System.currentTimeMillis()});
        } d.setTransactionSuccessful(); } finally { d.endTransaction(); }
    }
}
