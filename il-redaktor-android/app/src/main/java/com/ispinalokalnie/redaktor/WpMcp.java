package com.ispinalokalnie.redaktor;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WordPress.com OAuth 2.1 MCP integration.
 * Uses server-independent dynamic registration + PKCE, no client secret in APK.
 * Never log authorization codes, refresh tokens or access tokens.
 */
public final class WpMcp {
    public static final String REGISTER = "https://public-api.wordpress.com/oauth2-1/register";
    public static final String AUTHORIZE = "https://public-api.wordpress.com/oauth2-1/authorize";
    public static final String TOKEN = "https://public-api.wordpress.com/oauth2-1/token";
    public static final String MCP = "https://public-api.wordpress.com/wpcom/v2/mcp/v1";
    public static final String SITE = "ispinalokalnie.wordpress.com";

    private WpMcp() {}

    // In-memory, per access-token session. Never persist session IDs or log credentials.
    // WordPress.com uses stateful MCP Streamable HTTP transport.
    private static String mcpSessionId = "";
    private static String mcpSessionBearer = "";
    private static String mcpProtocolVersion = "2025-11-25";

    static synchronized void resetSession() {
        mcpSessionId = "";
        mcpSessionBearer = "";
        mcpProtocolVersion = "2025-11-25";
    }

    private static final class ExpiredMcpSession extends Exception {
        ExpiredMcpSession() { super("Sesja MCP wygasła."); }
    }

    private static final class McpHttpResponse {
        final JSONObject body;
        final String sessionId;
        McpHttpResponse(JSONObject body, String sessionId) {
            this.body = body;
            this.sessionId = sessionId;
        }
    }

