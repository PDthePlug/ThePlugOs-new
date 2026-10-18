package com.theplugos.local;
import org.json.*;
final class Menu {
    static JSONArray all() { return new JSONArray().put(Json.o("id","chips","name","Chips","category","Sides","unitCents",2500)).put(Json.o("id","burger","name","Burger","category","Meals","unitCents",4500)).put(Json.o("id","drink","name","Bottled drink","category","Drinks","unitCents",1500)); }
    static JSONObject product(String id) { JSONArray list=all(); for(int i=0;i<list.length();i++) if(Json.text(Json.at(list,i),"id").equals(id)) return Json.at(list,i); throw new Fault(400,"Unknown product"); }
}
