package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/** Direct, on-device Sign in with ChatGPT and Responses API client. */
public final class ChatGptPlanClient {
    public interface ConnectListener {
        void success(JSONObject state);
        void failure(String message);
    }

    private static final String PREFS = "il_redaktor_settings";
    private static final String KEY_ALIAS = "il_redaktor_oauth_aes_v1";
    private static final String AUTH_ENDPOINT = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String REVOKE_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/revoke";
    private static final String ISSUER = "https://auth.openai.com";
    private static final String DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String RESPONSES_URL = RESOURCE + "/responses";
    private static final String MODELS_URL = RESOURCE + "/models";
    private static final String REQUIRED_SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String PREF_HOST = "chatgpt_host_id";
    private static final String PREF_CLIENT = "chatgpt_client_id";
    private static final String PREF_ACCESS = "chatgpt_access_token";
    private static final String PREF_REFRESH = "chatgpt_refresh_token";
    private static final String PREF_ID_TOKEN = "chatgpt_id_token";
    private static final String PREF_SUBJECT = "chatgpt_subject";
    private static final String PREF_EMAIL = "chatgpt_email";
    private static final String PREF_EMAIL_VERIFIED = "chatgpt_email_verified";
    private static final String PREF_GENERATION = "chatgpt_auth_generation";
    private static final String PREF_SCOPES = "chatgpt_scopes";
    private static final String PREF_MODEL = "chatgpt_model";
    private static final String PREF_MODEL_NAME = "chatgpt_model_name";
    private static final String PREF_EXPIRY = "chatgpt_expiry";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object TOKEN_LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private static final ThreadLocal<SearchControl> ACTIVE_SEARCH = new ThreadLocal<>();

    public static final class SearchControl implements AutoCloseable {
        public interface Observer { void update(JSONObject event); }
        private volatile Observer observer;
        private final java.util.concurrent.atomic.AtomicLong eventNumber=new java.util.concurrent.atomic.AtomicLong();
        public void setObserver(Observer value){observer=value;}
        void report(String name,String address,String stage,String method,int count,String detail){
            Observer target=observer;if(target==null||cancelled)return;
            try{target.update(new JSONObject().put("sequence",eventNumber.incrementAndGet()).put("name",name==null?"Źródło":name)
                .put("url",address==null?"":address).put("stage",stage).put("method",method)
                .put("found",count).put("detail",detail==null?"":detail).put("at",System.currentTimeMillis()));}catch(Exception ignored){}
        }
        private volatile boolean cancelled;
        private volatile boolean timedOut;
        private final java.util.Set<HttpURLConnection> connections=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final long generation;
        private final long deadline;
        private final java.util.Timer timer = new java.util.Timer(true);

        public SearchControl(Context context, long timeoutMs) {
            generation = prefs(context).getLong(PREF_GENERATION, 0L);
            deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            timer.schedule(new java.util.TimerTask() {
                @Override public void run() { timedOut = true; cancel(); }
            }, timeoutMs);
        }
        public void cancel() {
            cancelled = true;
            new Thread(()->{for(HttpURLConnection current:connections)current.disconnect();connections.clear();}, "il-search-cancel").start();
            timer.cancel();
        }
        void check() throws Exception {
            if (timedOut || android.os.SystemClock.elapsedRealtime() >= deadline)
                throw new Exception("Wyszukiwanie przekroczyło limit czasu. Zawęź temat i spróbuj ponownie.");
            if (cancelled) throw new Exception("Wyszukiwanie anulowane.");
        }
        void attach(HttpURLConnection next) throws Exception {
            connections.add(next);
            try { check(); } catch (Exception ex) { connections.remove(next);next.disconnect(); throw ex; }
        }
        void detach(HttpURLConnection current){connections.remove(current);}
        @Override public void close() { cancelled=true;timer.cancel();for(HttpURLConnection current:connections)current.disconnect();connections.clear(); }
    }

    private static void checkSearch() throws Exception {
        SearchControl control = ACTIVE_SEARCH.get();
        if (control != null) control.check();
    }
    private static void trackSearch(HttpURLConnection connection) throws Exception {
        SearchControl control = ACTIVE_SEARCH.get();
        if (control != null) control.attach(connection);
    }