    private static JSONObject parseMcpBody(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) return new JSONObject();
        String input = body.trim();
        if (input.startsWith("{")) return new JSONObject(input);
        // Streamable HTTP may return server-sent events instead of plain JSON.
        if (input.startsWith("event:") || input.startsWith("data:") || input.startsWith(":")) {
            StringBuilder data = new StringBuilder();
            for (String line : input.split("\\r?\\n")) {
                if (line.startsWith("data:")) {
                    String value = line.substring(5).trim();
                    if ("[DONE]".equals(value)) continue;
                    if (data.length() > 0) data.append('\n');
                    data.append(value);
                    try {
                        JSONObject candidate = new JSONObject(data.toString());
                        if (candidate.has("result") || candidate.has("error"))
                            return candidate;
                    } catch (org.json.JSONException ignored) {
                        // Wait for remaining multiline SSE payload.
                    }
                } else if (line.trim().isEmpty()) {
                    data.setLength(0);
                }
            }
        }
        throw new Exception("Nieczytelna odpowiedź serwera WordPress MCP.");
    }

    private static McpHttpResponse postMcp(String bearer, JSONObject json,
                                            String session, String protocol) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(MCP).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (session != null && !session.isEmpty()) {
                connection.setRequestProperty("Mcp-Session-Id", session);
                connection.setRequestProperty("MCP-Protocol-Version", protocol);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String value = readReply(connection);
            if (status == 404 && session != null && !session.isEmpty())
                throw new ExpiredMcpSession();
            if (status < 200 || status >= 300)
                throw new Exception("HTTP " + status + ": " + friendly(value));
            JSONObject parsed = parseMcpBody(value);
            JSONObject error = parsed.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "Błąd protokołu MCP.");
                if (message.toLowerCase(java.util.Locale.ROOT).contains("invalid or expired session"))
                    throw new ExpiredMcpSession();
            }
            return new McpHttpResponse(parsed, connection.getHeaderField("Mcp-Session-Id"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void ensureMcpSession(String token) throws Exception {
        if (!mcpSessionId.isEmpty() && token.equals(mcpSessionBearer)) return;
        resetSession();
        JSONObject initialize = new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "initialize")
            .put("params", new JSONObject()
                .put("protocolVersion", "2025-11-25")
                .put("capabilities", new JSONObject())
                .put("clientInfo", new JSONObject()
                    .put("name", "IL Redaktor Smart")
                    .put("version", "1.5.1")));
        McpHttpResponse initial = postMcp(token, initialize, null, null);
        if (initial.body.has("error"))
            throw new Exception("WordPress MCP odrzucił inicjalizację: " +
                initial.body.getJSONObject("error").optString("message"));
        JSONObject result = initial.body.optJSONObject("result");
        if (result == null)
            throw new Exception("WordPress MCP nie potwierdził inicjalizacji.");
        String session = initial.sessionId == null ? "" : initial.sessionId.trim();
        if (session.isEmpty() || session.length() > 512 ||
            !session.matches("[\\x21-\\x7E]+"))
            throw new Exception("WordPress MCP nie przekazał prawidłowego Mcp-Session-Id.");
        String protocol = result.optString("protocolVersion", "2025-11-25");
        if (protocol.isEmpty() || protocol.length() > 32 ||
            !protocol.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new Exception("WordPress MCP przekazał nieznaną wersję protokołu.");
        // MCP initialization is complete only after this mandatory notification.
        JSONObject initialized = new JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized");
        postMcp(token, initialized, session, protocol);
        mcpSessionId = session;
        mcpSessionBearer = token;
        mcpProtocolVersion = protocol;
    }



    static String readReply(HttpURLConnection c) throws Exception {
        InputStream source = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        if (source == null) return "{}";
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > 5_000_000) throw new Exception("Odpowiedź WordPress jest za duża.");
            }
            return out.toString("UTF-8");
        }
    }

    static JSONObject sendJson(String endpoint, JSONObject request, String bearer) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json, text/event-stream");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            try (OutputStream out = c.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            String value = readReply(c);
            if (status < 200 || status >= 300)
                throw new Exception("HTTP " + status + ": " + friendly(value));
            // WordPress MCP should return JSON; SSE is tolerated for a single event.
            if (value.startsWith("event:") || value.startsWith("data:")) {
                for (String line : value.split("\n")) {
                    if (line.startsWith("data: ")) return new JSONObject(line.substring(6));
                }
                throw new Exception("WordPress zwrócił pustą odpowiedź MCP.");
            }
            return new JSONObject(value);
        } finally { if (c != null) c.disconnect(); }
    }

    static JSONObject sendForm(String endpoint, Map<String,String> fields) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(28000);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String,String> e : fields.entrySet()) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            String value = readReply(c);
            if (status < 200 || status >= 300)
                throw new Exception("HTTP " + status + ": " + friendly(value));
            return new JSONObject(value);
        } finally { if (c != null) c.disconnect(); }
    }

    private static String friendly(String value) {
        try {
            JSONObject j = new JSONObject(value);
            String msg = j.optString("message", j.optString("error_description", j.optString("error", "Błąd serwera.")));
            return msg.substring(0, Math.min(280, msg.length())).replaceAll("<[^>]*>", " ");
        } catch (Exception e) { return value.substring(0, Math.min(240, value.length())); }
    }

    static String registerClient(String redirectUri) throws Exception {
        JSONObject request = new JSONObject();
        request.put("client_name", "IL Redaktor Smart – Ispina Lokalnie");
        request.put("redirect_uris", new JSONArray().put(redirectUri));
        request.put("grant_types", new JSONArray().put("authorization_code").put("refresh_token"));
        request.put("response_types", new JSONArray().put("code"));
        request.put("token_endpoint_auth_method", "none");
        JSONObject response = sendJson(REGISTER, request, null);
        String id = response.optString("client_id", "");
        if (id.isEmpty() || id.length() > 200) throw new Exception("WordPress nie zwrócił identyfikatora aplikacji OAuth 2.1.");
        return id;
    }

    static JSONObject exchangeCode(String clientId, String redirectUri, String code, String verifier) throws Exception {
        Map<String,String> map = new LinkedHashMap<>();
        map.put("grant_type","authorization_code");
        map.put("client_id",clientId);
        map.put("redirect_uri",redirectUri);
        map.put("code",code);
        map.put("code_verifier",verifier);
        return sendForm(TOKEN,map);
    }

    static JSONObject refresh(String clientId, String refreshToken) throws Exception {
        Map<String,String> map = new LinkedHashMap<>();
        map.put("grant_type","refresh_token");
        map.put("client_id",clientId);
        map.put("refresh_token",refreshToken);
        return sendForm(TOKEN,map);
    }

    static synchronized JSONObject request(String token, String operation, JSONObject params) throws Exception {
        JSONObject args = new JSONObject();
        args.put("wpcom_site", SITE);
        args.put("action", "execute");
        args.put("operation", operation);
        args.put("params", params);
        JSONObject call = new JSONObject();
        call.put("jsonrpc", "2.0");
        call.put("id", 1);
        call.put("method", "tools/call");
        call.put("params", new JSONObject()
            .put("name", "wpcom-mcp-content-authoring")
            .put("arguments", args));
        // No tools/call may be sent before the initialize/initialized handshake.
        // Synchronization avoids multiple threads accidentally sharing an unfinished session.
        JSONObject outer = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureMcpSession(token);
                outer = postMcp(token, call, mcpSessionId, mcpProtocolVersion).body;
                break;
            } catch (ExpiredMcpSession expired) {
                resetSession();
                if (attempt == 1)
                    throw new Exception("WordPress MCP odrzucił dwukrotnie sesję. Spróbuj ponownie.");
            }
        }
        if (outer == null) throw new Exception("Brak odpowiedzi z WordPress MCP.");
        if (outer.has("error")) {
            JSONObject error = outer.optJSONObject("error");
            throw new Exception(error == null ? "Błąd protokołu MCP." :
                error.optString("message", "Nie udało się wykonać operacji."));
        }
        JSONObject result = outer.optJSONObject("result");
        if (result == null) throw new Exception("Brak wyniku operacji WordPress.");
        if (result.optBoolean("isError", false)) {
            throw new Exception(extractError(result));
        }
        JSONObject structured = result.optJSONObject("structuredContent");
        if (structured != null) {
            if (structured.has("error")) throw new Exception(String.valueOf(structured.get("error")));
            return structured;
        }
        JSONArray content = result.optJSONArray("content");
        if (content != null) for (int i=0;i<content.length();i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null || !"text".equals(item.optString("type"))) continue;
            String text = item.optString("text");
            try {
                JSONObject parsed = new JSONObject(text);
                if (parsed.has("error")) throw new Exception(parsed.get("error").toString());
                return parsed;
            } catch (org.json.JSONException ignored) {
                throw new Exception(text.substring(0,Math.min(text.length(),300)));
            }
        }
        throw new Exception("Odpowiedź WordPress nie zawiera danych.");
    }

    static String extractError(JSONObject response) {
        JSONArray parts = response.optJSONArray("content");
        if (parts != null && parts.length() > 0)
            return parts.optJSONObject(0).optString("text", "Błąd WordPress.");
        return "WordPress zwrócił błąd operacji.";
    }

    static Object unwrap(JSONObject wrapper) throws Exception {
        Object data = wrapper.opt("data");
        if (data == null || JSONObject.NULL.equals(data)) {
            if (wrapper.has("id")) return wrapper;
            if (wrapper.has("error")) throw new Exception(wrapper.optString("error"));
            throw new Exception("Nie otrzymano potwierdzonych danych z WordPress.");
        }
        if (data instanceof JSONObject) return normalize((JSONObject)data);
        if (data instanceof JSONArray) {
            JSONArray array=(JSONArray)data;
            JSONArray normalized = new JSONArray();
            for(int i=0;i<array.length();i++) {
                Object e = array.get(i);
                normalized.put(e instanceof JSONObject ? normalize((JSONObject)e) : e);
            }
            return normalized;
        }
        return data;
    }

    private static JSONObject normalize(JSONObject data) throws Exception {
        for (String key : new String[]{"title","content","excerpt"}) {
            Object value = data.opt(key);
            if (value instanceof String) {
                JSONObject field = new JSONObject();
                field.put("raw",value);
                field.put("rendered",value);
                data.put(key,field);
            }
        }
        return data;
    }

    /**
     * The WordPress.com core REST API offers an official, direct posts endpoint.
     * Posting via MCP may be denied even when reading works (different tool grants).
     * An editor confirmation happens in JavaScript BEFORE this function runs.
     *
     * Security: fixed WordPress.com host + site ID; no caller-supplied URL.
     * Reliability: a write is sent exactly once. On a timeout or HTTP 5xx we
     * propagate uncertainty to the UI instead of retrying and duplicating a post.
     */
    static JSONObject postArticleDirect(String token, Integer postId, JSONObject data) throws Exception {
        String endpoint = "https://public-api.wordpress.com/wp/v2/sites/252442125/posts" +
            (postId == null ? "" : "/" + postId);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(18000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setDoOutput(true);
            try(OutputStream output = connection.getOutputStream()) {
                output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            String value = readReply(connection);
            if (status < 200 || status >= 300) {
                String reason = friendly(value);
                if (status == 401 || status == 403)
                    throw new Exception("HTTP " + status + " (uprawnienia): " + reason +
                        ". Sprawdź rolę Administrator/Redaktor i zgodę na zarządzanie wpisami.");
                if (status >= 500)
                    throw new Exception("HTTP " + status + " (wynik zapisu niepewny): " + reason +
                        ". Sprawdź listę artykułów przed ponowną próbą.");
                throw new Exception("HTTP " + status + " (publikowanie): " + reason);
            }
            JSONObject response = new JSONObject(value);
            if (!response.has("id") || response.optInt("id", 0) <= 0)
                throw new Exception("API WordPress zwróciło odpowiedź bez ID. Sprawdź listę artykułów.");
            return normalize(response);
        } finally {
            if(connection!=null) connection.disconnect();
        }
    }

    static JSONObject getArticleDirect(String token, int postId) throws Exception {
        if(postId <= 0) throw new Exception("Nieprawidłowy numer artykułu.");
        String endpoint="https://public-api.wordpress.com/wp/v2/sites/252442125/posts/" +
            postId + "?context=edit";
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(endpoint).openConnection();
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization","Bearer "+token);
            c.setRequestProperty("Accept","application/json");
            int code=c.getResponseCode();
            String reply=readReply(c);
            if(code<200 || code>=300)
                throw new Exception("Odczyt wpisu HTTP "+code+": "+friendly(reply));
            return normalize(new JSONObject(reply));
        } finally {if(c!=null)c.disconnect();}
    }

    static Object mapRestCall(String token, String method, String path, String body) throws Exception {
        Uri uri=Uri.parse("https://local.invalid/" + path);
        String clean=uri.getPath().replaceFirst("^/", "");
        String[] parts=clean.split("/");
        String type=parts[0];
        boolean hasId=parts.length==2 && parts[1].matches("[0-9]+");
        JSONObject params="POST".equals(method) ? new JSONObject(body) : new JSONObject();
        if (!"POST".equals(method)) {
            // WordPress MCP operations do NOT share the same parameter schema.
            // In particular, posts.list and media.list reject the REST context=edit
            // parameter, whereas posts.get and pages.get accept it.
            final boolean list = !hasId;
            if (!list && ("posts".equals(type) || "pages".equals(type))) {
                String context = uri.getQueryParameter("context");
                if ("edit".equals(context) || "view".equals(context))
                    params.put("context", context);
            }
            if (list) {
                for (String key : new String[]{"search","orderby","order"}) {
                    String v=uri.getQueryParameter(key);
                    if (v!=null&&!v.isEmpty()) params.put(key,v);
                }
                for (String key : new String[]{"per_page","page"}) {
                    String v=uri.getQueryParameter(key);
                    if(v!=null&&v.matches("[0-9]+")) params.put(key,Integer.parseInt(v));
                }
                if ("posts".equals(type)) {
                    String status=uri.getQueryParameter("status");
                    if (status!=null && !status.isEmpty())
                        params.put("status",new JSONArray().put(status));
                } else if ("media".equals(type)) {
                    String mediaType=uri.getQueryParameter("media_type");
                    if (mediaType!=null && !mediaType.isEmpty())
                        params.put("media_type",new JSONArray().put(mediaType));
                }
            }
        }
        String operation;
        if("posts".equals(type)) operation=hasId?("POST".equals(method)?"posts.update":"posts.get"):
                        ("POST".equals(method)?"posts.create":"posts.list");
        else if("pages".equals(type)) operation=hasId?("POST".equals(method)?"pages.update":"pages.get"):
                            ("POST".equals(method)?"pages.create":"pages.list");
        else if("media".equals(type)) {
            if ("POST".equals(method)) throw new Exception("Przesyłanie nowych zdjęć wymaga osobnego połączenia API. Wybierz zdjęcie z biblioteki.");
            operation="media.list";

        } else if("categories".equals(type)) operation="categories.list";
        else if("tags".equals(type)) operation="POST".equals(method)?"tags.create":"tags.list";
        else throw new Exception("Ta operacja nie jest obsługiwana przez WordPress MCP.");

        if ("posts".equals(type) && "POST".equals(method)) {
            if (!params.optString("status").matches("draft|publish|private|pending"))
                throw new Exception("Wybierz poprawny status artykułu.");
            if (params.optString("title").trim().isEmpty() &&
                params.optString("content").trim().isEmpty())
                throw new Exception("Artykuł musi mieć tytuł lub treść.");
            return postArticleDirect(token, hasId ? Integer.parseInt(parts[1]) : null, params);
        }
        if ("posts".equals(type) && hasId && "GET".equals(method)) {
            return getArticleDirect(token,Integer.parseInt(parts[1]));
        }
        if (hasId) params.put("id",Integer.parseInt(parts[1]));
        if ("POST".equals(method)) params.put("user_confirmed",true);
        JSONObject response=request(token,operation,params);
        return unwrap(response);
    }
}
