package com.theplugos.local;

import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.util.Date;

interface Vault { String seal(String plain); String open(String sealed); }

final class Crypto implements Vault {
    private final SecretKey key;
    Crypto() {
        try {
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if(!ks.containsAlias("local-data-v1")) {
                KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
                g.init(new KeyGenParameterSpec.Builder("local-data-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); g.generateKey();
            }
            key=(SecretKey)ks.getKey("local-data-v1",null);
        } catch(Exception e) { throw new IllegalStateException("Cannot unlock device storage",e); }
    }
    public String seal(String s) { return encrypt(key,s); }
    public String open(String s) { return decrypt(key,s); }
    static String encrypt(SecretKey k,String s) {
        try { Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,k); return b64(c.getIV())+"."+b64(c.doFinal(s.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException("Storage encryption failed",e); }
    }
    static String decrypt(SecretKey k,String s) {
        try { String[] p=s.split("\\."); Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP))); return new String(c.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8); }
        catch(Exception e) { throw new IllegalStateException("Storage authentication failed",e); }
    }
    static String b64(byte[] b) { return Base64.encodeToString(b,Base64.NO_WRAP); }
    static String random() { byte[] b=new byte[32]; new SecureRandom().nextBytes(b); return b64(b); }
    static String hash(String s) { return hash(s.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] b) {
        try { byte[] h=MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s=new StringBuilder(); for(byte v:h) s.append(String.format("%02x",v&255)); return s.toString(); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String pin(String pin,String salt) {
        try { PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),Base64.decode(salt,Base64.NO_WRAP),210000,256); byte[] h=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); spec.clearPassword(); return b64(h); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static KeyStore tlsKeys() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(!ks.containsAlias("hub-tls-v1")) {
            KeyPairGenerator g=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder("hub-tls-v1",KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).setCertificateSubject(new X500Principal("CN=ThePlugOS local hub")).setCertificateSerialNumber(BigInteger.ONE).setCertificateNotBefore(new Date(1577836800000L)).setCertificateNotAfter(new Date(2208988800000L)).build()); g.generateKeyPair();
        }
        return ks;
    }
    static String fingerprint() throws Exception { return hash(tlsKeys().getCertificate("hub-tls-v1").getEncoded()); }
    static SSLContext serverTls() throws Exception {
        KeyStore source=tlsKeys();
        X509ExtendedKeyManager km=new X509ExtendedKeyManager() {
            public String[] getClientAliases(String t,java.security.Principal[] i){return null;}
            public String chooseClientAlias(String[] t,java.security.Principal[] i,java.net.Socket s){return null;}
            public String[] getServerAliases(String t,java.security.Principal[] i){return "RSA".equals(t)?new String[]{"hub-tls-v1"}:null;}
            public String chooseServerAlias(String t,java.security.Principal[] i,java.net.Socket s){return "RSA".equals(t)?"hub-tls-v1":null;}
            public X509Certificate[] getCertificateChain(String a){try{return new X509Certificate[]{(X509Certificate)source.getCertificate(a)};}catch(Exception e){throw new IllegalStateException(e);}}
            public PrivateKey getPrivateKey(String a){try{return (PrivateKey)source.getKey(a,null);}catch(Exception e){throw new IllegalStateException(e);}}
        };
        SSLContext ctx=SSLContext.getInstance("TLS"); ctx.init(new KeyManager[]{km},null,null); return ctx;
    }
    static SSLContext pinnedTls(String fingerprint) throws Exception {
        if(!fingerprint.matches("[0-9a-f]{64}")) throw new Fault(400,"Check the full 64-character hub fingerprint");
        TrustManager tm=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String type) throws java.security.cert.CertificateException { throw new java.security.cert.CertificateException("Not a client trust manager"); }
            public void checkServerTrusted(X509Certificate[] chain,String type) throws java.security.cert.CertificateException {
                if(chain.length==0 || !MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),hash(encoded(chain[0])).getBytes(StandardCharsets.US_ASCII))) throw new java.security.cert.CertificateException("Hub fingerprint mismatch");
                chain[0].checkValidity();
            }
            private byte[] encoded(X509Certificate c) throws java.security.cert.CertificateException { return c.getEncoded(); }
        };
        SSLContext ctx=SSLContext.getInstance("TLS"); ctx.init(null,new TrustManager[]{tm},null); return ctx;
    }
}
