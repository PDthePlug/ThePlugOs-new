package com.theplugos.local;

import org.json.*;

final class Json {
    static JSONObject obj(String raw) { try { return new JSONObject(raw); } catch (JSONException e) { throw new Fault(400,"Invalid JSON"); } }
    static JSONObject o(Object... pairs) {
        JSONObject j=new JSONObject();
        try { for(int i=0;i<pairs.length;i+=2) j.put((String)pairs[i], pairs[i+1]); return j; }
        catch(JSONException e) { throw new IllegalStateException(e); }
    }
    static String text(JSONObject j,String k) { try { Object v=j.get(k); if(!(v instanceof String)) throw new Exception(); return (String)v; } catch(Exception e) { throw new Fault(400,"Missing text: "+k); } }
    static long num(JSONObject j,String k) { try { Object v=j.get(k); if(!(v instanceof Number) || ((Number)v).doubleValue()!=((Number)v).longValue()) throw new Exception(); return ((Number)v).longValue(); } catch(Exception e) { throw new Fault(400,"Missing integer: "+k); } }
    static JSONArray arr(JSONObject j,String k) { try { return j.getJSONArray(k); } catch(Exception e) { throw new Fault(400,"Missing list: "+k); } }
    static JSONObject at(JSONArray a,int i) { try { return a.getJSONObject(i); } catch(Exception e) { throw new Fault(400,"Invalid list item"); } }
    static void put(JSONObject j,String k,Object v) { try { j.put(k,v); } catch(Exception e) { throw new IllegalStateException(e); } }
}
class Fault extends RuntimeException {
    final int status;
    Fault(int status,String message) { super(message); this.status=status; }
}
