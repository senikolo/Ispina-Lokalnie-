package pl.ispinalokalnie.kindlewifi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class GuestPortalServerTest {
    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) {this.status=status;this.body=body;}
    }
    private static Response request(String path,String post) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL("http://127.0.0.1:8765"+path).openConnection();
        conn.setConnectTimeout(2000);conn.setReadTimeout(4000);
        if(post!=null){
            conn.setRequestMethod("POST");conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            byte[] payload=post.getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=conn.getOutputStream()){out.write(payload);}
        }
        int code=conn.getResponseCode();
        InputStream stream=code<400?conn.getInputStream():conn.getErrorStream();
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] data=new byte[1024];int n;
        while((n=stream.read(data))!=-1)buffer.write(data,0,n);
        stream.close();conn.disconnect();
        return new Response(code, new String(buffer.toByteArray(),StandardCharsets.UTF_8));
    }
    private static void check(boolean valid,String message){
        if(!valid)throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception{
        GuestPortalServer portal=new GuestPortalServer();
        portal.start();
        try{
            Response page=request("/",null);
            check(page.status==200 && page.body.contains("action=\"/authorize\""),"guest HTML form");
            check(page.body.contains("name=\"agree\""),"explicit consent");
            check(request("/status",null).body.equals("TEST_NOT_AUTHENTICATED"),"no implied authentication");
            check(request("/authorize",null).status==404,"GET not accepted");
            // Test invalid submission before a successful explicit POST.

            Response fail=request("/authorize","simulation=kindle-lab&guest=go");
            check(fail.status==400,"consent cannot be bypassed");
            Response guest=request("/authorize","simulation=kindle-lab&agree=yes&guest=go");
            check(guest.status==200,"guest form accepted");
            check(request("/status",null).body.equals("TEST_OK_GUEST"),"guest marked only after POST");
            portal.setEmailMode(true);
            check(request("/status",null).body.equals("TEST_NOT_AUTHENTICATED"),"changing mode clears session");
            check(request("/",null).body.contains("type=\"email\""),"email form rendered");
            check(request("/register","simulation=kindle-lab&agree=yes&email=invalid").status==400,"bad email rejected");
            Response sent=request("/register","simulation=kindle-lab&agree=yes&email=test%40example.org");
            check(sent.status==200 && !sent.body.contains("test@example.org"),"email accepted, not echoed");
            check(request("/status",null).body.equals("TEST_OK_EMAIL"),"email marked only after valid POST");
            check(portal.getAccepted()==1,"accepted count reset per scenario");
        }finally{portal.stop();}
        System.out.println("PASS: Android local guest portal simulator, HTTP forms, consent and email privacy");
    }
}
