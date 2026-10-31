package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * WordPress.com OAuth 2.1 native Android authorization-code + PKCE login.
 *
 * Unlike a desktop app, Android may suspend/kill an Activity when an external
 * browser opens. A loopback ServerSocket therefore cannot reliably receive the
 * authorization code. The redirect is an Android deep link instead.
 *
 * The short-lived PKCE verifier/state are persisted BEFORE launching the browser
 * so the callback works even if Android recreates MainActivity in the meantime.
 * Access and refresh tokens are stored separately with AndroidKeyStore.
 */
public final class OAuthBrowserLogin {
    public interface Listener {
        void status(String message);
        void success(String accessToken, long expiresSeconds, String refreshToken, String clientId);
        void failure(String message);
    }

    public static final String REDIRECT = "com.ispinalokalnie.redaktorsmart.publish180:/callback";
    private static final String PREFS = "il_redaktor_settings";
    private static final String PENDING_STATE = "oauth21_pending_state";
    private static final String PENDING_VERIFIER = "oauth21_pending_verifier";
    private static final String PENDING_CLIENT = "oauth21_pending_client";
    private static final String PENDING_EXPIRES = "oauth21_pending_expires";
    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    public OAuthBrowserLogin(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }
    private void note(String text) { main.post(() -> listener.status(text)); }
    private void fail(String text) { main.post(() -> listener.failure(text)); }

    private static String random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    public static boolean isCallback(Uri uri) {
        return uri != null &&
            "com.ispinalokalnie.redaktorsmart.publish170".equals(uri.getScheme()) &&
            "/callback".equals(uri.getPath());
    }

    public void start() {
        new Thread(this::beginLogin, "il-oauth-begin").start();
    }

    private void beginLogin() {
        try {
            note("Krok 1/4: przygotowuję bezpieczne logowanie w aplikacji Android…");
            SharedPreferences storage = prefs();
            String clientId = storage.getString("oauth21_client_id", "");
            if (!REDIRECT.equals(storage.getString("oauth21_redirect", ""))) clientId = "";
            if (clientId.isEmpty()) {
                try {
                    clientId = WpMcp.registerClient(REDIRECT);
                } catch (Exception ex) {
                    throw new Exception("WordPress.com nie zarejestrował mobilnego adresu powrotu. " +
                        "Możliwy brak obsługi schematu aplikacji przez serwer. Szczegóły: " + ex.getMessage());
                }
                if (!storage.edit().putString("oauth21_client_id", clientId)
                    .putString("oauth21_redirect", REDIRECT).commit())
                    throw new Exception("Nie udało się zapisać nowej rejestracji aplikacji.");
            }

            final String state = random(32);
            final String verifier = random(48);
            byte[] sha = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.encodeToString(sha,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);

            // The callback may be delivered to a NEW Activity: do not rely on
            // an in-memory OAuth instance or JavaScript state.
            if (!storage.edit()
                .putString(PENDING_STATE, state)
                .putString(PENDING_VERIFIER, verifier)
                .putString(PENDING_CLIENT, clientId)
                .putLong(PENDING_EXPIRES, System.currentTimeMillis() + TTL_MS)
                .commit())
                throw new Exception("Nie udało się zapisać tymczasowej sesji logowania.");

            String loginUrl = WpMcp.AUTHORIZE +
                "?response_type=code" +
                "&client_id=" + Uri.encode(clientId) +
                "&redirect_uri=" + Uri.encode(REDIRECT) +
                "&code_challenge=" + Uri.encode(challenge) +
                "&code_challenge_method=S256" +
                "&scope=global" +
                "&state=" + Uri.encode(state);

            note("Krok 2/4: otwieram WordPress.com. Zatwierdź dostęp wymagany do zarządzania treściami. Zakres global obejmuje konto WordPress.com; aplikacja pracuje na Ispina Lokalnie.");
            main.post(() -> {
                try {
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl));
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    activity.startActivity(browser);
                    note("Krok 3/4: po zatwierdzeniu przeglądarka powinna automatycznie otworzyć Redaktora IL.");
                } catch (Exception ex) {
                    clearPending();
                    fail("Nie udało się otworzyć przeglądarki: " + ex.getMessage());
                }
            });
        } catch (Exception ex) {
            clearPending();
            fail("Błąd logowania WordPress.com: " +
                (ex.getMessage() == null ? "Nieznany problem." : ex.getMessage()));
        }
    }

    private void clearPending() {
        prefs().edit().remove(PENDING_STATE).remove(PENDING_VERIFIER)
            .remove(PENDING_CLIENT).remove(PENDING_EXPIRES).apply();
    }

    /** Call from MainActivity.onCreate AND onNewIntent. Never log a callback URI. */
    public void handleRedirect(Uri uri) {
        if (!isCallback(uri)) return;
        note("Krok 4/4: Redaktor otrzymał odpowiedź. Sprawdzam uprawnienia WordPress.com…");
        new Thread(() -> {
            SharedPreferences p = prefs();
            try {
                String expectedState = p.getString(PENDING_STATE, "");
                String verifier = p.getString(PENDING_VERIFIER, "");
                String clientId = p.getString(PENDING_CLIENT, "");
                long until = p.getLong(PENDING_EXPIRES, 0);
                if (expectedState.isEmpty() || verifier.isEmpty() || clientId.isEmpty())
                    throw new Exception("Brak rozpoczętej sesji. Uruchom logowanie jeszcze raz.");
                if (System.currentTimeMillis() > until)
                    throw new Exception("Sesja logowania wygasła. Rozpocznij logowanie ponownie.");
                String state = uri.getQueryParameter("state");
                if (!expectedState.equals(state))
                    throw new Exception("Nieprawidłowe potwierdzenie stanu OAuth. Spróbuj ponownie.");
                String denied = uri.getQueryParameter("error");
                if (denied != null && !denied.isEmpty())
                    throw new Exception("WordPress odrzucił autoryzację: " + denied);
                String code = uri.getQueryParameter("code");
                if (code == null || code.isEmpty())
                    throw new Exception("WordPress nie przekazał kodu logowania.");

                JSONObject tokens = WpMcp.exchangeCode(clientId, REDIRECT, code, verifier);
                String access = tokens.optString("access_token", "");
                if (access.isEmpty()) throw new Exception("Nie otrzymano tokenu WordPress.com.");
                String refresh = tokens.optString("refresh_token", "");
                long lifetime = Math.max(120, tokens.optLong("expires_in", 3600));
                main.post(() -> listener.success(access, lifetime, refresh, clientId));
            } catch (Exception ex) {
                fail("Nie udało się zakończyć logowania: " +
                    (ex.getMessage() == null ? "Nieznany problem OAuth." : ex.getMessage()));
            } finally {
                clearPending();
            }
        }, "il-oauth-exchange").start();
    }
}
