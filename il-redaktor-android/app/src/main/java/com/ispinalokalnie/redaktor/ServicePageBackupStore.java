package com.ispinalokalnie.redaktor;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Exact, private recovery copy of page 818 before a write. Never accesses WordPress. */
final class ServicePageBackupStore {
    private final AtomicFile file;
    ServicePageBackupStore(File path){file=new AtomicFile(path);}
    private static String digest(String text)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    synchronized String save(String content){
        FileOutputStream out=null;
        try {
            if(content==null||content.getBytes(StandardCharsets.UTF_8).length>2_000_000)throw new Exception("Kopia strony przekracza 2 MB.");
            JSONObject data=new JSONObject().put("pageId",818).put("site","ispinalokalnie.wordpress.com")
                .put("at",System.currentTimeMillis()).put("content",content).put("sha256",digest(content));
            out=file.startWrite();out.write(data.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();file.finishWrite(out);out=null;
            JSONObject check=new JSONObject(read());
            if(!check.optBoolean("ok")||!content.equals(check.optString("content")))throw new Exception("Odczyt kopii nie potwierdził zapisu.");
            return new JSONObject().put("ok",true).toString();
        }catch(Exception e){if(out!=null)file.failWrite(out);return error(e.getMessage());}
    }
    synchronized String read(){
        try {
            byte[] raw=file.readFully();if(raw.length>3_000_000)throw new Exception("Kopia jest za duża.");
            JSONObject data=new JSONObject(new String(raw,StandardCharsets.UTF_8));
            if(data.optInt("pageId")!=818||!"ispinalokalnie.wordpress.com".equals(data.optString("site"))||
                !digest(data.getString("content")).equals(data.optString("sha256")))throw new Exception("Suma kontrolna kopii nie zgadza się.");
            return data.put("ok",true).toString();
        }catch(Exception e){return error("Nie odczytano kopii katalogu. "+(e.getMessage()==null?"":e.getMessage()));}
    }
    private static String error(String message){try{return new JSONObject().put("ok",false).put("message",message).toString();}catch(Exception impossible){return "{\"ok\":false}";}}
}
