package com.theplugos.local;
import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Fail closed if state cannot be read or committed; never regenerate a pending command. */
final class LocalState {
    private final AtomicFile file; private final Vault vault;
    LocalState(Context c,Vault v) { file=new AtomicFile(new File(c.getFilesDir(),"terminal.enc")); vault=v; }
    synchronized JSONObject read() {
        try { return Json.obj(vault.open(new String(file.readFully(),StandardCharsets.UTF_8))); }
        catch(FileNotFoundException e) { if(!file.getBaseFile().exists()&&!new File(file.getBaseFile().getPath()+".bak").exists())return Json.o();throw new IllegalStateException("Saved device state cannot be opened. Do not reset this device.",e); }
        catch(Exception e) { throw new IllegalStateException("Saved device state cannot be opened. Do not reset this device.",e); }
    }
    synchronized void put(String key,Object value) {
        JSONObject state=read(); Json.put(state,key,value); FileOutputStream out=null;
        try { out=file.startWrite(); out.write(vault.seal(state.toString()).getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch(Exception e) { if(out!=null) file.failWrite(out); throw new IllegalStateException("Unable to save input",e); }
    }
}
