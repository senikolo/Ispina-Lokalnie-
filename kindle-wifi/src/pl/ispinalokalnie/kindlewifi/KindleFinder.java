package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class KindleFinder {
    static final int PORT = 2222;
    private final Context context;

    KindleFinder(Context context) {
        this.context = context;
    }

    static final class Pin implements HostKeyRepository {
        final byte[] key;
        final String type;
        private final HostKey hostKey;

        Pin(HostKey hostKey, byte[] key) {
            this.hostKey = hostKey;
            this.type = hostKey.getType();
            this.key = key;
        }

        @Override public int check(String host, byte[] offered) {
            return Arrays.equals(key, offered) ? OK : CHANGED;
        }

        @Override public HostKey[] getHostKey() { return new HostKey[] { hostKey }; }
        @Override public HostKey[] getHostKey(String host, String type) { return getHostKey(); }
        @Override public void add(HostKey key, UserInfo user) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "Kindle"; }
    }

    static final class Match {
        final String host;
        private final LocalLink link;

        Match(String host, LocalLink link) {
            this.host = host;
            this.link = link;
        }

        SocketFactory sockets(int timeout) {
            return new SocketFactory() {
                @Override public Socket createSocket(String host, int port) throws IOException {
                    if (!Match.this.host.equals(host) || port != PORT) throw new IOException("Nieprawidłowy adres Kindle.");
                    return open(host, timeout, link);
                }

                @Override public InputStream getInputStream(Socket socket) throws IOException {
                    return socket.getInputStream();
                }

                @Override public OutputStream getOutputStream(Socket socket) throws IOException {
                    return socket.getOutputStream();
                }
            };
        }
    }

    private static final class LocalLink {
        final Inet4Address address;
        final int prefix;
        final Network wifi;

        LocalLink(Inet4Address address, int prefix, Network wifi) {
            this.address = address;
            this.prefix = prefix;
            this.wifi = wifi;
        }

        boolean contains(int target) {
            return (number(address) & mask(prefix)) == (target & mask(prefix)) && number(address) != target;
        }

    }

    private static Socket open(String host, int timeout, LocalLink link) throws IOException {
        Socket socket = new Socket();
        try {
            if (link != null) {
                if (link.wifi != null) link.wifi.bindSocket(socket);
                socket.bind(new InetSocketAddress(link.address, 0));
            }
            socket.connect(new InetSocketAddress(host, PORT), timeout);
            return socket;
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }
    }

    private List<LocalLink> links() throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        HashSet<String> managed = new HashSet<>();
        ArrayList<LocalLink> links = new ArrayList<>();
        if (manager != null) {
            for (Network network : manager.getAllNetworks()) {
                LinkProperties properties = manager.getLinkProperties(network);
                if (properties == null) continue;
                managed.add(properties.getInterfaceName());
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                for (LinkAddress link : properties.getLinkAddresses())
                    addLink(links, link.getAddress(), link.getPrefixLength(), network);
            }
        }
        // Wi-Fi tethering interfaces are not reported as Wi-Fi client networks.
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (managed.contains(iface.getName()) || !iface.getName().matches("(wlan|swlan|ap|softap|wifi)[0-9]*")
                    || !iface.isUp()) continue;
            for (InterfaceAddress link : iface.getInterfaceAddresses())
                addLink(links, link.getAddress(), link.getNetworkPrefixLength(), null);
        }
        return links;
    }

    private static void addLink(List<LocalLink> links, InetAddress address, int prefix, Network wifi) {
        if (address instanceof Inet4Address && privateAddress(number(address)) && prefix > 0 && prefix < 32)
            links.add(new LocalLink((Inet4Address) address, prefix, wifi));
    }

    boolean hasHotspot() {
        try {
            for (LocalLink link : links()) {
                if (link.wifi == null) return true;
            }
        } catch (IOException | SecurityException ignored) {
            // The phone may hide tethering interface details.
        }
        return false;
    }

    static final class Found {
        final Match match;
        final Pin pin;

        Found(Match match, Pin pin) {
            this.match = match;
            this.pin = pin;
        }

        String fingerprint() {
            return pin.hostKey.getFingerPrint(new JSch());
        }
    }

    static final class ScanIssue extends IOException {
        ScanIssue(String message) { super(message); }
    }

    Match localRoute(String host) throws IOException {
        int address = number(InetAddress.getByName(host));
        for (LocalLink link : links()) {
            if (link.contains(address)) return new Match(host, link);
        }
        throw new IOException("Adres jest poza lokalną siecią.");
    }

    Found findAt(String host, Pin pin) throws IOException {
        java.util.concurrent.atomic.AtomicBoolean keyChanged = new java.util.concurrent.atomic.AtomicBoolean();
        Found found = findAt(host, pin, linksForDiscovery(), keyChanged);
        if (found == null && keyChanged.get())
            throw new ScanIssue("Klucz SSH pod tym adresem różni się od zapisanego.");
        return found;
    }

    private List<LocalLink> linksForDiscovery() {
        try { return links(); }
        catch (IOException | SecurityException unavailable) { return java.util.Collections.emptyList(); }
    }

    private Found findAt(String host, Pin pin, List<LocalLink> links,
                         java.util.concurrent.atomic.AtomicBoolean keyChanged) throws IOException {
        int address = number(InetAddress.getByName(host));
        for (LocalLink link : links) {
            if (!link.contains(address)) continue;
            Found found = probe(new Match(host, link), pin, keyChanged, 1800, 6500);
            if (found != null) return found;
        }
        // The entered or saved IP may be reachable even if Android hides the tethering interface.
        return probe(new Match(host, null), pin, keyChanged, 1800, 6500);
    }

    List<Found> find(String lastHost, Pin pin) throws Exception {
        List<LocalLink> links = linksForDiscovery();
        java.util.concurrent.atomic.AtomicBoolean savedAddressChanged = new java.util.concurrent.atomic.AtomicBoolean();
        if (!lastHost.isEmpty()) {
            Found saved = findAt(lastHost, pin, links, savedAddressChanged);
            if (saved != null) return java.util.Collections.singletonList(saved);
        }
        if (links.isEmpty()) {
            if (savedAddressChanged.get()) return scanResult(java.util.Collections.emptyList(), true);
            throw new ScanIssue("Telefon nie udostępnia informacji o sieci do wyszukiwania. Wpisz aktualny adres IP Kindle’a.");
        }
        int previous = lastHost.isEmpty() ? 0 : number(InetAddress.getByName(lastHost));
        ArrayList<Found> found = new ArrayList<>();

        LinkedHashMap<String, LocalLink> candidates = new LinkedHashMap<>();
        for (LocalLink link : links) {
            int prefix = Math.max(link.prefix, 23);
            int address = number(link.address);
            int start = address & mask(prefix);
            int count = 1 << (32 - prefix);
            int first = link.prefix <= 30 && prefix == link.prefix ? 1 : 0;
            // Scan the phone's /24 first, then the adjacent block if it is part of the same subnet.
            int block = prefix == 23 ? (address - start) & 256 : 0;
            for (int i = 0; i < count && candidates.size() < 508; i++) {
                int offset = i ^ block;
                if (offset < first || offset >= count - first) continue;
                int candidate = start + offset;
                if (candidate != previous && link.contains(candidate) && privateAddress(candidate))
                    candidates.putIfAbsent(host(candidate), link);
            }
        }
        if (candidates.isEmpty()) return scanResult(found, savedAddressChanged.get());

        ExecutorService pool = Executors.newFixedThreadPool(24);
        try {
            CompletionService<Found> results = new ExecutorCompletionService<>(pool);
            for (String host : candidates.keySet()) {
                Match candidate = new Match(host, candidates.get(host));
                results.submit(() -> probe(candidate, pin, null, 650, 3500));
            }
            for (int i = 0; i < candidates.size(); i++) {
                Found candidate = results.take().get();
                if (candidate != null) {
                    found.add(candidate);
                    if (pin != null || found.size() >= 12) break;
                }
            }
            return scanResult(found, savedAddressChanged.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Found> scanResult(List<Found> found, boolean keyChanged) throws ScanIssue {
        if (found.isEmpty() && keyChanged)
            throw new ScanIssue("Urządzenie pod zapisanym adresem ma inny klucz. Sprawdź aktualny adres w KOReaderze.");
        return found;
    }

    private Found probe(Match match, Pin pin, java.util.concurrent.atomic.AtomicBoolean keyChanged,
                        int tcpTimeout, int sshTimeout) {
        Session session = null;
        final byte[][] observed = {null};
        try {
            session = new JSch().getSession("root", match.host, PORT);
            session.setSocketFactory(match.sockets(tcpTimeout));
            String algorithms = session.getConfig("server_host_key");
            if (pin != null) algorithms = hostAlgorithms(pin.type);
            else if (!Arrays.asList(algorithms.split(",")).contains("ssh-rsa")) algorithms += ",ssh-rsa";
            session.setConfig("server_host_key", algorithms);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setHostKeyRepository(new HostKeyRepository() {
                @Override public int check(String host, byte[] offered) {
                    if (pin == null || pin.check(host, offered) == OK) observed[0] = offered.clone();
                    else if (keyChanged != null) keyChanged.set(true);
                    return NOT_INCLUDED; // Stop the handshake before any authentication.
                }
                @Override public HostKey[] getHostKey() { return new HostKey[0]; }
                @Override public HostKey[] getHostKey(String host, String type) { return getHostKey(); }
                @Override public void add(HostKey key, UserInfo user) {}
                @Override public void remove(String host, String type) {}
                @Override public void remove(String host, String type, byte[] key) {}
                @Override public String getKnownHostsRepositoryID() { return "Kindle discovery"; }
            });
            session.connect(sshTimeout);
        } catch (Exception ignored) {
            // Unreachable hosts and the deliberate abort after the key exchange are expected.
        } finally {
            if (session != null) session.disconnect();
        }
        byte[] key = observed[0];
        if (key == null) return null;
        try {
            return new Found(match, pin == null ? new Pin(new HostKey(match.host, key), key) : pin);
        } catch (com.jcraft.jsch.JSchException unsupportedKey) {
            return null;
        }
    }

    static String hostAlgorithms(String type) {
        return "ssh-rsa".equals(type) ? "rsa-sha2-512,rsa-sha2-256,ssh-rsa" : type;
    }

    private static boolean privateAddress(int address) {
        return (address >>> 24) == 10 || (address >>> 20) == 0xac1 || (address >>> 16) == 0xc0a8;
    }

    private static int number(InetAddress address) {
        int number = 0;
        for (byte octet : address.getAddress()) number = number << 8 | octet & 255;
        return number;
    }

    private static int mask(int prefix) { return -1 << (32 - prefix); }

    private static String host(int number) {
        return (number >>> 24) + "." + ((number >>> 16) & 255) + "." + ((number >>> 8) & 255) + "." + (number & 255);
    }
}