    private ChatGptPlanClient() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConnected(Context context) {
        SharedPreferences p = prefs(context);
        return !readSecret(context, PREF_ACCESS).isEmpty()
            && hasScope(p.getString(PREF_SCOPES, ""), "chatgpt.tokens.use.direct")
            && hasScope(p.getString(PREF_SCOPES, ""), "resource.invoke");
    }

    public static JSONObject state(Context context) {
        SharedPreferences p = prefs(context);
        JSONObject result = new JSONObject();
        try {
            boolean connected = isConnected(context);
            result.put("connected", connected);
            result.put("email", connected ? p.getString(PREF_EMAIL, "") : "");
            result.put("emailVerified", connected && p.getBoolean(PREF_EMAIL_VERIFIED, false));
            result.put("model", connected ? p.getString(PREF_MODEL_NAME, p.getString(PREF_MODEL, "")) : "");
            result.put("usageUrl", "https://chatgpt.com/settings/usage");
        } catch (Exception ignored) {}
        return result;
    }

    public static void connect(Activity activity, ConnectListener listener) {
        new Thread(() -> {
            try {
                JSONObject result = authorize(activity);
                new Handler(Looper.getMainLooper()).post(() -> listener.success(result));
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "Nie udało się połączyć z ChatGPT." : ex.getMessage();
                new Handler(Looper.getMainLooper()).post(() -> listener.failure(message));
            }
        }, "il-chatgpt-oauth").start();
    }

