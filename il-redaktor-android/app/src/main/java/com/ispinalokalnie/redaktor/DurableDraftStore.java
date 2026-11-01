package com.ispinalokalnie.redaktor;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Draft data only. AtomicFile syncs writes before returning to the editor. */
final class DurableDraftStore {
    private static final int MAX_BYTES=8_000_000;
    private final AtomicFile file;
    DurableDraftStore(File path){file=new AtomicFile(path);}
    synchronized String read(){
        try{
            if(!file.getBaseFile().exists()&&!new File(file.getBaseFile().getPath()+".bak").exists())return new JSONObject().put("ok",true).put("exists",false).toString();
            byte[] raw=file.readFully();if(raw.length>MAX_BYTES)throw new Exception("Biblioteczka przekracza limit 8 MB.");
            String text=new String(raw,StandardCharsets.UTF_8);validate(text);
            return new JSONObject().put("ok",true).put("exists",true).put("data",text).toString();
        }catch(Exception ex){return error("Nie mogę odczytać trwałej biblioteczki. Nie usuwaj danych aplikacji.");}
    }
    synchronized String write(String payload){
        FileOutputStream stream=null;
        try{
            if(payload==null)throw new Exception();byte[] raw=payload.getBytes(StandardCharsets.UTF_8);if(raw.length>MAX_BYTES)return error("Biblioteczka przekracza limit 8 MB. Zachowaj tekst także w WordPressie lub przez Kopia tekstu.");validate(payload);
            stream=file.startWrite();stream.write(raw);stream.getFD().sync();file.finishWrite(stream);stream=null;
            if(!payload.equals(new String(file.readFully(),StandardCharsets.UTF_8)))throw new Exception("Zapis nie został potwierdzony.");
            return new JSONObject().put("ok",true).toString();
        }catch(Exception ex){if(stream!=null)file.failWrite(stream);return error("Nie udało się trwale zapisać szkiców. Sprawdź wolne miejsce i zachowaj tekst przez Kopia tekstu.");}
    }
    private static void validate(String text)throws Exception{JSONObject data=new JSONObject(text);if(data.optInt("version")!=1||data.optJSONArray("drafts")==null)throw new Exception("Nieprawidłowy format szkiców.");}
    private static String error(String message){try{return new JSONObject().put("ok",false).put("message",message).toString();}catch(Exception impossible){return "{\"ok\":false}";}}
}
