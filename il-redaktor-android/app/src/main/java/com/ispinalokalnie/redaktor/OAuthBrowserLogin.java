package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/** 
 * OAuth2.1 PKCE for Android using the SYSTEM browser and a temporary
 * loopback listener. The redirect contains a short-lived authorization code;
 * tokens are exchanged only from native Java, never exposed to JavaScript.
 */
public final class OAuthBrowserLogin {
    public interface Listener {
        void status(String message);
        void success(String accessToken, long expiresSeconds, String refreshToken, String clientId);
        void failure(String message);
    }

    private static final String REDIRECT="http://127.0.0.1:38477/callback";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    public OAuthBrowserLogin(Activity activity, Listener listener) {
        this.activity=activity;
        this.listener=listener;
    }

    private void note(String value) { main.post(()->listener.status(value)); }
    private void fail(String value) { main.post(()->listener.failure(value)); }

    private static String random(int size) {
        byte[] buffer=new byte[size];
        RANDOM.nextBytes(buffer);
        return Base64.encodeToString(buffer,Base64.NO_PADDING|Base64.NO_WRAP|Base64.URL_SAFE);
    }

    public void start() {
        new Thread(this::run, "il-oauth21-login").start();
    }

    private void run() {
        try (ServerSocket server=new ServerSocket(38477,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(180000);
            note("Konfiguruję bezpieczne połączenie z WordPress.com...");
            String clientId = activity.getSharedPreferences("il_redaktor_settings", Activity.MODE_PRIVATE)
                .getString("oauth21_client_id","");
            if (clientId.isEmpty()) {
                clientId=WpMcp.registerClient(REDIRECT);
                activity.getSharedPreferences("il_redaktor_settings",Activity.MODE_PRIVATE)
                    .edit().putString("oauth21_client_id",clientId).apply();
            }
            final String actualClientId=clientId;
            final String state=random(24);
            final String verifier=random(48);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge=Base64.encodeToString(digest,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
            String login=WpMcp.AUTHORIZE+
                "?response_type=code" +
                "&client_id="+Uri.encode(clientId)+
                "&redirect_uri="+Uri.encode(REDIRECT)+
                "&code_challenge="+Uri.encode(challenge)+
                "&code_challenge_method=S256" +
                "&scope=auth"+
                "&state="+Uri.encode(state);
            note("Wybierz konto Google na ekranie WordPress.com i zatwierdź dostęp.");
            main.post(()->{
                try {
                    Intent browser=new Intent(Intent.ACTION_VIEW,Uri.parse(login));
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    activity.startActivity(browser);
                } catch(Exception ex) { listener.failure("Nie można otworzyć przeglądarki: "+ex.getMessage()); }
            });
            String receivedCode=null;
            String error=null;
            long expiresAt=System.currentTimeMillis()+180000;
            while(System.currentTimeMillis()<expiresAt) {
                try(Socket socket=server.accept()) {
                    socket.setSoTimeout(8000);
                    BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String first=input.readLine();
                    if(first==null)continue;
                    String[] firstParts=first.split(" ");
                    String path=firstParts.length>=2?firstParts[1]:"/";
                    Uri request=Uri.parse("http://127.0.0.1:38477"+path);
                    boolean callback="/callback".equals(request.getPath());
                    String remoteState=request.getQueryParameter("state");
                    boolean correctState=callback&&state.equals(remoteState);
                    if(correctState) {
                        receivedCode=request.getQueryParameter("code");
                        error=request.getQueryParameter("error");
                    }
                    String page=correctState&&receivedCode!=null?
                        "<h2>Logowanie WordPress zakończone</h2><p>Wróć do aplikacji IL Redaktor Smart. Możesz zamknąć tę kartę.</p>":
                        "<h2>IL Redaktor Smart</h2><p>Wróć do aplikacji i spróbuj ponownie, jeśli logowanie nie działa.</p>";
                    byte[] bytes=("<!DOCTYPE html><html lang=\"pl\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><body style=\"font:18px system-ui;padding:22px;color:#174875\">"+page+"</body></html>").getBytes(StandardCharsets.UTF_8);
                    String header="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
                    OutputStream out=socket.getOutputStream();
                    out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
                    if(correctState)break;
                }
            }
            if(receivedCode==null)throw new Exception(error!=null?"WordPress odrzucił logowanie: "+error:"Nie otrzymano potwierdzenia z przeglądarki.");
            note("WordPress potwierdził logowanie. Weryfikuję uprawnienia do Ispina Lokalnie...");
            JSONObject tokens=WpMcp.exchangeCode(actualClientId,REDIRECT,receivedCode,verifier);
            String token=tokens.optString("access_token","");
            if(token.isEmpty())throw new Exception("Nie otrzymano tokenu dostępu WordPress.");
            String refresh=tokens.optString("refresh_token","");
            long seconds=tokens.optLong("expires_in",3600);
            main.post(()->listener.success(token,Math.max(120,seconds),refresh,actualClientId));
        } catch(Exception ex) {
            fail("Nie udało się zalogować przez WordPress.com: "+ex.getMessage());
        }
    }
}