    private static JSONObject authorize(Activity activity) throws Exception {
        SharedPreferences p = prefs(activity);
        final long generation;
        synchronized (TOKEN_LOCK) {
            generation = p.getLong(PREF_GENERATION, 0L) + 1L;
            if (!p.edit().putLong(PREF_GENERATION, generation).commit())
                throw new Exception("Nie udało się rozpocząć bezpiecznej sesji logowania.");
        }
        String previousClient = p.getString(PREF_CLIENT, "");
        String previousSubject = p.getString(PREF_SUBJECT, "");
        String previousIdToken = readSecret(activity, PREF_ID_TOKEN);
        String hostId = p.getString(PREF_HOST, "");
        if (hostId.isEmpty()) {
            hostId = "urn:uuid:" + UUID.randomUUID().toString();
            if (!p.edit().putString(PREF_HOST, hostId).commit())
                throw new Exception("Nie udało się przygotować bezpiecznej rejestracji IL Redaktora.");
        }

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        server.setSoTimeout(300000);
        String redirectUri = "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback";

        String state = randomToken(32);
        String nonce = randomToken(32);
        String verifier = randomToken(48);
        String challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)),
            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

        boolean registering = previousClient.isEmpty();
        String clientForAuthorize = registering ? "dynamic_agent_client" : previousClient;
        StringBuilder auth = new StringBuilder(AUTH_ENDPOINT).append("?")
            .append(form("client_id", clientForAuthorize))
            .append("&").append(form("response_type", "code"))
            .append("&").append(form("redirect_uri", redirectUri))
            .append("&").append(form("scope", REQUIRED_SCOPES))
            .append("&").append(form("resource", RESOURCE))
            .append("&").append(form("state", state))
            .append("&").append(form("nonce", nonce))
            .append("&").append(form("code_challenge_method", "S256"))
            .append("&").append(form("code_challenge", challenge))
            .append("&").append(form("ext_agent_host_id", hostId));
        if (registering) {
            auth.append("&").append(form("agent_name_hint", "IL Redaktor"));
        } else {
            if (!previousIdToken.isEmpty()) auth.append("&").append(form("id_token_hint", previousIdToken));
            String email = p.getString(PREF_EMAIL, "");
            if (!email.isEmpty()) auth.append("&").append(form("login_hint", email));
        }

        CountDownLatch launched = new CountDownLatch(1);
        AtomicReference<Exception> launchError = new AtomicReference<>();
        String loginUrl = auth.toString();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed())
                    throw new Exception("Aplikacja została zamknięta przed logowaniem.");
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl));
                browser.addCategory(Intent.CATEGORY_BROWSABLE);
                activity.startActivity(browser);
            } catch (Exception ex) {
                launchError.set(ex);
            } finally {
                launched.countDown();
            }
        });

        try (ServerSocket callbackServer = server) {
            if (!launched.await(20, TimeUnit.SECONDS))
                throw new Exception("Nie udało się otworzyć przeglądarki logowania.");
            if (launchError.get() != null)
                throw new Exception("Nie udało się otworzyć przeglądarki: " + launchError.get().getMessage());
            Uri callback = awaitCallback(callbackServer);
            String returnedState = callback.getQueryParameter("state");
            if (returnedState == null || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), returnedState.getBytes(StandardCharsets.UTF_8)))
                throw new Exception("Odpowiedź logowania nie przeszła kontroli bezpieczeństwa. Spróbuj ponownie.");

            String authError = callback.getQueryParameter("error");
            if (authError != null && !authError.isEmpty())
                throw new Exception("Nie przyznano zgody na używanie planu ChatGPT w IL Redaktorze. Możesz połączyć konto ponownie i zaakceptować dostęp.");

            String code = callback.getQueryParameter("code");
            if (code == null || code.isEmpty())
                throw new Exception("ChatGPT nie zwrócił kodu logowania.");

            String returnedClient = callback.getQueryParameter("client_id");
            String issuedClient;
            if (registering) {
                if (returnedClient == null || returnedClient.isEmpty() || "dynamic_agent_client".equals(returnedClient))
                    throw new Exception("Rejestracja IL Redaktora w ChatGPT nie została zakończona.");
                issuedClient = returnedClient;
            } else {
                if (returnedClient != null && !returnedClient.isEmpty() && !previousClient.equals(returnedClient))
                    throw new Exception("ChatGPT zwrócił inną rejestrację konta. Dane nie zostały podmienione.");
                issuedClient = previousClient;
            }

            JSONObject tokens = postForm(TOKEN_ENDPOINT, new LinkedHashMap<String, String>() {{
                put("grant_type", "authorization_code");
                put("client_id", issuedClient);
                put("code", code);
                put("code_verifier", verifier);
                put("redirect_uri", redirectUri);
                put("resource", RESOURCE);
            }});
            String idToken = tokens.optString("id_token", "");
            String accessToken = tokens.optString("access_token", "");
            String refreshToken = tokens.optString("refresh_token", "");
            if (idToken.isEmpty() || accessToken.isEmpty() || refreshToken.isEmpty())
                throw new Exception("ChatGPT nie zwrócił kompletu bezpiecznych tokenów.");

            JSONObject claims = verifyIdToken(idToken, issuedClient, nonce);
            String subject = claims.optString("sub", "");
            if (subject.isEmpty()) throw new Exception("Nie udało się potwierdzić konta ChatGPT.");
            if (!previousSubject.isEmpty() && previousClient.equals(issuedClient) && !previousSubject.equals(subject))
                throw new Exception("Wybrane konto ChatGPT różni się od poprzednio połączonego. Odłącz poprzednie konto i rozpocznij nowe logowanie.");

            String grantedScopes = tokens.optString("scope", "");
            if (grantedScopes.isEmpty()) grantedScopes = callback.getQueryParameter("scope");
            if (!hasScope(grantedScopes, "chatgpt.tokens.use.direct") || !hasScope(grantedScopes, "resource.invoke"))
                throw new Exception("Logowanie powiodło się, ale nie przyznano zgody na używanie planu ChatGPT w IL Redaktorze. Połącz konto ponownie i zaakceptuj tę zgodę.");

            long expiresIn = Math.max(60L, tokens.optLong("expires_in", 3600L));
            String email = claims.optString("email", "");
            JSONObject model = chooseModel(accessToken);
            synchronized (TOKEN_LOCK) {
            ensureCurrentGeneration(p, generation);
            SharedPreferences.Editor edit = p.edit()
                .putBoolean(PREF_EMAIL_VERIFIED, claims.optBoolean("email_verified", false))
                .putString(PREF_CLIENT, issuedClient)
                .putString(PREF_SUBJECT, subject)
                .putString(PREF_EMAIL, email)
                .putString(PREF_SCOPES, grantedScopes)
                .putString(PREF_MODEL, model.optString("slug", ""))
                .putString(PREF_MODEL_NAME, model.optString("display_name", model.optString("slug", "")))
                .putLong(PREF_EXPIRY, expiryAt(expiresIn))
                .putString(PREF_ACCESS, encryptSecret(activity, accessToken))
                .putString(PREF_REFRESH, encryptSecret(activity, refreshToken))
                .putString(PREF_ID_TOKEN, encryptSecret(activity, idToken));
            if (!edit.commit()) throw new Exception("Nie udało się bezpiecznie zapisać połączenia ChatGPT.");
            return state(activity);
            }
        } finally {
            try { server.close(); } catch (Exception ignored) {}
        }
    }

    private static Uri awaitCallback(ServerSocket server) throws Exception {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String first = reader.readLine();
            String target = "";
            if (first != null) {
                String[] pieces = first.split(" ");
                if (pieces.length >= 2 && "GET".equals(pieces[0])) target = pieces[1];
            }
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { }
            Uri callback = Uri.parse("http://127.0.0.1" + target);
            boolean validPath = "/auth/callback".equals(callback.getPath());
            String body = validPath
                ? "<!doctype html><meta charset=utf-8><title>IL Redaktor</title><p>Logowanie ChatGPT zakończone. Wróć do aplikacji IL Redaktor.</p>"
                : "<!doctype html><meta charset=utf-8><title>IL Redaktor</title><p>Nieprawidłowy adres powrotu. Wróć do aplikacji.</p>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            String headers = "HTTP/1.1 " + (validPath ? "200 OK" : "404 Not Found")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "
                + bytes.length + "\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            if (!validPath) throw new Exception("Nieprawidłowy adres powrotu z logowania ChatGPT.");
            return callback;
        }
    }

    private static JSONObject verifyIdToken(String token, String audience, String expectedNonce) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new Exception("Nieprawidłowy token tożsamości ChatGPT.");
        JSONObject header = new JSONObject(new String(Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
        JSONObject claims = new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg", "")))
            throw new Exception("ChatGPT zwrócił nieobsługiwany podpis tokenu.");
        if (!ISSUER.equals(claims.optString("iss", "")))
            throw new Exception("Nieprawidłowy wystawca tokenu ChatGPT.");
        long exp = claims.optLong("exp", 0L);
        if (exp <= System.currentTimeMillis() / 1000L)
            throw new Exception("Token tożsamości ChatGPT wygasł. Zaloguj się ponownie.");
        if (!expectedNonce.equals(claims.optString("nonce", "")))
            throw new Exception("Token ChatGPT nie pasuje do rozpoczętej sesji logowania.");

        Object aud = claims.opt("aud");
        boolean hasAudience = audience.equals(aud);
        int audienceCount = 1;
        if (aud instanceof JSONArray) {
            JSONArray list = (JSONArray) aud;
            audienceCount = list.length();
            hasAudience = false;
            for (int i = 0; i < list.length(); i++) if (audience.equals(list.optString(i))) hasAudience = true;
            if (audienceCount > 1 && !audience.equals(claims.optString("azp", "")))
                throw new Exception("Nieprawidłowy odbiorca tokenu ChatGPT.");
        }
        if (!hasAudience) throw new Exception("Token ChatGPT nie jest przeznaczony dla IL Redaktora.");

        JSONObject discovery = getJson(DISCOVERY_URL, null);
        if (!ISSUER.equals(discovery.optString("issuer", "")))
            throw new Exception("Nie udało się potwierdzić konfiguracji logowania OpenAI.");
        String jwksUrl = discovery.optString("jwks_uri", "");
        if (!jwksUrl.startsWith("https://auth.openai.com/"))
            throw new Exception("Nieprawidłowy adres kluczy podpisujących OpenAI.");
        JSONObject jwks = getJson(jwksUrl, null);
        JSONArray keys = jwks.optJSONArray("keys");
        String kid = header.optString("kid", "");
        JSONObject match = null;
        if (keys != null) for (int i = 0; i < keys.length(); i++) {
            JSONObject candidate = keys.optJSONObject(i);
            if (candidate != null && kid.equals(candidate.optString("kid", ""))) { match = candidate; break; }
        }
        if (match == null || !"RSA".equals(match.optString("kty", "")))
            throw new Exception("Nie udało się zweryfikować podpisu tokenu ChatGPT.");
        byte[] modulus = Base64.decode(match.getString("n"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        byte[] exponent = Base64.decode(match.getString("e"), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new RSAPublicKeySpec(new java.math.BigInteger(1, modulus), new java.math.BigInteger(1, exponent)));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(Base64.decode(parts[2], Base64.URL_SAFE | Base64.NO_WRAP)))
            throw new Exception("Nie udało się zweryfikować podpisu tokenu ChatGPT.");
        return claims;
    }

    private static JSONObject chooseModel(String accessToken) throws Exception {
        JSONObject response = getJson(MODELS_URL, accessToken);
        JSONArray models = response.optJSONArray("models");
        if (models == null) throw new Exception("ChatGPT nie zwrócił listy dostępnych modeli.");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null || !"list".equals(model.optString("visibility", ""))) continue;
            String slug = model.optString("slug", "");
            if (!slug.isEmpty()) return new JSONObject().put("slug", slug)
                .put("display_name", model.optString("display_name", slug));
        }
        throw new Exception("To konto ChatGPT nie udostępnia modelu do IL Redaktora.");
    }

    public static JSONObject ask(Context context, String prompt, JSONArray history, boolean webSearch) throws Exception {
        return ask(context, prompt, history, webSearch, new SearchControl(context, 60000));
    }

    public static JSONObject ask(Context context, String prompt, JSONArray history, boolean webSearch, SearchControl control) throws Exception {
        ACTIVE_SEARCH.set(control);
        try {
            control.check();
            return askWithinDeadline(context, prompt, history, webSearch);
        } catch (Exception ex) {
            control.check();
            throw ex;
        } finally {
            ACTIVE_SEARCH.remove();
            control.close();
        }
    }

    private static JSONObject askWithinDeadline(Context context, String prompt, JSONArray history, boolean webSearch) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) throw new Exception("Wpisz temat wyszukiwania.");
        SharedPreferences p = prefs(context);
        String model = p.getString(PREF_MODEL, "");
        if (model.isEmpty()) throw new Exception("Połącz konto ChatGPT, aby włączyć IL Chat i Radar.");
        JSONArray input = new JSONArray();
        if (history != null) {
            for (int i = Math.max(0, history.length() - 8); i < history.length(); i++) {
                JSONObject old = history.optJSONObject(i);
                if (old == null) continue;
                String role = old.optString("role", "");
                String content = old.optString("content", "");
                if (("user".equals(role) || "assistant".equals(role)) && !content.trim().isEmpty())
                    input.put(new JSONObject().put("role", role).put("content", content));
            }
        }
        input.put(new JSONObject().put("role", "user").put("content", prompt.trim()));

        JSONObject request = new JSONObject()
            .put("model", model)
            .put("instructions", "Jesteś IL Chat AI, asystentem redakcji lokalnej Ispiny w gminie Drwinia, w Małopolsce. Odpowiadaj po polsku. Korzystaj z wyszukiwania internetowego, gdy pytanie dotyczy aktualnych informacji. Oddziel datę publikacji od daty wydarzenia. Dla twierdzeń o faktach podaj źródła i opieraj odpowiedź na stronach źródłowych. Nie zgaduj, nie powtarzaj samych wyników wyszukiwarki i zaznaczaj, gdy nie znajdujesz wiarygodnego potwierdzenia.")
            .put("input", input)
            .put("store", false)
            .put("stream", true);
        configureWebSearch(request, webSearch);
        return sendResponse(context, request);
    }

    private static void configureWebSearch(JSONObject request, boolean webSearch) throws Exception {
        if (!webSearch) return;
        request.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")))
            .put("tool_choice", "required")
            .put("include", new JSONArray().put("web_search_call.action.sources"));
    }

    private static JSONObject sendResponse(Context context, JSONObject request) throws Exception {
        long generation = ACTIVE_SEARCH.get() == null ? prefs(context).getLong(PREF_GENERATION, 0L) : ACTIVE_SEARCH.get().generation;
        checkSearch();
        String token = accessToken(context, false, generation);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                ensureCurrentGeneration(prefs(context), generation);
                connection = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
                trackSearch(connection);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(request.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                if (status == 401 && attempt == 0) {
                    readBody(connection.getErrorStream());
                    connection.disconnect();
                    connection = null;
                    token = accessToken(context, true, generation);
                    continue;
                }
                if (status < 200 || status >= 300)
                    throw new Exception(formatApiError(status, readBody(connection.getErrorStream())));

                StringBuilder text = new StringBuilder();
                JSONObject completed = null;
                String streamError = "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) continue;
                        JSONObject event;
                        try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                        String type = event.optString("type", "");
                        if ("response.output_text.delta".equals(type)) text.append(event.optString("delta", ""));
                        else if ("response.completed".equals(type)) completed = event.optJSONObject("response");
                        else if ("response.failed".equals(type) || "error".equals(type)) {
                            JSONObject response = event.optJSONObject("response");
                            JSONObject error = event.optJSONObject("error");
                            if (error == null && response != null) error = response.optJSONObject("error");
                            if (error == null) streamError = "Odpowiedź ChatGPT nie została zakończona.";
                            else streamError = formatStreamError(error.optString("code", ""),
                                error.optString("message", error.optString("code", "Odpowiedź ChatGPT nie została zakończona.")));
                            break;
                        } else if ("response.incomplete".equals(type)) {
                            streamError = "Odpowiedź ChatGPT została przerwana przed ukończeniem.";
                            break;
                        }
                    }
                }
                if (!streamError.isEmpty()) throw new Exception(streamError);
                if (completed == null) throw new Exception("Połączenie z ChatGPT zakończyło się przed potwierdzeniem kompletnej odpowiedzi.");
                if (text.toString().trim().isEmpty()) text.append(extractOutputText(completed));
                String answer = text.toString().trim();
                if (answer.isEmpty()) throw new Exception("ChatGPT zwrócił pustą odpowiedź.");
                ensureCurrentGeneration(prefs(context), generation);
                checkSearch();
                return responseResult(answer, completed);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new Exception("Nie udało się odświeżyć sesji ChatGPT.");
    }

    private static JSONObject responseResult(String answer, JSONObject response) throws Exception {
        Map<String, JSONObject> sources = new LinkedHashMap<>();
        LinkedHashMap<String, Boolean> queries = new LinkedHashMap<>();
        JSONArray output = response.optJSONArray("output");
        if (output != null) for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            if ("web_search_call".equals(item.optString("type", ""))) {
                JSONObject action = item.optJSONObject("action");
                if (action != null) {
                    String query = action.optString("query", "");
                    if (!query.isEmpty()) queries.put(query, true);
                    addSources(sources, action.optJSONArray("sources"));
                }
            }
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null) addAnnotations(sources, part.optJSONArray("annotations"));
            }
        }
        JSONArray sourceArray = new JSONArray();
        for (JSONObject source : sources.values()) sourceArray.put(source);
        JSONArray queryArray = new JSONArray();
        for (String query : queries.keySet()) queryArray.put(query);
        boolean verified = !answer.isEmpty() && sourceArray.length() > 0;
        return new JSONObject()
            .put("answer", answer)
            .put("text", answer)
            .put("sources", sourceArray)
            .put("queries", queryArray)
            .put("verified", verified)
            .put("searchedAt", System.currentTimeMillis());
    }

    private static void addAnnotations(Map<String, JSONObject> sources, JSONArray array) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject annotation = array.optJSONObject(i);
            if (annotation == null) continue;
            JSONObject citation = annotation.optJSONObject("url_citation");
            addSource(sources,
                citation == null ? annotation.optString("url", "") : citation.optString("url", ""),
                citation == null ? annotation.optString("title", "") : citation.optString("title", ""));
        }
    }

    private static void addSources(Map<String, JSONObject> sources, JSONArray array) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject source = array.optJSONObject(i);
            if (source != null) addSource(sources, source.optString("url", ""), source.optString("title", ""));
        }
    }

    private static void addSource(Map<String, JSONObject> sources, String rawUrl, String rawTitle) {
        try {
            Uri uri = Uri.parse(rawUrl);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return;
            String url = uri.toString();
            if (url.length() > 1800 || sources.containsKey(url)) return;
            String title = rawTitle == null || rawTitle.trim().isEmpty() ? uri.getHost() : rawTitle.trim();
            sources.put(url, new JSONObject().put("url", url).put("title", title.substring(0, Math.min(180, title.length()))));
        } catch (Exception ignored) {}
    }

    private static String extractOutputText(JSONObject response) {
        StringBuilder result = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type", ""))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type", "")))
                    result.append(part.optString("text", ""));
            }
        }
        return result.toString();
    }

    private static String accessToken(Context context, boolean forceRefresh, long generation) throws Exception {
        synchronized (REFRESH_LOCK) {
            checkSearch();
            SharedPreferences p = prefs(context);
            final String refresh;
            final String client;
            synchronized (TOKEN_LOCK) {
            ensureCurrentGeneration(p, generation);
            String access = readSecret(context, PREF_ACCESS);
            if (!forceRefresh && !access.isEmpty() && p.getLong(PREF_EXPIRY, 0L) > System.currentTimeMillis() + 60000L)
                return access;
            refresh = readSecret(context, PREF_REFRESH);
            client = p.getString(PREF_CLIENT, "");
            }
            if (refresh.isEmpty() || client.isEmpty())
                throw new Exception("Sesja ChatGPT wygasła. Połącz konto ChatGPT ponownie w Radarze.");
            JSONObject tokens = postForm(TOKEN_ENDPOINT, new LinkedHashMap<String, String>() {{
                put("grant_type", "refresh_token");
                put("client_id", client);
                put("refresh_token", refresh);
                put("resource", RESOURCE);
            }});
            String nextAccess = tokens.optString("access_token", "");
            if (nextAccess.isEmpty()) throw new Exception("Nie udało się odświeżyć sesji ChatGPT. Połącz konto ponownie.");
            String nextRefresh = tokens.optString("refresh_token", refresh);
            String nextIdToken = tokens.optString("id_token", readSecret(context, PREF_ID_TOKEN));
            long expiresIn = Math.max(60L, tokens.optLong("expires_in", 3600L));
            String scopes = tokens.optString("scope", p.getString(PREF_SCOPES, ""));
            synchronized (TOKEN_LOCK) {
            ensureCurrentGeneration(p, generation);
            SharedPreferences.Editor edit = p.edit()
                .putString(PREF_ACCESS, encryptSecret(context, nextAccess))
                .putString(PREF_REFRESH, encryptSecret(context, nextRefresh))
                .putLong(PREF_EXPIRY, expiryAt(expiresIn))
                .putString(PREF_SCOPES, scopes);
            if (nextIdToken != null && !nextIdToken.isEmpty())
                edit.putString(PREF_ID_TOKEN, encryptSecret(context, nextIdToken));
            if (!edit.commit()) throw new Exception("Nie udało się zapisać odświeżonej sesji ChatGPT.");
            return nextAccess;
            }
        }
    }

    public static boolean disconnect(Context context) throws Exception {
        final String refresh;
        final String client;
        synchronized (TOKEN_LOCK) {
            SharedPreferences p = prefs(context);
            refresh = readSecret(context, PREF_REFRESH);
            client = p.getString(PREF_CLIENT, "");
            // Invalidate pending OAuth BEFORE any network call.
            boolean saved = p.edit().putLong(PREF_GENERATION, p.getLong(PREF_GENERATION, 0L) + 1L)
                .remove(PREF_CLIENT).remove(PREF_ACCESS).remove(PREF_REFRESH)
                .remove(PREF_ID_TOKEN).remove(PREF_SUBJECT).remove(PREF_EMAIL).remove(PREF_EMAIL_VERIFIED)
                .remove(PREF_SCOPES).remove(PREF_MODEL).remove(PREF_MODEL_NAME).remove(PREF_EXPIRY).commit();
            if (!saved) throw new Exception("Nie udało się trwale usunąć lokalnego logowania. Spróbuj ponownie.");
        }
        if (refresh.isEmpty()) return true;
        if (client.isEmpty()) return false;
        try {
            LinkedHashMap<String, String> fields = new LinkedHashMap<>();
            fields.put("token", refresh);
            fields.put("token_type_hint", "refresh_token");
            fields.put("client_id", client);
            postForm(REVOKE_ENDPOINT, fields, 5000);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void ensureCurrentGeneration(SharedPreferences p, long generation) throws Exception {
        if (p.getLong(PREF_GENERATION, 0L) != generation)
            throw new Exception("To logowanie zostało anulowane. Rozpocznij ponownie.");
    }

    private static JSONObject getJson(String url, String accessToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            trackSearch(connection);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (accessToken != null && !accessToken.isEmpty())
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            int status = connection.getResponseCode();
            String body = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) throw new Exception(formatApiError(status, body));
            return new JSONObject(body);
        } finally { connection.disconnect(); }
    }

    private static JSONObject postForm(String url, Map<String, String> fields) throws Exception {
        return postForm(url, fields, 25000);
    }

    private static JSONObject postForm(String url, Map<String, String> fields, int timeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(Math.min(15000, timeout));
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (body.length() > 0) body.append("&");
                body.append(form(entry.getKey())).append("=").append(form(entry.getValue()));
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String response = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status < 200 || status >= 300) throw new Exception(formatApiError(status, response));
            if (response.trim().isEmpty()) return new JSONObject();
            return new JSONObject(response);
        } finally { connection.disconnect(); }
    }

    private static String form(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String form(String key, String value) throws Exception {
        return form(key) + "=" + form(value);
    }

    private static String randomToken(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static long expiryAt(long expiresIn) {
        long margin = Math.min(120L, Math.max(10L, expiresIn / 10L));
        return System.currentTimeMillis() + Math.max(1L, expiresIn - margin) * 1000L;
    }

    private static boolean hasScope(String scopes, String expected) {
        if (scopes == null) return false;
        for (String scope : scopes.trim().split("\\s+")) if (expected.equals(scope)) return true;
        return false;
    }

    private static String readSecret(Context context, String keyName) {
        try {
            String stored = prefs(context).getString(keyName, "");
            if (stored.isEmpty()) return "";
            String[] parts = stored.split(":");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private static String encryptSecret(Context context, String value) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            gen.generateKey();
        }
        SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String readBody(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkSearch();
                if (out.size() + read > 1_000_000) throw new Exception("Odpowiedź ChatGPT jest za duża.");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String formatStreamError(String code, String message) {
        if ("subscription_sharing_usage_limit_exceeded".equals(code))
            return "Limit użycia ChatGPT dla IL Redaktora został osiągnięty. Sprawdź limity w ustawieniach użycia ChatGPT.";
        if ("subscription_sharing_usage_unavailable".equals(code))
            return "Połączenie z ChatGPT jest chwilowo niedostępne. Spróbuj później.";
        if ("subscription_sharing_user_not_eligible".equals(code))
            return "To konto ChatGPT lub wybrana przestrzeń robocza nie może używać planu ChatGPT w IL Redaktorze.";
        return message == null || message.trim().isEmpty()
            ? "Odpowiedź ChatGPT nie została zakończona." : message;
    }

    private static String formatApiError(int status, String body) {
        String code = "";
        String message = body == null ? "" : body;
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                code = error.optString("code", "");
                message = error.optString("message", code.isEmpty() ? message : code);
            } else message = json.optString("detail", json.optString("message", message));
        } catch (Exception ignored) {}
        String lower = (code + " " + message).toLowerCase(Locale.ROOT);
        if (status == 401) return "Sesja ChatGPT wygasła lub nie ma wymaganej zgody. Połącz konto ChatGPT ponownie.";
        if (status == 403 && lower.contains("not_eligible"))
            return "To konto ChatGPT lub wybrana przestrzeń robocza nie może używać planu ChatGPT w IL Redaktorze.";
        if (status == 403) return "ChatGPT odrzucił dostęp. Sprawdź zgodę dla IL Redaktora i ustawienia konta.";
        if (lower.contains("subscription_sharing_usage_limit_exceeded"))
            return "Limit użycia ChatGPT dla IL Redaktora został osiągnięty. Sprawdź limity w ustawieniach użycia ChatGPT.";
        if (status == 429) return "ChatGPT chwilowo ogranicza liczbę zapytań. Spróbuj później; Radar pozostaje włączony.";
        if (status == 503 || lower.contains("subscription_sharing_usage_unavailable"))
            return "Połączenie z ChatGPT jest chwilowo niedostępne. Spróbuj później.";
        String clean = message == null ? "" : message.replaceAll("[\\r\\n]+", " ").trim();
        return "ChatGPT: HTTP " + status + (clean.isEmpty() ? "." : ": " + clean.substring(0, Math.min(260, clean.length())));
    }
}
