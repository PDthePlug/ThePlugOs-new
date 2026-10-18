package com.theplugos.local;
import java.io.*;
final class Bytes {
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];while(out.size()<limit){int n=in.read(b,0,Math.min(b.length,limit-out.size()));if(n<0)break;out.write(b,0,n);}return out.toByteArray();}
}
