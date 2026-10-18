package com.theplugos.local;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.json.*;

final class HubServer implements AutoCloseable {
    private final Ledger ledger;
    private final SSLServerSocket server;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24));
    private volatile boolean running=true;
    volatile String lastTransportError="";
    volatile Throwable lastProblem;
    HubServer(Ledger l) throws Exception { this(l,Crypto.serverTls()); }
    HubServer(Ledger l,SSLContext tls) throws Exception {
        ledger=l; server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(8443,16);
        server.setEnabledProtocols(new String[]{"TLSv1.2"});
        Thread accept=new Thread(()->{
            while(running) try { Socket s=server.accept(); s.setSoTimeout(4000); try { workers.execute(()->serve(s)); } catch(RejectedExecutionException e) { s.close(); } }
            catch(IOException e) { if(running) HubService.status="Hub listener failed; reopen hub"; }
        },"hub-listener"); accept.start();
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int ch;
        while((ch=in.read())!=-1) { if(ch=='\n') return b.toString(StandardCharsets.US_ASCII.name()).replace("\r",""); if(b.size()>=4096) throw new Fault(413,"Header too large"); b.write(ch); }
        throw new EOFException();
    }
    private void serve(Socket s) {
        try(s) {
            try {
                InputStream in=new BufferedInputStream(s.getInputStream()); String[] request=line(in).split(" ");
                if(request.length!=3||!request[0].equals("POST")||!request[2].equals("HTTP/1.1")) throw new Fault(400,"POST HTTP/1.1 required");
                Map<String,String> headers=new HashMap<>(); int total=0;
                while(true) { String h=line(in); if(h.isEmpty()) break; total+=h.length(); if(total>8192) throw new Fault(413,"Headers too large"); int colon=h.indexOf(':'); if(colon<1) throw new Fault(400,"Invalid header"); String k=h.substring(0,colon).trim().toLowerCase(Locale.ROOT); if(headers.put(k,h.substring(colon+1).trim())!=null) throw new Fault(400,"Duplicate header"); }
                if(headers.containsKey("transfer-encoding")) throw new Fault(400,"Chunked requests are unsupported");
                int length; try { length=Integer.parseInt(headers.getOrDefault("content-length","-1")); } catch(Exception e) { throw new Fault(400,"Invalid body length"); }
                if(length<0||length>32768) throw new Fault(413,"Body must be at most 32 KiB");
                byte[] raw=Bytes.read(in,length); if(raw.length!=length) throw new Fault(400,"Incomplete body");
                String body; try { body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString(); } catch(java.nio.charset.CharacterCodingException e) { throw new Fault(400,"JSON must be valid UTF-8"); }
                String token=headers.getOrDefault("x-device-token",""),session=headers.getOrDefault("x-staff-session","");
                JSONObject j=Json.obj(body),result;
                switch(request[1]) {
                    case "/v1/pair": result=ledger.pair(Json.text(j,"code")); break;
                    case "/v1/login": result=ledger.login(token,Json.text(j,"staffId"),Json.text(j,"pin")); break;
                    case "/v1/logout": ledger.logout(token,session); result=Json.o("ok",true); break;
                    case "/v1/snapshot": result=ledger.snapshot(token,session); break;
                    case "/v1/delivered": ledger.delivered(token,session,Json.arr(j,"orderIds")); result=Json.o("ok",true); break;
                    case "/v1/commands": result=ledger.command(token,session,body); break;
                    case "/v1/pair-code": result=Json.o("code",ledger.issuePairCode(token,session,Json.text(j,"role"))); break;
                    default: throw new Fault(404,"Unknown endpoint");
                }
                reply(s,200,result);
            } catch(Fault e) { reply(s,e.status,Json.o("error",e.getMessage())); }
            catch(Exception e) { lastProblem=e;lastTransportError=e.getClass().getSimpleName()+": "+e.getMessage();reply(s,500,Json.o("error","Hub could not complete request. Keep the command and retry.")); }
        } catch(IOException e) { if(lastProblem==null)lastProblem=e;lastTransportError+=" / "+e.getClass().getSimpleName()+": "+e.getMessage(); /* caller retries same command ID */ }
    }
    private void reply(Socket s,int code,JSONObject value) throws IOException {
        byte[] b=value.toString().getBytes(StandardCharsets.UTF_8); OutputStream out=s.getOutputStream();
        out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+b.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(b); out.flush();
    }
    public void close() { running=false; try { server.close(); } catch(IOException ignored) {} workers.shutdownNow(); }
}
