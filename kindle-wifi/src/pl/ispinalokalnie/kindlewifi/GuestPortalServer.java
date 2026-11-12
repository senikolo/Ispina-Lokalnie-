package pl.ispinalokalnie.kindlewifi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * On-demand local HTTP *simulator* for KOReader guest-portal testing.
 * It does not intercept other traffic, impersonate a train operator,
 * collect email addresses, or provide access to the Internet.
 */
final class GuestPortalServer {
    static final int PORT = 8765;
    private static final int MAX_LINE = 4096;
    private volatile ServerSocket server;
    private volatile Thread worker;
    private volatile boolean running;
    private volatile boolean emailMode;
    private volatile int accepted;
    private volatile String lastResult = "TEST_NOT_AUTHENTICATED";

    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORT), 8);
        socket.setSoTimeout(1500);
        server = socket;
        running = true;
        accepted = 0;
        lastResult = "TEST_NOT_AUTHENTICATED";
        worker = new Thread(this::serve, "kindle-guest-portal-local");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        lastResult = "TEST_NOT_AUTHENTICATED";
    }

    void setEmailMode(boolean required) {
        emailMode = required;
        accepted = 0;
        lastResult = "TEST_NOT_AUTHENTICATED";
    }

    boolean isEmailMode() { return emailMode; }
    boolean isRunning() { return running; }
    int getAccepted() { return accepted; }

    private void serve() {
        try {
            while (running) {
                ServerSocket listener = server;
                if (listener == null) break;
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(3500);
                    InetAddress remote = client.getInetAddress();
                    if (!remote.isLoopbackAddress() && !remote.isSiteLocalAddress()) {
                        respond(client, 403, "text/plain; charset=utf-8", "Forbidden");
                        continue;
                    }
                    handle(client);
                } catch (java.net.SocketTimeoutException ignored) {
                    // Keep the listener responsive to the "Stop" action.
                } catch (IOException ignored) {
                    // A broken client must not crash the server.
                } catch (RuntimeException ignored) {
                    // Malformed requests are ignored, without leaking private data.
                }
            }
        } finally {
            running = false;
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= MAX_LINE) {
            int c = in.read();
            if (c == -1 || c == '\n') break;
            if (c != '\r') bytes.write(c);
        }
        if (bytes.size() > MAX_LINE) throw new IOException("Line too long");
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String,String> decode(String body) {
        Map<String,String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0,split), "UTF-8");
                String value = URLDecoder.decode(pair.substring(split+1), "UTF-8");
                if (key.length() <= 80 && value.length() <= 512) values.put(key,value);
            } catch (Exception ignored) {}
        }
        return values;
    }

    private void handle(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        String request = line(in);
        String[] pieces = request.split(" ");
        if (pieces.length < 2) { respond(client,400,"text/plain; charset=utf-8","Bad request"); return; }
        String method = pieces[0].toUpperCase(Locale.ROOT);
        String route = pieces[1].split("\\?",2)[0];
        int contentLength = 0;
        for (int count=0; count<70; count++) {
            String header = line(in);
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            if (header.substring(0,colon).trim().equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(header.substring(colon+1).trim()); }
                catch (NumberFormatException ignored) { contentLength = -1; }
            }
        }
        if (contentLength < 0 || contentLength > 4096) {
            respond(client,413,"text/plain; charset=utf-8","Form too large"); return;
        }
        byte[] bytes = new byte[contentLength];
        int offset = 0;
        while (offset < bytes.length) {
            int read = in.read(bytes,offset,bytes.length-offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset != bytes.length) { respond(client,400,"text/plain; charset=utf-8","Incomplete form"); return; }
        Map<String,String> fields = decode(new String(bytes,StandardCharsets.UTF_8));
        if ("GET".equals(method) && "/status".equals(route)) {
            respond(client,200,"text/plain; charset=utf-8",lastResult);
        } else if ("GET".equals(method) && ("/".equals(route) || "/guest".equals(route) || "/email".equals(route))) {
            boolean needsEmail = "/email".equals(route) || (!"/guest".equals(route) && emailMode);
            respond(client,200,"text/html; charset=utf-8",portal(needsEmail));
        } else if ("POST".equals(method) && ("/authorize".equals(route) || "/register".equals(route))) {
            boolean needsEmail = "/register".equals(route);
            if (!"kindle-lab".equals(fields.get("simulation")) || !"yes".equals(fields.get("agree"))) {
                respond(client,400,"text/html; charset=utf-8",
                        "<h1>TEST: nie zaakceptowano warunków</h1>");
                return;
            }
            if (needsEmail) {
                String email = fields.get("email");
                if (email == null || email.length()>240 || !email.matches("^[^@\\s<>]+@[^@\\s<>]+\\.[a-zA-Z]{2,}$")) {
                    respond(client,400,"text/html; charset=utf-8","<h1>TEST: niepoprawny e-mail</h1>");
                    return;
                }
            }
            // We deliberately do NOT persist or log the provided email address.
            accepted++;
            lastResult = needsEmail ? "TEST_OK_EMAIL" : "TEST_OK_GUEST";
            respond(client,200,"text/html; charset=utf-8",
                    "<html><body><h1>TEST: formularz przyjęty</h1>"
                    + "<p>Symulacja działa. To nie daje internetu ani nie loguje do pociągu.</p></body></html>");
        } else {
            respond(client,404,"text/plain; charset=utf-8","Not found");
        }
    }

    private static String portal(boolean email) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>IL Wi-Fi – symulacja</title></head>"
            + "<body><h1>IL Wi-Fi Gość: lokalny portal TEST</h1>"
            + "<p>To symulacja formularza Wi-Fi pociągowego uruchomiona na Twoim telefonie.</p>"
            + "<p>Akceptujesz wyłącznie próbę połączenia. Żadne dane nie są wysyłane do internetu.</p>"
            + "<form method=\"POST\" action=\"" + (email ? "/register" : "/authorize") + "\">"
            + "<input type=\"hidden\" name=\"simulation\" value=\"kindle-lab\">"
            + (email ? "<p>Podaj testowy adres e-mail.</p><input type=\"email\" name=\"email\">" : "")
            + "<p><input type=\"checkbox\" name=\"agree\" value=\"yes\"> Zgadzam się na symulację.</p>"
            + "<input type=\"submit\" name=\"guest\" value=\"Zatwierdź test\"></form>"
            + "</body></html>";
    }

    private static void respond(Socket client,int code,String type,String value) throws IOException {
        byte[] content = value.getBytes(StandardCharsets.UTF_8);
        String status = code==200?"OK":code==400?"Bad Request":code==403?"Forbidden":
            code==404?"Not Found":"Payload Too Large";
        String headers = "HTTP/1.1 "+code+" "+status+"\r\n"
            + "Content-Type: "+type+"\r\n"
            + "Content-Length: "+content.length+"\r\n"
            + "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(content);
        out.flush();
    }
}
