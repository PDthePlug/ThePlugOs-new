package com.theplugos.local;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.text.*;
import android.view.*;
import android.widget.*;
import android.media.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;

public final class MainActivity extends Activity {
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private LocalState state; private Crypto crypto;
    private JSONObject config; private volatile String session="";
    private String staffId="",role=""; private boolean foreground;
    private LinearLayout page,orders,top; private TextView connection,feedback,pendingView;
    private EditText note; private JSONArray cart=new JSONArray();
    private ScheduledFuture<?> polling; private long lastSeen=0; private final Set<String> announced=new HashSet<>();
    private static final int INK=Color.rgb(22,41,54),ACCENT=Color.rgb(0,96,108),PAPER=Color.rgb(246,247,242);
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try { crypto=new Crypto(); state=new LocalState(this,crypto); config=state.read().optJSONObject("config"); }
        catch(Exception e) { fatal(e.getMessage()); return; }
        if(Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},5);
        if(config==null) setup(); else { if(config.optBoolean("isHub")) startForegroundService(new android.content.Intent(this,HubService.class)); loginScreen(); }
    }
    @Override protected void onResume(){super.onResume();foreground=true;}
    @Override protected void onPause(){
        super.onPause(); foreground=false; if(polling!=null) polling.cancel(false);
        String old=session; session="";
        if(!old.isEmpty()&&config!=null) io.execute(()->{try{new ApiClient(config).post("/v1/logout",old,Json.o());}catch(Exception ignored){}});
        if(config!=null) loginScreen();
    }
    @Override protected void onDestroy(){if(polling!=null)polling.cancel(false);io.shutdown();super.onDestroy();}
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(14),dp(8),dp(14),dp(8));return l;}
    private void shell(String title){
        page=column();page.setBackgroundColor(PAPER);
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(14)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(14)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;});
        setContentView(page);page.requestApplyInsets();
        label(page,"THEPLUGOS  /  TRAINING PILOT",12,ACCENT);label(page,title,26,INK);
        label(page,"Test orders only • No payment or stock effects",14,Color.rgb(139,64,0));
    }
    private TextView label(LinearLayout parent,String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));v.setTextIsSelectable(true);parent.addView(v);return v;}
    private EditText field(LinearLayout parent,String hint,boolean pin){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(18);e.setSingleLine(true);e.setMinHeight(dp(52));if(pin)e.setInputType(18);parent.addView(e);return e;}
    private Button button(LinearLayout parent,String text,Runnable click){Button b=new Button(this);b.setText(text);b.setTextSize(16);b.setAllCaps(false);b.setMinHeight(dp(52));parent.addView(b);b.setOnClickListener(v->click.run());return b;}
    private ScrollView scroll(LinearLayout parent,LinearLayout child){ScrollView s=new ScrollView(this);s.addView(child);parent.addView(s,new LinearLayout.LayoutParams(-1,0,1));return s;}
    private void fatal(String message){shell("Action needed");label(page,message,18,Color.RED);}
    private interface Work {void run()throws Exception;}
    private void background(Work work){io.execute(()->{try{work.run();}catch(Exception e){runOnUiThread(()->message(e.getMessage()));}});}
    private void message(String text){if(feedback!=null)feedback.setText(text);else Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void setup(){
        shell("Set up a test shop");LinearLayout form=column();scroll(page,form);
        label(form,"Use a dedicated training branch. Initialize ONE hub only.",17,INK);
        EditText branch=field(form,"Branch name",false),manager=field(form,"Manager PIN (6–12 digits)",true),cashier=field(form,"Cashier PIN",true),kitchen=field(form,"Kitchen PIN",true);
        button(form,"Initialize this Android as the hub",()->{
            String branchName=branch.getText().toString(),mp=manager.getText().toString(),cp=cashier.getText().toString(),kp=kitchen.getText().toString();
            background(()->{
            Ledger ledger=new Ledger(this,crypto);JSONObject local;
            try{local=ledger.initialize(branchName,mp,cp,kp);}finally{ledger.close();}
            Json.put(local,"host","127.0.0.1");Json.put(local,"fingerprint",Crypto.fingerprint());Json.put(local,"isHub",true);state.put("config",local);config=local;
            runOnUiThread(()->{startForegroundService(new android.content.Intent(this,HubService.class));loginScreen();});
        });});
        label(form,"Or enroll this device with an existing hub",20,INK);
        EditText host=field(form,"Hub IPv4 address (e.g. 192.168.1.10)",false),fingerprint=field(form,"Full hub SHA-256 fingerprint",false),code=field(form,"Single-use pairing code from manager",false);
        button(form,"Verify fingerprint and enroll",()->{
            String h=host.getText().toString().trim(),f=fingerprint.getText().toString().replace(" ","").trim().toLowerCase(Locale.ROOT),c=code.getText().toString().trim();
            background(()->{JSONObject conf=Json.o("host",h,"fingerprint",f);JSONObject paired=new ApiClient(conf).post("/v1/pair","",Json.o("code",c));Json.put(paired,"host",h);Json.put(paired,"fingerprint",f);Json.put(paired,"isHub",false);state.put("config",paired);config=paired;runOnUiThread(this::loginScreen);});
        });
    }
    private void loginScreen(){
        if(polling!=null)polling.cancel(false);shell("Staff sign-in");feedback=label(page,"",16,Color.RED);
        label(page,config.optBoolean("isHub")?"Hub / cashier device":"Enrolled "+config.optString("role")+" device",18,INK);
        EditText staff=field(page,"Staff ID: cashier, kitchen or manager",false),pin=field(page,"Your PIN",true);
        staff.setText(config.optString("role").equals("kitchen")?"kitchen":"cashier");
        button(page,"Sign in",()->{
            String id=staff.getText().toString().trim(),p=pin.getText().toString();pin.setText("");
            background(()->{JSONObject result=new ApiClient(config).post("/v1/login","",Json.o("staffId",id,"pin",p));
                runOnUiThread(()->{if(!foreground)return;session=Json.text(result,"session");role=Json.text(result,"role");staffId=id;workspace();});});
        });
        if(!config.optBoolean("isHub")) {
            EditText host=field(page,"Update hub address after router change",false);host.setText(config.optString("host"));
            button(page,"Save hub address (keep fingerprint)",()->{Json.put(config,"host",host.getText().toString().trim());state.put("config",config);message("Address saved; sign in again");});
        }
    }
    private void workspace(){
        note=null;shell(role.equals("kitchen")?"Kitchen queue":role.equals("manager")?"Manager · local pilot":"Cashier");
        connection=label(page,"Connecting to hub…",15,ACCENT);feedback=label(page,"",15,Color.RED);pendingView=label(page,"",16,Color.rgb(139,64,0));
        button(page,"Lock / change staff",()->{String old=session;session="";background(()->new ApiClient(config).post("/v1/logout",old,Json.o()));loginScreen();});
        LinearLayout body=column();scroll(page,body);
        if(role.equals("manager")) manager(body);
        else if(role.equals("cashier")) cashier(body);
        label(body,role.equals("kitchen")?"Unfinished orders":"Orders · newest first",21,INK);orders=column();body.addView(orders);
        JSONObject cached=state.read().optJSONObject("snapshot");if(cached!=null){renderOrders(cached);connection.setText("Saved view from "+new java.util.Date(cached.optLong("serverTime"))+" — waiting for hub confirmation");}
        renderPending();polling=io.scheduleWithFixedDelay(this::poll,0,750,TimeUnit.MILLISECONDS);
    }
    private void manager(LinearLayout body){
        label(body,"Hub device setup",21,INK);label(body,HubService.status,16,INK);
        label(body,"Hub addresses: "+addresses(),16,INK);label(body,"SHA-256: "+config.optString("fingerprint"),15,INK);
        for(String r:new String[]{"kitchen","cashier"}) button(body,"Create "+r+" enrollment code",()->{String s=session;background(()->{JSONObject result=new ApiClient(config).post("/v1/pair-code",s,Json.o("role",r));runOnUiThread(()->new AlertDialog.Builder(this).setTitle(r+" code · expires in 5 minutes").setMessage(result.optString("code")+"\n\nCompare full hub fingerprint before enrollment.").setPositiveButton("Close",null).show());});});
        label(body,"Manager controls here are limited to enrollment and cancelling received orders. Shifts, cash and stock follow in M4.",16,INK);
    }
    private String addresses(){List<String> ips=new ArrayList<>();try{Enumeration<NetworkInterface> ns=NetworkInterface.getNetworkInterfaces();while(ns.hasMoreElements()){Enumeration<InetAddress> as=ns.nextElement().getInetAddresses();while(as.hasMoreElements()){InetAddress a=as.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress())ips.add(a.getHostAddress());}}}catch(Exception ignored){}return String.join(", ",ips);}
    private void cashier(LinearLayout body){
        JSONObject draft=state.read().optJSONObject("draft");cart=draft==null?new JSONArray():Json.arr(draft,"items");
        TextView cartView=label(body,"",18,INK);
        Spinner modifier=new Spinner(this);modifier.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Standard","No salt","Extra crispy"}));body.addView(modifier);
        Runnable refresh=()->{StringBuilder s=new StringBuilder();long total=0;for(int i=0;i<cart.length();i++){JSONObject line=Json.at(cart,i),p=Menu.product(Json.text(line,"productId"));s.append(Json.num(line,"quantity")).append(" × ").append(Json.text(p,"name")).append(" · ").append(Json.text(line,"modifier")).append('\n');total+=Json.num(line,"quantity")*Json.num(p,"unitCents");}cartView.setText((s.length()==0?"Cart empty\n":s.toString())+"Order total: "+money(total));};
        JSONArray menu=Menu.all();for(int i=0;i<menu.length();i++){JSONObject p=Json.at(menu,i);button(body,Json.text(p,"category")+" · "+Json.text(p,"name")+" · "+money(Json.num(p,"unitCents")),()->{if(hasPending()){message("Resolve the pending command first");return;}cart.put(Json.o("productId",Json.text(p,"id"),"quantity",1,"modifier",modifier.getSelectedItem().toString()));saveDraft();refresh.run();});}
        note=field(body,"Preparation notes",false);note.setText(draft==null?"":draft.optString("note"));note.setEnabled(!hasPending());note.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){saveDraft();}public void afterTextChanged(Editable e){}});
        button(body,"Remove last item",()->{if(hasPending()){message("Resolve the pending command first");return;}if(cart.length()>0)cart.remove(cart.length()-1);saveDraft();refresh.run();});
        button(body,"Send order to kitchen",()->{
            if(cart.length()==0){message("Add an item first");return;}
            send(Json.o("commandId",Ledger.uuid(),"type","submit","orderId",Ledger.uuid(),"items",cart,"note",note.getText().toString()));
        });refresh.run();
    }
    private void saveDraft(){state.put("draft",Json.o("items",cart,"note",note==null?"":note.getText().toString()));}
    private boolean hasPending(){return !state.read().optString("pending","").isEmpty();}
    private void renderPending(){
        String p=state.read().optString("pending","");pendingView.setText(p.isEmpty()?"":"UNCONFIRMED COMMAND — keep it and retry. Do not re-enter this order.");
        if(!p.isEmpty()) { Button retry=button(orders,"Retry saved command",this::retry);retry.setTag("pending"); }
    }
    private void send(JSONObject cmd){
        if(hasPending()){message("A command is awaiting confirmation. Retry it first.");return;}
        state.put("pendingStaff",staffId);state.put("pending",cmd.toString());if(note!=null)note.setEnabled(false);renderPending();retry();
    }
    private void retry(){
        JSONObject local=state.read();String pending=local.optString("pending","");if(pending.isEmpty())return;
        if(!local.optString("pendingStaff").equals(staffId)){message("Sign in as "+local.optString("pendingStaff")+" to retry this command");return;}
        String s=session;background(()->{
            try {
                JSONObject accepted=new ApiClient(config).raw("/v1/commands",s,pending);
                if(Json.text(Json.obj(pending),"type").equals("submit"))state.put("draft",Json.o("items",new JSONArray(),"note",""));
                state.put("pending","");
                runOnUiThread(()->{if(!s.equals(session))return;workspace();message("Saved by hub · order "+accepted.optString("number"));});
            }catch(Fault e){
                if(e.status==400||e.status==403||e.status==404||e.status==409){
                    // Explicit hub rejection is different from a lost reply; preserve the original draft.
                    state.put("pending","");runOnUiThread(()->{renderPending();if(note!=null)note.setEnabled(true);message("Hub rejected action: "+e.getMessage()+". Review the current order before a new action.");});
                }else throw e;
            }
        });
    }
    private void poll(){
        String s=session;if(s.isEmpty()||!foreground)return;
        try{
            ApiClient api=new ApiClient(config);JSONObject snapshot=api.post("/v1/snapshot",s,Json.o());
            if(!Json.text(snapshot,"branchId").equals(Json.text(config,"branchId")))throw new Fault(403,"Hub branch does not match enrolled branch");
            state.put("snapshot",snapshot); // Commit locally BEFORE announcing device delivery.
            if(role.equals("kitchen")){JSONArray ids=new JSONArray(),list=Json.arr(snapshot,"orders");for(int i=0;i<list.length();i++)ids.put(Json.text(Json.at(list,i),"id"));api.post("/v1/delivered",s,Json.o("orderIds",ids));}
            lastSeen=System.currentTimeMillis();
            runOnUiThread(()->{if(!s.equals(session))return;connection.setText("Hub connected · Kitchen "+(snapshot.optBoolean("kitchenConnected")?"connected":"not reachable")+"\nCloud not configured · "+snapshot.optLong("pendingCloudEvents")+" events waiting");connection.setTextColor(ACCENT);renderOrders(snapshot);});
        }catch(Exception e){runOnUiThread(()->{if(!s.equals(session))return;connection.setText("HUB UNREACHABLE / NOT AUTHORIZED — saved view is stale\n"+e.getMessage());connection.setTextColor(Color.RED);if(e instanceof Fault&&((Fault)e).status==401){session="";loginScreen();message("Session ended. Sign in again; saved input is retained.");}});}
    }
    private void renderOrders(JSONObject snapshot){
        orders.removeAllViews();JSONArray list=Json.arr(snapshot,"orders");int visible=0;
        for(int i=0;i<list.length();i++){
            JSONObject o=Json.at(list,i);String status=Json.text(o,"state");
            if(role.equals("kitchen")&&(status.equals("Collected")||status.equals("Cancelled")))continue;
            visible++;LinearLayout card=column();card.setBackgroundColor(status.equals("Ready")?Color.rgb(211,244,215):Color.WHITE);orders.addView(card,new LinearLayout.LayoutParams(-1,-2));
            long minutes=Math.max(0,(System.currentTimeMillis()-o.optLong("createdAt"))/60000);
            label(card,"#"+o.optString("number")+" · "+status+" · "+minutes+" min",22,status.equals("Ready")?Color.rgb(0,96,30):INK);
            label(card,"Saved by hub · "+(o.optBoolean("deliveredToKitchen")?"Delivered to kitchen device":"Kitchen delivery pending")+"\n"+(o.optLong("acknowledgedAt")>0?"Acknowledged by kitchen staff":"Staff acknowledgement pending"),14,INK);
            JSONArray items=Json.arr(o,"items");for(int n=0;n<items.length();n++){JSONObject line=Json.at(items,n);label(card,line.optLong("quantity")+" × "+line.optString("name")+" · "+line.optString("modifier"),19,INK);}
            if(!o.optString("note").isEmpty())label(card,"NOTE: "+o.optString("note"),18,INK);
            if(!role.equals("kitchen"))label(card,money(o.optLong("totalCents"))+" · Training / unpaid",16,INK);
            String action=null,text=null;
            if(role.equals("kitchen")){
                if(status.equals("Received")){action=o.optLong("acknowledgedAt")==0?"acknowledge":"prepare";text=o.optLong("acknowledgedAt")==0?"Acknowledge order":"Start preparing";}
                else if(status.equals("Preparing")){action="ready";text="Food ready";}
            }else if(status.equals("Ready")){action="collect";text="Confirm customer collected";}
            if(action!=null){String a=action;button(card,text,()->send(Json.o("commandId",Ledger.uuid(),"type",a,"orderId",o.optString("id"),"expectedVersion",o.optLong("version"))));}
            if(role.equals("manager")&&status.equals("Received"))button(card,"Cancel received order",()->{
                EditText reason=new EditText(this);reason.setHint("Reason required");new AlertDialog.Builder(this).setTitle("Cancel #"+o.optString("number")).setView(reason).setNegativeButton("Keep order",null).setPositiveButton("Cancel order",(d,w)->send(Json.o("commandId",Ledger.uuid(),"type","cancel","orderId",o.optString("id"),"expectedVersion",o.optLong("version"),"reason",reason.getText().toString()))).show();
            });
            String alertKey=o.optString("id")+":"+(role.equals("kitchen")?"received":"ready");
            if((role.equals("kitchen")&&status.equals("Received")&&o.optLong("acknowledgedAt")==0)||(!role.equals("kitchen")&&status.equals("Ready"))){if(announced.add(alertKey))tone();}
        }
        if(visible==0)label(orders,"No orders yet",18,INK);renderPending();
    }
    private void tone(){try{ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_NOTIFICATION,80);tone.startTone(ToneGenerator.TONE_PROP_ACK,180);new Handler(getMainLooper()).postDelayed(tone::release,350);}catch(Exception ignored){}}
    private static String money(long cents){return String.format(Locale.ROOT,"R %d.%02d",cents/100,cents%100);}
}
