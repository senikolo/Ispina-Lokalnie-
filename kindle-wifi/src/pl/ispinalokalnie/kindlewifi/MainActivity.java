package pl.ispinalokalnie.kindlewifi;

import android.Manifest;
import android.app.Activity;
import pl.ispinalokalnie.kindlewifi.ActionIcon.Glyph;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 101;
    private AudioLibrary audioLibrary;
    private final GuestPortalServer guestPortalServer = new GuestPortalServer();
    private static final int WIFI_NAME_PERMISSION = 102;
    private static final String HOTSPOT_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String KINDLE_FOUND = "Udało się! Kindle gotowy do przesyłania";
    private static final String DESTINATION = "/mnt/us/documents";
    private static final int INK = 0xff172d28;
    private static final int GREEN = 0xff175748;
    private static final int SUCCESS = 0xff176440;
    private static final int MUTED = 0xff546a60;
    private static final int BG = 0xfff6f5ee;
    private static final int SURFACE = 0xffffffff;
    private static final int MINT = 0xffe2f0e7;
    private static final int BORDER = 0xffe5e5da;
    private SharedPreferences settings;
    private SshIdentity sshIdentity;
    private static TransferQueue queue;
    private static final AtomicBoolean batchRunning = new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService imports = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService queueActions =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.List<ImportBatch> pendingImports = new java.util.ArrayList<>();
    private Button stopImport;

    private static final class ImportBatch {
        final android.os.CancellationSignal signal = new android.os.CancellationSignal();
        volatile boolean stopped;
        volatile InputStream source;

        void stop() {
            if (stopped) return;
            stopped = true;
            new Thread(() -> {
                InputStream current = source;
                if (current != null) {
                    try { current.close(); } catch (IOException ignored) { }
                }
                signal.cancel();
            }, "kindle-import-stop").start();
        }
    }
    private CheckBox skipIdentical;
    private FileManager fileManager;
    private ScrollView screen;
    private Button connectionButton;
    private Button connectionSettings;
    private Button deviceTools;
    private Button wifiNameButton;
    private Button hotspotNameButton;
    private TextView networkName;
    private TextView networkHint;
    private TextView kindleStatus;
    private ProgressBar discoveryProgress;
    private ProgressBar importProgress;
    private String networkLabel = "";
    private Boolean hotspotReported;
    private boolean kindleFound;
    private final BroadcastReceiver networkChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (HOTSPOT_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra("wifi_state", -1);
                if (state == 13) hotspotReported = true;
                else if (state == 11 || state == 14) hotspotReported = false;
            }
            refreshNetwork();
        }
    };
    private KindleFinder.Match detectedKindle;
    private KindleFinder.Pin detectedPin;
    private Button clearSelection;
    private Button viewQueue;
    private Button sendQueue;
    private Button retryQueue;
    private String recoveredHost;
    private TextView selectionNote;
    private TextView status;
    private TextView stateTitle;
    private TextView stateIcon;
    private Button detailsButton;
    private String lastDetails = "";
    private LinearLayout stateCard;
    private LinearLayout confirmedRows;
    private ProgressBar progress;
    private Button choose;
    private Button list;
    private boolean sending;
    private boolean recentReceiptFailed;
    private final java.util.ArrayList<Uri> sharedFiles = new java.util.ArrayList<>();
    private TextView progressLabel;
    private Button cancelButton;
    private volatile Thread transferThread;
    private volatile boolean cancellationRequested;
    private int currentFile, fileCount;
    private PdfEpubConverter.Options pdfOptions;
    private int batchPdfCount;
    private int pdfMode = 0; // 0 PDF only; 1 EPUB only; 2 PDF and EPUB

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(0xff153e36);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        settings = getSharedPreferences("kindle", MODE_PRIVATE);
        disableLegacyWeather();
        sshIdentity = new SshIdentity(getFilesDir());
        if (queue == null) {
            queue = new TransferQueue(getFilesDir());
            imports.execute(queue::cleanAbandoned);
        }

        screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setBackgroundColor(BG);
        LinearLayout frame = new LinearLayout(this);
        frame.setGravity(Gravity.CENTER_HORIZONTAL);
        screen.addView(frame);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(14), dp(18), dp(28));
        frame.addView(page, new LinearLayout.LayoutParams(-1, -2));
        frame.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(560));
            if (width > 0 && page.getLayoutParams().width != width) {
                page.getLayoutParams().width = width;
                page.requestLayout();
            }
        });
        setContentView(screen);

        LinearLayout brand = row();
        brand.addView(new AppIcon(this, AppIcon.BOOK, GREEN, MINT),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView brandName = heading("Kindle przez Wi-Fi", 18, INK);
        brandName.setPadding(dp(11), 0, 0, 0);
        brand.addView(brandName, new LinearLayout.LayoutParams(0, -2, 1));
        Button help = action("Pomoc", Glyph.HELP, GREEN, BG, 0);
        help.setOnClickListener(v -> helpConnection());
        brand.addView(help);
        add(page, brand, 0);

        LinearLayout hero = column();
        hero.setPadding(dp(20), dp(18), dp(20), dp(19));
        hero.setElevation(dp(2));
        GradientDrawable heroSurface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xff173b35, 0xff216856 });
        heroSurface.setCornerRadius(dp(26));
        hero.setBackground(heroSurface);
        add(page, connectionCard(), 10);
        deviceTools = action("Zegar Kindle’a", Glyph.SETTINGS, GREEN, SURFACE, 0);
        deviceTools.setOnClickListener(v -> showDeviceTools());
        add(page, deviceTools, 10);
        Button guestWifi = action("Wi-Fi w pociągu · TEST", Glyph.HOTSPOT, GREEN, SURFACE, 0);
        guestWifi.setOnClickListener(v -> showGuestWifiMenu());
        add(page, guestWifi, 6);
        add(page, hero, 12);
        LinearLayout heroTop = row();
        TextView heroTitle = heading("Twoja kolejka", 25, Color.WHITE);
        heroTop.addView(heroTitle, new LinearLayout.LayoutParams(0, -2, 1));
        AppIcon illustration = new AppIcon(this, AppIcon.READER, 0xffe1f6df, 0xff376f59);
        LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(dp(66), dp(66));
        art.leftMargin = dp(8);
        if (getResources().getConfiguration().fontScale < 1.4f) heroTop.addView(illustration, art);
        add(hero, heroTop, 0);

        selectionNote = label("Dodaj EPUB, MOBI lub PDF z telefonu.", 15, 0xffe0f1e7);
        selectionNote.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(hero, selectionNote, 8);
        importProgress = processIndicator(0xffd3eddb);
        add(hero, importProgress, 4);
        stopImport = action("Przerwij dodawanie", Glyph.CLOSE, Color.WHITE, 0xff376f59, 0);
        stopImport.setVisibility(View.GONE);
        stopImport.setOnClickListener(v -> {
            for (ImportBatch batch : pendingImports) batch.stop();
            updateChoose();
        });
        add(hero, stopImport, 6);
        sendQueue = action("Wyślij na Kindle", Glyph.UPLOAD, 0xff12372a, 0xffe1f6df, 0);
        sendQueue.setMinHeight(dp(54));
        sendQueue.setTextSize(17);
        sendQueue.setOnClickListener(v -> sendSelection());
        add(hero, sendQueue, 12);
        choose = action("Dodaj książki", Glyph.BOOK, Color.WHITE, 0xff376f59, 0);
        choose.setOnClickListener(v -> pickFile());
        add(hero, choose, 8);
        clearSelection = action("Wyczyść kolejkę", Glyph.CLOSE, Color.WHITE, 0xff376f59, 0);
        clearSelection.setVisibility(View.GONE);
        clearSelection.setOnClickListener(v -> {
            new DialogUi.Builder(this).setTitle("Wyczyścić kolejkę?")
                    .setMessage("Aplikacja usunie kopie oczekujące na wysłanie. Pliki źródłowe pozostaną bez zmian.")
                    .setNegativeButton("Anuluj", null).destructive().setPositiveButton("Wyczyść kolejkę", (dialog, which) -> clearQueue()).show();
        });

        viewQueue = action("Zobacz kolejkę", Glyph.HISTORY, Color.WHITE, 0xff376f59, 0);
        viewQueue.setVisibility(View.GONE);
        viewQueue.setOnClickListener(v -> showQueue());
        add(hero, viewQueue, 6);
        skipIdentical = new CheckBox(this);
        skipIdentical.setText("Pomiń identyczne pliki na Kindle");
        skipIdentical.setTextColor(0xffeef6ee);
        skipIdentical.setButtonTintList(ColorStateList.valueOf(0xffd3eddb));
        skipIdentical.setChecked(settings.getBoolean("skip_identical", true));
        skipIdentical.setOnCheckedChangeListener((button, checked) ->
                settings.edit().putBoolean("skip_identical", checked).apply());




        stateCard = column();
        stateCard.setPadding(dp(16), dp(15), dp(16), dp(15));
        stateCard.setBackground(rounded(SURFACE, 20, BORDER));
        stateCard.setVisibility(View.GONE);
        add(page, stateCard, 12);
        LinearLayout stateHeader = row();
        stateIcon = label("", 23, GREEN);
        stateIcon.setGravity(Gravity.CENTER);
        stateIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stateHeader.addView(stateIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout stateTexts = column();
        stateTexts.setPadding(dp(11), 0, 0, 0);
        stateTitle = heading("", 17, INK);
        stateTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateTexts.addView(stateTitle);
        progressLabel = label("", 13, MUTED);
        stateTexts.addView(progressLabel);
        stateHeader.addView(stateTexts, new LinearLayout.LayoutParams(0, -2, 1));
        add(stateCard, stateHeader, 0);
        status = label("", 15, INK);
        add(stateCard, status, 9);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        add(stateCard, progress, 8);
        confirmedRows = column();
        confirmedRows.setVisibility(View.GONE);
        add(stateCard, confirmedRows, 8);
        detailsButton = secondaryButton("Zobacz szczegóły", Glyph.INFO);
        detailsButton.setVisibility(View.GONE);
        detailsButton.setOnClickListener(v -> showDetails());
        add(stateCard, detailsButton, 8);
        retryQueue = secondaryButton("Spróbuj ponownie", Glyph.REFRESH);
        retryQueue.setVisibility(View.GONE);
        retryQueue.setOnClickListener(v -> sendSelection());
        add(stateCard, retryQueue, 8);
        cancelButton = secondaryButton("Przerwij", Glyph.CLOSE);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> {
            cancellationRequested = true;
            Thread worker = transferThread;
            if (worker != null) worker.interrupt();
            cancelButton.setEnabled(false);
            stateTitle.setText("Zatrzymuję wysyłanie…");
            status.setText("Kończę pracę nad bieżącym plikiem.");
        });
        add(stateCard, cancelButton, 8);

        LinearLayout library = column();
        library.setPadding(dp(14), dp(12), dp(14), dp(14));
        library.setBackground(rounded(SURFACE, 20, BORDER));
        add(library, heading("Twoje książki", 19, INK), 0);
        LinearLayout actions = column();
        list = secondaryButton("Na Kindle", Glyph.BOOK);
        list.setOnClickListener(v -> withKindle(this::showLibrary));
        add(actions, list, 0);
        Button history = secondaryButton("Ostatnio wysłane", Glyph.HISTORY);
        history.setOnClickListener(v -> showHistory());
        add(actions, history, 6);
        Button recent = secondaryButton("Ostatnio przesłane w KOReaderze", Glyph.HISTORY);
        recent.setOnClickListener(v -> confirmRecentPlugin());
        add(actions, recent, 6);
        Button gazette = secondaryButton("Gazetka na Kindle", Glyph.BOOK);
        gazette.setOnClickListener(v -> showGazette());
        add(actions, gazette, 6);
        Button readerPanel = secondaryButton("Ustawienia KOReadera", Glyph.SETTINGS);
        readerPanel.setOnClickListener(v -> showReaderPanel());
        add(actions, readerPanel, 6);
        add(library, actions, 8);
        add(page, library, 12);

        LinearLayout audio = column();
        audio.setPadding(dp(14), dp(12), dp(14), dp(14));
        audio.setBackground(rounded(SURFACE, 20, BORDER));
        add(audio, heading("Dźwięk na Kindle’u", 19, INK), 0);
        Button music = secondaryButton("Muzyka", Glyph.FOLDER);
        music.setOnClickListener(v -> withKindle(() -> showAudioLibrary(AudioFiles.MUSIC)));
        add(audio, music, 8);
        Button audiobooks = secondaryButton("Audiobooki", Glyph.BOOK);
        audiobooks.setOnClickListener(v -> withKindle(() -> showAudioLibrary(AudioFiles.BOOKS)));
        add(audio, audiobooks, 6);
        Button radio = secondaryButton("Radio", Glyph.SETTINGS);
        radio.setOnClickListener(v -> showRadioMenu());
        add(audio, radio, 6);
        Button mail = secondaryButton("Poczta i książki", Glyph.BOOK);
        mail.setOnClickListener(v -> showMailMenu());
        add(audio, mail, 6);
        add(page, audio, 12);

        lastDetails = settings.getString("lastResult", "");
        if (state == null) receiveShare(getIntent());
        updateChoose();
        if (state == null && !isReducedMotion()) {
            hero.setAlpha(0f);
            hero.setTranslationY(dp(7));
            hero.animate().alpha(1f).translationY(0f).setDuration(210)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter changes = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        changes.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        changes.addAction(HOTSPOT_STATE_CHANGED);
        Intent current = registerReceiver(networkChanges, changes);
        if (current != null && HOTSPOT_STATE_CHANGED.equals(current.getAction())) {
            networkChanges.onReceive(this, current);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshNetwork();
        queueActions.execute(() -> {
            java.util.List<File> items = queue.files();
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || sending) return;
                sharedFiles.clear();
                for (File item : items) sharedFiles.add(Uri.fromFile(item));
                updateChoose();
            });
        });
    }

    @Override protected void onStop() {
        unregisterReceiver(networkChanges);
        hotspotReported = null;
        super.onStop();
    }

    private LinearLayout connectionCard() {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(13), dp(15), dp(12));
        card.setBackground(rounded(SURFACE, 22, BORDER));
        card.setElevation(dp(1));

        LinearLayout top = row();
        top.addView(heading("Połączenie z Kindle", 18, INK),
                new LinearLayout.LayoutParams(0, -2, 1));
        connectionSettings = action("Ustawienia", Glyph.SETTINGS, GREEN, SURFACE, 0);
        connectionSettings.setOnClickListener(v -> showConnection(null));
        add(card, top, 0);

        kindleStatus = heading("", 16, SUCCESS);
        kindleStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        kindleStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        kindleStatus.setBackground(rounded(MINT, 13, 0));
        kindleStatus.setVisibility(View.GONE);
        add(card, kindleStatus, 2);
        discoveryProgress = processIndicator(GREEN);
        add(card, discoveryProgress, 3);

        networkName = heading("", 15, INK);
        networkName.setPadding(dp(10), dp(7), dp(10), dp(7));
        networkName.setBackground(rounded(0xffedf3ed, 12, 0));
        networkHint = label("", 14, MUTED);
        add(card, networkName, 7);
        add(card, networkHint, 4);

        wifiNameButton = action("Pokaż nazwę Wi‑Fi", Glyph.WIFI, GREEN, SURFACE, 0);
        wifiNameButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        wifiNameButton.setOnClickListener(v -> requestWifiName());
        add(card, wifiNameButton, 0);
        hotspotNameButton = action("Podaj nazwę hotspotu", Glyph.HOTSPOT, GREEN, SURFACE, 0);
        hotspotNameButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        hotspotNameButton.setOnClickListener(v -> editHotspotName());
        add(card, hotspotNameButton, 0);

        connectionButton = action("Znajdź Kindle", Glyph.SEARCH, GREEN, MINT, 0);
        connectionButton.setOnClickListener(v -> withKindle(null));
        add(card, connectionButton, 7);
        Button check = secondaryButton("Sprawdź połączenie", Glyph.INFO);
        check.setOnClickListener(v -> checkConnection());
        add(card, check, 6);
        add(card, connectionSettings, 2);
        return card;
    }

    private boolean hasWifiConnection() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    private String wifiName() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            return cleanNetworkName(info == null ? null : info.getSSID());
        } catch (SecurityException ignored) {
            return "";
        }
    }

    static String cleanNetworkName(String value) {
        if (value == null) return "";
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        return "<unknown ssid>".equalsIgnoreCase(value) ? "" : value;
    }

    private void refreshNetwork() {
        if (networkName == null) return;
        boolean wifi = hasWifiConnection();
        boolean hotspot = hotspotReported != null ? hotspotReported : new KindleFinder(this).hasHotspot();
        String wifiLabel = wifi ? wifiName() : "";
        String hotspotLabel = settings.getString("hotspot_name", "");
        String line;
        if (hotspot) {
            line = hotspotLabel.isEmpty() ? "Tryb hotspotu • włączony" : "Tryb hotspotu • " + hotspotLabel;
            networkHint.setText(hotspotLabel.isEmpty()
                    ? "Podłącz Kindle do hotspotu tego telefonu. Nazwę sprawdzisz w ustawieniach hotspotu."
                    : "Na Kindle wybierz hotspot „" + hotspotLabel + "” udostępniany przez ten telefon.");
        } else if (wifi) {
            line = wifiLabel.isEmpty() ? "Telefon jest połączony z Wi‑Fi" : "Wi‑Fi telefonu • " + wifiLabel;
            networkHint.setText(wifiLabel.isEmpty() ? "Podłącz Kindle do tej samej sieci Wi‑Fi."
                    : "Na Kindle wybierz tę samą sieć „" + wifiLabel + "”.");
        } else {
            line = "Brak połączenia z Wi‑Fi";
            networkHint.setText(hotspotLabel.isEmpty()
                    ? "Połącz telefon i Kindle z jedną siecią albo włącz hotspot na tym telefonie."
                    : "Jeśli udostępniasz Wi‑Fi, wybierz na Kindle zapisany hotspot „" + hotspotLabel + "”.");
        }
        if (!networkLabel.equals(line) && kindleFound) showKindleStatus("", false);
        networkLabel = line;
        networkName.setText(line);
        networkName.setBackground(rounded(hotspot ? 0xffffefcf : 0xffedf3ed, 12, 0));
        ActionIcon.attach(networkName, hotspot ? Glyph.HOTSPOT : Glyph.WIFI);
        wifiNameButton.setVisibility(!hotspot && wifi && wifiLabel.isEmpty() ? View.VISIBLE : View.GONE);
        hotspotNameButton.setVisibility(hotspot || !wifi ? View.VISIBLE : View.GONE);
        hotspotNameButton.setText(hotspotLabel.isEmpty() ? "Podaj nazwę hotspotu" : "Zmień nazwę hotspotu");
    }

    private enum ConnectionCheck {
        READY("Kindle jest połączony", "Czytnik odpowiada i można otworzyć folder książek."),
        NOT_SAVED("Najpierw znajdź Kindle’a", "Aplikacja potrzebuje adresu Twojego czytnika."),
        CONFIRM("Kindle odpowiada", "Potwierdź czytnik, aby zapisać go w aplikacji."),
        UNREACHABLE("Kindle nie odpowiada", "Nie udało się połączyć z czytnikiem pod zapisanym adresem."),
        CHANGED("Sprawdź, czy to Twój Kindle", "Urządzenie pod zapisanym adresem ma inny identyfikator."),
        SIGN_IN("Kindle odpowiada", "Aplikacja nie mogła zalogować się do czytnika."),
        FOLDER("Kindle odpowiada", "Aplikacja nie mogła otworzyć folderu książek.");

        final String title;
        final String message;

        ConnectionCheck(String title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    private void checkConnection() {
        if (sending) return;
        refreshNetwork();
        String host = savedAddress();
        if (!privateAddress(host)) {
            showConnectionCheck(ConnectionCheck.NOT_SAVED, null);
            return;
        }
        busy(true);
        showKindleStatus("Sprawdzam połączenie…", false);
        new Thread(() -> {
            ConnectionCheck result;
            Exception error = null;
            Session session = null;
            ChannelSftp files = null;
            int stage = 1;
            try {
                KindleFinder.Pin pin = pairedKindle(host);
                KindleFinder.Found reader = new KindleFinder(this).findAt(host, pin);
                if (reader == null) {
                    result = ConnectionCheck.UNREACHABLE;
                } else if (pin == null) {
                    result = ConnectionCheck.CONFIRM;
                } else {
                    stage = 2;
                    session = connect(host, reader.match, pin);
                    stage = 3;
                    files = (ChannelSftp) session.openChannel("sftp");
                    files.connect(6000);
                    result = files.stat("/mnt/us/koreader").isDir() && files.stat(DESTINATION).isDir()
                            ? ConnectionCheck.READY : ConnectionCheck.FOLDER;
                }
            } catch (Exception problem) {
                error = problem;
                if (problem instanceof KindleFinder.ScanIssue) result = ConnectionCheck.CHANGED;
                else if (stage == 1) result = ConnectionCheck.UNREACHABLE;
                else if (stage == 2) result = ConnectionCheck.SIGN_IN;
                else result = ConnectionCheck.FOLDER;
            } finally {
                if (files != null) files.disconnect();
                if (session != null) session.disconnect();
            }
            ConnectionCheck checked = result;
            Exception failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy(false);
                showKindleStatus("", false);
                showConnectionCheck(checked, failure);
            });
        }, "kindle-diagnostics").start();
    }

    private void showConnectionCheck(ConnectionCheck check, Exception error) {
        boolean ready = check == ConnectionCheck.READY;
        ResultPanel panel = new ResultPanel("Sprawdzenie połączenia", check.title, check.message,
                ready ? ResultTone.SUCCESS : ResultTone.ATTENTION);
        panel.section("Sieć telefonu", networkLabel, Glyph.WIFI);
        if (error != null) panel.details(technicalMessage(error));
        switch (check) {
            case READY:
                panel.section("Co dalej?", "Możesz już wysyłać i przeglądać książki.", Glyph.BOOK);
                panel.show("Wróć do aplikacji", null, null, null);
                break;
            case NOT_SAVED:
            case CONFIRM:
                panel.section("Co dalej?", "Wyszukaj czytnik i porównaj wyświetlony adres z adresem w KOReaderze.", Glyph.HELP);
                panel.show("Znajdź Kindle", () -> withKindle(null), "Zamknij", null);
                break;
            case CHANGED:
                panel.section("Co dalej?", "Odczytaj aktualny adres na ekranie Kindle’a i wpisz go w aplikacji.", Glyph.HELP);
                panel.show("Wpisz adres Kindle’a", () -> showConnection(null), "Zamknij", null);
                break;
            case UNREACHABLE:
                panel.section("Co dalej?", "Wybudź Kindle’a. Sprawdź, czy korzysta z tej samej sieci "
                        + "co telefon lub z jego hotspotu. W KOReaderze włącz serwer SSH.", Glyph.HELP);
                panel.show("Znajdź Kindle ponownie", () -> withKindle(null),
                        "Wpisz adres Kindle’a", () -> showConnection(null));
                break;
            case SIGN_IN:
            case FOLDER:
                String tip = check == ConnectionCheck.SIGN_IN
                        ? "Sprawdź ustawienia serwera SSH w KOReaderze i ponów sprawdzenie."
                        : "Otwórz KOReadera na Kindle’u, zostaw czytnik wybudzony i ponów sprawdzenie.";
                panel.section("Co dalej?", tip, Glyph.HELP);
                panel.show("Sprawdź ponownie", this::checkConnection, "Zamknij", null);
                break;
        }
    }

    private void requestWifiName() {
        boolean allowed = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        DialogUi.Builder dialog = new DialogUi.Builder(this).setTitle("Nazwa sieci Wi‑Fi")
                .setNegativeButton("Zamknij", null);
        if (allowed) {
            dialog.setMessage("Android nie udostępnił nazwy sieci. Sprawdź, czy w telefonie jest włączona lokalizacja.")
                    .setPositiveButton("Ustawienia lokalizacji", (d, which) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        } else {
            dialog.setMessage("Android wymaga zgody na dokładną lokalizację, żeby pokazać nazwę Wi‑Fi. Książki możesz wysyłać także bez tej zgody.")
                    .setPositiveButton("Pokaż nazwę", (d, which) -> requestPermissions(
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION}, WIFI_NAME_PERMISSION));
        }
        dialog.show();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == WIFI_NAME_PERMISSION) refreshNetwork();
    }

    private void editHotspotName() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Nazwa widoczna w ustawieniach telefonu");
        name.setText(settings.getString("hotspot_name", ""));
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), 0);
        add(content, name, 6);
        new DialogUi.Builder(this).setTitle("Nazwa hotspotu")
                .setMessage("Wpisz nazwę hotspotu widoczną w ustawieniach telefonu. Aplikacja ją zapamięta.")
                .setView(content).setNegativeButton("Anuluj", null)
                .setNeutralButton("Ustawienia hotspotu", (d, which) -> {
                    try { startActivity(new Intent("android.settings.TETHER_SETTINGS")); }
                    catch (android.content.ActivityNotFoundException error) {
                        startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                    }
                })
                .setPositiveButton("Zapisz", (d, which) -> {
                    settings.edit().putString("hotspot_name", cleanNetworkName(name.getText().toString())).apply();
                    refreshNetwork();
                }).show();
    }

    private void showKindleStatus(String message, boolean success) {
        kindleFound = success;
        kindleStatus.setText(message);
        kindleStatus.setTextColor(success ? SUCCESS : GREEN);
        ActionIcon.attach(kindleStatus, success ? Glyph.CHECK : Glyph.SEARCH);
        kindleStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        boolean searching = !success && (message.startsWith("Szukam") || message.startsWith("Sprawdzam"));
        discoveryProgress.setVisibility(searching && !isReducedMotion() ? View.VISIBLE : View.GONE);
        if (success && !isReducedMotion()) {
            kindleStatus.animate().cancel();
            kindleStatus.setAlpha(0.5f);
            kindleStatus.animate().alpha(1f).setDuration(180).start();
        } else {
            kindleStatus.animate().cancel();
            kindleStatus.setAlpha(1f);
        }
    }

    private ProgressBar processIndicator(int tint) {
        ProgressBar indicator = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        indicator.setIndeterminate(true);
        indicator.setIndeterminateTintList(ColorStateList.valueOf(tint));
        indicator.setVisibility(View.GONE);
        return indicator;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView heading(String text, int size, int color) {
        TextView view = label(text, size, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private boolean isReducedMotion() {
        return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    private String savedAddress() {
        return settings.getString("address", "");
    }

    private void showConnection(Runnable onSaved) {
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(8));
        add(fields, label("Jeśli wyszukiwanie nie działa, możesz wpisać adres z KOReadera.", 15, INK), 0);
        EditText address = new EditText(this);
        address.setTextColor(INK);
        address.setTextSize(17);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("Adres Kindle, np. 192.168.1.25");
        address.setText(savedAddress());
        address.setSelectAllOnFocus(true);
        add(fields, address, 8);
        Button help = action("Jak znaleźć adres?", Glyph.HELP, GREEN, SURFACE, 0);
        help.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        help.setOnClickListener(v -> helpConnection());
        add(fields, help, 4);
        if (skipIdentical.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) skipIdentical.getParent()).removeView(skipIdentical);
        skipIdentical.setTextColor(INK);
        skipIdentical.setButtonTintList(ColorStateList.valueOf(GREEN));
        add(fields, skipIdentical, 6);
        DialogUi.Builder builder = new DialogUi.Builder(this).setTitle("Połączenie z Kindle")
                .setView(fields).setNegativeButton("Anuluj", null)
                .setPositiveButton("Połącz z tym adresem", null);
        builder.setNeutralButton("Klucz SSH", (dialog, which) -> showKeySettings());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = address.getText().toString().trim();
            if (!privateAddress(host)) {
                address.setError("Wpisz adres z KOReadera, np. 192.168.1.25.");
                address.requestFocus();
                return;
            }
            dialog.dismiss();
            connectAtAddress(host, onSaved, false);
        }));
        dialog.show();
    }

    private void connectAtAddress(String host, Runnable onReady, boolean replaceKey) {
        busy(true);
        showKindleStatus("Sprawdzam adres " + host + "…", false);
        new Thread(() -> {
            KindleFinder.Found found = null;
            boolean known = false;
            boolean keyChanged = false;
            Exception technical = null;
            String problem = "Sprawdź adres na Kindle’u, połącz oba urządzenia z tą samą siecią "
                    + "i uruchom w KOReaderze serwer SSH na porcie 2222. Na czas próby zostaw czytnik wybudzony.";
            try {
                String previous = savedAddress();
                KindleFinder.Pin pin = replaceKey || previous.isEmpty() ? null : pairedKindle(previous);
                known = pin != null;
                found = new KindleFinder(this).findAt(host, pin);
            } catch (KindleFinder.ScanIssue issue) {
                keyChanged = true;
                problem = issue.getMessage();
            } catch (Exception error) {
                technical = error;
            }
            String extra = technical == null ? "" : technicalMessage(technical);
            KindleFinder.Found reader = found;
            boolean paired = known;
            boolean changed = keyChanged;
            String details = problem;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy(false);
                if (reader != null) {
                    if (paired) pairDiscoveredKindle(reader, onReady);
                    else confirmDiscoveredKindle(reader, onReady);
                    return;
                }
                showKindleStatus("", false);
                DialogUi.Builder result = new DialogUi.Builder(this).setTitle("Nie udało się połączyć z " + host)
                        .setMessage(details).setDetails(extra).setNegativeButton("Zamknij", null)
                        .setPositiveButton("Wpisz adres Kindle’a", (d, w) -> showConnection(onReady));
                if (changed) result.setNeutralButton("Sprawdź nowy klucz",
                        (d, w) -> connectAtAddress(host, onReady, true));
                result.show();
            });
        }, "kindle-manual-discovery").start();
    }

    private void showKeySettings() {
        if (sending) return;
        busy(true);
        new Thread(() -> {
            String key = null;
            try {
                key = sshIdentity.publicKey();
            } catch (Exception ignored) {
                // The dialog can show a useful error without exposing key or path details.
            }
            String publicKey = key;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy(false);
                if (publicKey == null) {
                    new DialogUi.Builder(this).setTitle("Nie udało się przygotować klucza")
                            .setPositiveButton("Zamknij", null).show();
                    return;
                }
                LinearLayout fields = column();
                fields.setPadding(dp(20), dp(12), dp(20), dp(12));
                add(fields, label("Skopiuj klucz publiczny i dopisz go jako osobny wiersz do pliku settings/SSH/authorized_keys w katalogu KOReadera. Na Kindle możesz zrobić to przez USB. Po sprawdzeniu połączenia wybierz w KOReaderze tryb „tylko klucze”.", 15, INK), 0);
                Button copy = secondaryButton("Skopiuj klucz publiczny", Glyph.INFO);
                copy.setOnClickListener(v -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("Klucz publiczny Kindle", publicKey));
                    android.widget.Toast.makeText(this, "Skopiowano klucz publiczny", android.widget.Toast.LENGTH_SHORT).show();
                });
                add(fields, copy, 8);
                CheckBox enabled = new CheckBox(this);
                enabled.setText("Używaj klucza do logowania");
                enabled.setChecked(settings.getBoolean("ssh_use_key", false));
                add(fields, enabled, 8);
                ScrollView scroll = new ScrollView(this);
                scroll.addView(fields);
                new DialogUi.Builder(this).setTitle("Logowanie kluczem SSH").setView(scroll)
                        .setNegativeButton("Anuluj", null)
                        .setPositiveButton("Zapisz", (d, which) -> settings.edit()
                                .putBoolean("ssh_use_key", enabled.isChecked()).apply()).show();
            });
        }, "kindle-user-key").start();
    }

    private void sendSelection() {
        if (sending || sharedFiles.isEmpty()) return;
        withKindle(() -> prepareBatch(new java.util.ArrayList<>(sharedFiles)));
    }

    private JSch knownKindleHosts() throws Exception {
        JSch ssh = new JSch();
        File knownHosts = new File(getFilesDir(), "known_hosts");
        if (!knownHosts.exists()) knownHosts.createNewFile();
        ssh.setKnownHosts(knownHosts.getPath());
        return ssh;
    }

    private KindleFinder.Pin pairedKindle(String host) throws Exception {
        String encoded = settings.getString("kindle_key", "");
        if (encoded.isEmpty()) {
            HostKey[] keys = knownKindleHosts().getHostKeyRepository()
                    .getHostKey("[" + host + "]:" + KindleFinder.PORT, null);
            if (keys != null) {
                for (HostKey key : keys) {
                    if (key.getMarker().isEmpty()) {
                        encoded = key.getKey();
                        break;
                    }
                }
            }
        }
        if (encoded.isEmpty()) return null;
        byte[] key = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
        return new KindleFinder.Pin(new HostKey(host, key), key);
    }

    private void withKindle(Runnable onReady) {
        if (sending) return;
        String saved = savedAddress();
        String host = privateAddress(saved) ? saved : "";
        busy(true);
        refreshNetwork();
        showKindleStatus("Szukam Twojego Kindle’a…", false);
        new Thread(() -> {
            KindleFinder.Pin pin = null;
            java.util.List<KindleFinder.Found> devices = java.util.Collections.emptyList();
            String problem = "Połącz Kindle z tą samą siecią Wi‑Fi albo z hotspotem telefonu. W KOReaderze włącz serwer SSH i spróbuj ponownie.";
            try {
                if (!host.isEmpty()) pin = pairedKindle(host);
                devices = new KindleFinder(this).find(host, pin);
            } catch (KindleFinder.ScanIssue issue) {
                problem = issue.getMessage();
            } catch (Exception ignored) {
                // Manual connection is still available if Android hides network details.
            }
            boolean known = pin != null;
            String details = problem;
            java.util.List<KindleFinder.Found> found = devices;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy(false);
                if (found.isEmpty()) {
                    detectedKindle = null;
                    detectedPin = null;
                    showKindleStatus("", false);
                    new DialogUi.Builder(this).setTitle("Nie udało się znaleźć Kindle’a")
                            .setMessage(details)
                            .setNegativeButton("Zamknij", null)
                            .setPositiveButton("Wpisz adres", (d, which) ->
                                    showConnection(onReady)).show();
                } else if (known) {
                    pairDiscoveredKindle(found.get(0), onReady);
                } else {
                    showKindleStatus("Potwierdź swój czytnik", false);
                    if (found.size() == 1) confirmDiscoveredKindle(found.get(0), onReady);
                    else chooseDiscoveredKindle(found, onReady);
                }
            });
        }, "kindle-discovery").start();
    }

    private void chooseDiscoveredKindle(java.util.List<KindleFinder.Found> found, Runnable onReady) {
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = "Urządzenie „" + found.get(i).match.host + "”";
        new DialogUi.Builder(this).setTitle("Wybierz swój Kindle")
                .setItems(names, (dialog, index) -> confirmDiscoveredKindle(found.get(index), onReady))
                .setNegativeButton("Anuluj", (dialog, which) -> showKindleStatus("", false))
                .setOnCancelListener(dialog -> showKindleStatus("", false)).show();
    }

    private void confirmDiscoveredKindle(KindleFinder.Found reader, Runnable onReady) {
        new DialogUi.Builder(this).setTitle("Czy to Twój Kindle?")
                .setMessage("Adres znalezionego czytnika\n" + reader.match.host
                        + "\n\nJeśli adres na ekranie KOReadera jest taki sam, wybierz „Połącz z tym Kindle’em”.")
                .setDetails("Odcisk klucza SSH: " + reader.fingerprint())
                .setPositiveButton("Połącz z tym Kindle’em", (dialog, which) -> pairDiscoveredKindle(reader, onReady))
                .setNegativeButton("Anuluj", (dialog, which) -> showKindleStatus("", false))
                .setOnCancelListener(dialog -> showKindleStatus("", false)).show();
    }

    private void pairDiscoveredKindle(KindleFinder.Found reader, Runnable onReady) {
        busy(true);
        showKindleStatus("Sprawdzam połączenie z Kindle…", false);
        new Thread(() -> {
            Session session = null;
            ChannelSftp files = null;
            boolean kindle = false;
            try {
                session = connect(reader.match.host, reader.match, reader.pin);
                files = (ChannelSftp) session.openChannel("sftp");
                files.connect(6000);
                kindle = files.stat("/mnt/us/koreader").isDir() && files.stat(DESTINATION).isDir();
            } catch (Exception ignored) {
                // The address could belong to a different SSH device or have disconnected.
            } finally {
                if (files != null) files.disconnect();
                if (session != null) session.disconnect();
            }
            boolean verified = kindle;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy(false);
                if (verified) useKindle(reader, onReady);
                else {
                    showKindleStatus("", false);
                    new DialogUi.Builder(this).setTitle("Nie udało się połączyć z Kindle’em")
                            .setMessage("Sprawdź, czy wybrane urządzenie to Kindle i czy serwer SSH w KOReaderze jest włączony.")
                            .setPositiveButton("Spróbuj ponownie", (dialog, which) -> withKindle(onReady))
                            .setNegativeButton("Zamknij", null).show();
                }
            });
        }, "kindle-pairing").start();
    }

    private void useKindle(KindleFinder.Found reader, Runnable onReady) {
        detectedKindle = reader.match;
        detectedPin = reader.pin;
        settings.edit().putString("address", reader.match.host).putString("kindle_key",
                android.util.Base64.encodeToString(reader.pin.key, android.util.Base64.NO_WRAP)).apply();
        refreshNetwork();
        showKindleStatus(KINDLE_FOUND, true);
        android.widget.Toast.makeText(this, KINDLE_FOUND, android.widget.Toast.LENGTH_SHORT).show();
        if (onReady != null) onReady.run();
    }

    private void disableLegacyWeather() {
        // Upgrade migration: remove the previous periodic/immediate weather jobs.
        // Leave unrelated Kindle/KOReader settings, radio and email accounts intact.
        android.app.job.JobScheduler scheduler =
                (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(215031);
            scheduler.cancel(215032);
        }
        settings.edit()
                .remove("weather_reader_key")
                .remove("weather_sent_at")
                .remove("weather_try_at")
                .remove("weather_try_result")
                .remove("weather_report")
                .remove("weather_fetched_at")
                .remove("weather_sent_forecast")
                .apply();
    }

    private void showDeviceTools() {
        if (sending) return;
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog = new DialogUi.Builder(this).setTitle("Zegar Kindle’a")
                .setView(scroll).setNegativeButton("Zamknij", null).create();
        TextView clock = heading("Zegar Kindle’a", 16, GREEN);
        markHeading(clock);
        add(fields, clock, 0);
        Button checkTime = secondaryButton("Sprawdź i ustaw zegar", Glyph.HISTORY);
        checkTime.setOnClickListener(v -> {
            dialog.dismiss();
            withKindle(this::showClockComparison);
        });
        add(fields, checkTime, 6);
        Button warsawClock = secondaryButton("Czas warszawski w KOReaderze", Glyph.HISTORY);
        warsawClock.setOnClickListener(v -> {
            dialog.dismiss();
            withKindle(this::showWarsawClockPatch);
        });
        add(fields, warsawClock, 6);
        dialog.show();
    }

    private interface DeviceAction<T> { T run() throws Exception; }

    private <T> void deviceAction(String working, DeviceAction<T> action, java.util.function.Consumer<T> done) {
        busy(true);
        showKindleStatus(working, false);
        new Thread(() -> {
            try {
                T result = action.run();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy(false);
                    showKindleStatus("", false);
                    done.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy(false);
                    showKindleStatus("", false);
                    if (error instanceof ReaderPanel.StateUnavailable) {
                        String[] parts = error.getMessage().split("\n\n", 2);
                        ResultPanel panel = new ResultPanel("Ustawienia KOReadera",
                                "Ustawienia są niedostępne", parts[0], ResultTone.ATTENTION);
                        if (parts.length == 2) panel.details(parts[1]);
                        panel.show("Zamknij", null, null, null);
                        return;
                    }
                    String message = error instanceof JSchException || error instanceof SftpException
                            ? userMessage(error)
                            : "Operacja nie została ukończona. Sprawdź szczegóły techniczne.";
                    ResultPanel panel = new ResultPanel("", "Nie udało się dokończyć", message, ResultTone.ATTENTION);
                    panel.details(technicalMessage(error));
                    panel.show("Zamknij", null, null, null);
                });
            }
        }, "kindle-device-tools").start();
    }

    private Session openPairedDevice() throws Exception {
        String host = savedAddress();
        if (!privateAddress(host) || settings.getString("kindle_key", "").isEmpty())
            throw new IOException("Najpierw użyj przycisku „Znajdź Kindle” i potwierdź czytnik.");
        KindleFinder.Match route = detectedKindle;
        if (route == null || !host.equals(route.host)) route = new KindleFinder(this).localRoute(host);
        return connect(host, route, pairedKindle(host));
    }

    private static String warsawTime(long epoch) {
        return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm:ss")
                .withZone(java.time.ZoneId.of("Europe/Warsaw")).format(java.time.Instant.ofEpochSecond(epoch));
    }

    private void showClockComparison() {
        deviceAction("Odczytuję zegar Kindle’a…", () -> {
            Session session = openPairedDevice();
            try { return KindleTools.readClock(session); }
            finally { session.disconnect(); }
        }, reading -> showClockResult(reading, false));
    }

    private void showClockResult(KindleTools.ClockReading reading, boolean updated) {
        long now = java.time.Instant.now().getEpochSecond();
        boolean synced = reading.isSynchronized(now);
        String warsaw = KindleTools.warsawClock(synced ? reading.epoch : now);
        boolean sameZone = reading.displaysWarsawTime();
        String title = synced && !sameZone ? "Zegar zsynchronizowany, inna strefa"
                : updated && synced ? "Zegar został ustawiony"
                : synced ? "Zegar zsynchronizowany" : "Zegar wymaga ustawienia";
        String summary = synced ? "Zegary Kindle’a i telefonu są zsynchronizowane."
                : "Zegar Kindle’a różni się od zegara telefonu.";
        ResultPanel panel = new ResultPanel("Zegar Kindle’a", title, summary,
                synced && sameZone ? ResultTone.SUCCESS : ResultTone.ATTENTION);
        panel.section("Godzina systemowa Kindle’a", reading.localTime, Glyph.HISTORY);
        panel.section("Godzina w Warszawie", warsaw, Glyph.HISTORY);
        if (!sameZone) panel.section("Inna strefa czasowa",
                "Zegar systemowy używa innej strefy. Czas wyświetlany w KOReaderze można ustawić osobno przez Wi-Fi.",
                Glyph.INFO);
        if (synced && !sameZone) {
            panel.show("Czas warszawski w KOReaderze", this::showWarsawClockPatch, "Zamknij", null);
            return;
        }
        if (updated) {
            panel.show("Zamknij", null, null, null);
            return;
        }
        Runnable update = () -> deviceAction("Ustawiam zegar Kindle’a…", () -> {
            Session session = openPairedDevice();
            try {
                KindleTools.setTime(session);
                return KindleTools.readClock(session);
            } finally { session.disconnect(); }
        }, result -> showClockResult(result, true));
        if (synced) panel.show("Zamknij", null, "Ustaw zegar z telefonu", update);
        else panel.show("Ustaw zegar z telefonu", update, "Zamknij", null);
    }

    private void showWarsawClockPatch() {
        changeWarsawClockPatch(WarsawClockPatch.Action.CHECK, status -> {
            if (status == WarsawClockPatch.Status.CONFLICT) {
                showWarsawClockConflict();
                return;
            }
            boolean installed = status == WarsawClockPatch.Status.INSTALLED;
            ResultPanel panel = new ResultPanel("Czas warszawski w KOReaderze",
                    installed ? "Poprawka jest na Kindle’u" : "Ustaw czas warszawski",
                    installed ? "Uruchom ponownie KOReadera z KUAL, jeśli od instalacji był nadal otwarty."
                            : "Po instalacji KOReader pokaże czas warszawski z automatyczną zmianą czasu letniego i zimowego.",
                    ResultTone.INFO);
            panel.section("Gdzie zobaczysz zmianę?", "Na pasku zegara w KOReaderze. "
                    + "Ekran zegara w telefonie nadal odczytuje godzinę systemową Kindle’a.", Glyph.HISTORY);
            if (installed) {
                panel.show("Zamknij", null, "Cofnij poprawkę przez Wi-Fi", () -> changeWarsawClockPatch(
                        WarsawClockPatch.Action.REMOVE, result -> {
                            if (result == WarsawClockPatch.Status.CONFLICT) showWarsawClockConflict();
                            else showDeviceResult("Poprawka została cofnięta",
                                    "Uruchom ponownie KOReadera z KUAL, aby wrócić do godziny systemowej.");
                        }));
            } else {
                panel.show("Zainstaluj przez Wi-Fi", () -> changeWarsawClockPatch(
                        WarsawClockPatch.Action.INSTALL, result -> {
                            if (result == WarsawClockPatch.Status.CONFLICT) showWarsawClockConflict();
                            else showDeviceResult("Poprawka jest na Kindle’u",
                                    "Zamknij KOReadera i uruchom go ponownie z KUAL. "
                                    + "Sprawdź godzinę na pasku w KOReaderze.");
                        }), "Anuluj", null);
            }
        });
    }

    private void changeWarsawClockPatch(WarsawClockPatch.Action action,
                                        java.util.function.Consumer<WarsawClockPatch.Status> done) {
        String working;
        switch (action) {
            case INSTALL: working = "Wgrywam poprawkę zegara przez Wi-Fi…"; break;
            case REMOVE: working = "Cofam poprawkę zegara…"; break;
            default: working = "Sprawdzam poprawkę zegara…";
        }
        deviceAction(working, () -> {
            Session session = openPairedDevice();
            try { return WarsawClockPatch.run(this, session, action); }
            finally { session.disconnect(); }
        }, done);
    }

    private void showWarsawClockConflict() {
        showDeviceResult("Na Kindle’u jest inna poprawka zegara",
                "Plik o tej samej nazwie ma inną zawartość. Zmień jego nazwę, jeśli chcesz zainstalować tę poprawkę.");
    }

    private void showDeviceResult(String title, String message) {
        String[] parts = message.split("\n\nSzczegóły z KOReadera: ", 2);
        ResultPanel panel = new ResultPanel("", title, parts[0], ResultTone.INFO);
        if (parts.length == 2) panel.details(parts[1]);
        panel.show("Zamknij", null, null, null);
    }

    private void showAudioLibrary(String folder) {
        if (sending || audioLibrary != null) return;
        busy(true);
        audioLibrary = new AudioLibrary(this, folder, this::openPairedDevice, () -> {
            audioLibrary = null;
            busy(false);
        });
        audioLibrary.show();
    }

    private void showMailMenu() {
        if (sending) return;
        String[] options = {
                "Zainstaluj lub zaktualizuj pocztę w KOReaderze",
                "Jak korzystać z poczty bez telefonu?",
                "Bezpieczeństwo i ustawienia konta"
        };
        new DialogUi.Builder(this).setTitle("Poczta i książki · TEST")
                .setItems(options, (dialog, selected) -> {
                    switch (selected) {
                        case 0:
                            withKindle(() -> deviceAction("Instaluję pocztę na Kindle przez Wi-Fi…", () -> {
                                Session session = openPairedDevice();
                                try { return MailInstaller.install(this, session); }
                                finally { session.disconnect(); }
                            }, result -> showDeviceResult("Poczta na Kindle", result
                                    + "\n\nUruchom ponownie KOReadera. W menu Narzędzia → Więcej narzędzi wybierz „Poczta i książki”."
                                    + "\nTelefon nie musi być potem włączony.")));
                            break;
                        case 1:
                            showDeviceResult("Poczta bez telefonu",
                                    "Telefon służy tylko do pierwszej instalacji wtyczki. Później Kindle "
                                    + "łączy się samodzielnie przez Wi-Fi z serwerem IMAP i SMTP. "
                                    + "Możesz odebrać wiadomość, przeczytać ją, odpisać nową wiadomością "
                                    + "oraz zapisać załącznik EPUB/PDF w dokumentach czytnika.");
                            break;
                        default:
                            showDeviceResult("Ustawienia i bezpieczeństwo",
                                    "Wtyczka na Kindle wymaga adresu konta, serwerów IMAP 993 i SMTP 465 "
                                    + "oraz hasła aplikacji. Hasło podaje się wyłącznie na Kindle; "
                                    + "aplikacja telefoniczna nie zbiera go ani nie przechowuje. "
                                    + "Nie wyłączamy sprawdzania certyfikatów TLS. "
                                    + "To wersja testowa wymagająca próby na fizycznym Kindle Keyboard 3.");
                    }
                }).setNegativeButton("Zamknij", null).show();
    }


    private void showGuestWifiMenu() {
        if (sending) return;
        String[] options={
            "Zainstaluj / zaktualizuj IL Wi-Fi Gość",
            "Uruchom symulację · dostęp jako gość",
            "Uruchom symulację · formularz e-mail",
            "Status portalu testowego",
            "Zatrzymaj portal testowy",
            "Instrukcja lokalnego testu",
            "Ograniczenia prawdziwych sieci"
        };
        new DialogUi.Builder(this).setTitle("IL Wi-Fi Gość · TEST")
            .setItems(options,(dialog,index)->{
                switch(index){
                    case 0: withKindle(this::installGuestWifi);break;
                    case 1: launchGuestTest(false);break;
                    case 2: launchGuestTest(true);break;
                    case 3: showGuestTestStatus();break;
                    case 4: stopGuestTest();break;
                    case 5: showDeviceResult("Test Wi-Fi bez internetu",
                        "1. Włącz hotspot telefonu (pasmo 2,4 GHz). Wyłącz dane mobilne, jeśli chcesz.\n\n"
                        +"2. Uruchom w tej aplikacji symulację gościa lub e-mail.\n\n"
                        +"3. Połącz Kindle z hotspotem telefonu.\n\n"
                        +"4. W KOReaderze wybierz IL Wi-Fi Gość → Test lokalnego portalu na telefonie.\n\n"
                        +"5. Otwórz wykryty portal, zapoznaj się z treścią i zaakceptuj testowy formularz.\n\n"
                        +"6. Kindle sprawdzi odpowiedź lokalnego serwera; przy sukcesie wyświetli TEST OK.\n\n"
                        +"Pozostaw aplikację otwartą do zakończenia próby. "
                        +"Ten test nie zapewnia internetu i nie sprawdza automatycznego przekierowania DNS.");break;
                    default: showDeviceResult("Ograniczenia",
                        "Lokalna symulacja sprawdza odczyt i wysyłanie formularzy HTTP na Kindle. "
                        +"Nie jest prawdziwym captive portalem, nie przechwytuje ruchu i nie "
                        +"potwierdza zgodności z siecią konkretnego pociągu. "
                        +"Wymagający JavaScriptu/CAPTCHA/TLS portal może nadal nie działać.");
                }
            }).setNegativeButton("Zamknij",null).show();
    }

    private void launchGuestTest(boolean emailMode) {
        try {
            guestPortalServer.start();
            guestPortalServer.setEmailMode(emailMode);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showGuestTestStatus();
        } catch (java.io.IOException error) {
            showDeviceResult("Nie uruchomiono portalu",
                "Port TCP 8765 jest zajęty albo serwer nie może nasłuchiwać. "
                +"Wyłącz inne serwery w telefonie i ponów próbę.");
        }
    }

    private void stopGuestTest() {
        guestPortalServer.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showDeviceResult("Portal testowy", "Zatrzymano lokalny serwer. "
            +"Nie zapisano adresu e-mail ani historii logowania.");
    }

    private void showGuestTestStatus() {
        if(!guestPortalServer.isRunning()) {
            showDeviceResult("Portal testowy wyłączony",
                "Uruchom najpierw „Symulację · dostęp jako gość” lub „Symulację · formularz e-mail”.");
            return;
        }
        String mode=guestPortalServer.isEmailMode()?"Formularz e-mail":"Dostęp jako gość";
        showDeviceResult("Portal testowy uruchomiony",
            "Tryb: "+mode+"\n"
            +"Port: 8765 · tylko w Twojej sieci lokalnej\n"
            +"Przyjęte formularze: "+guestPortalServer.getAccepted()+"\n\n"
            +"Włącz hotspot 2,4 GHz. Połącz Kindle z tym hotspotem, następnie "
            +"otwórz IL Wi-Fi Gość → „Test lokalnego portalu na telefonie”. "
            +"Adres telefonu wtyczka ustali z bramy sieciowej Wi-Fi.\n\n"
            +"Nie potrzeba internetu mobilnego ani komputera. "
            +"Pozostaw aplikację na pierwszym planie. "
            +"Po teście wybierz „Zatrzymaj portal testowy”.");
    }

    private void installGuestWifi() {
        deviceAction("Instaluję IL Wi-Fi Gość przez Wi-Fi…", () -> {
            Session session = openPairedDevice();
            try {
                CompanionFiles.install(this, session, "ilwifi_guest.koplugin",
                        "a4e91075c750dcf004d2b0c3402a19d4bd139775a3125ee943d6aeb97ecf82c6",
                        "a56c371d0345f99bdfa19d9c990eb1422a2761c4e43406202a4be45ec7aeafca",
                        "ea59003d22c89b8eee0378e87dc2cdcc345e5e22f521285167e03f54a9a552a7");
                return "Wtyczka została przesłana i sprawdzona. "
                        + "Zamknij KOReadera i uruchom ponownie przez KUAL. "
                        + "W menu Narzędzia → Więcej narzędzi wybierz „IL Wi-Fi Gość · TEST”. "
                        + "Późniejsze testy odbywają się bez udziału telefonu.";
            } finally {
                session.disconnect();
            }
        }, result -> showDeviceResult("IL Wi-Fi Gość na Kindle", result));
    }

    private void showRadioMenu() {
        if (sending) return;
        String[] options = {
                "Zainstaluj lub zaktualizuj IL Radio",
                "Sprawdź odtwarzacz Kindle’a",
                "Zainstaluj MPlayer dla Kindle K3",
                "Przywróć kopię IL Radio",
                "Usuń IL Radio z KOReadera",
                "Jakość i zużycie transferu",
                "Katalog stacji i własne adresy",
                "Gdzie znajdę radio?"
        };
        new DialogUi.Builder(this).setTitle("Radio")
                .setItems(options, (dialog, selected) -> {
                    switch (selected) {
                        case 0: withKindle(this::installRadio); break;
                        case 1: withKindle(this::diagnoseRadio); break;
                        case 2: withKindle(this::installMplayer); break;
                        case 3: confirmRadioChange(true); break;
                        case 4: confirmRadioChange(false); break;
                        case 5: withKindle(() -> loadRadioSettings(this::editRadioSettings)); break;
                        case 6: showRadioCatalog(); break;
                        default: showDeviceResult("Słuchanie radia",
                                "Po instalacji uruchom ponownie KOReadera z KUAL. "
                                + "W menu Narzędzia → Więcej narzędzi wybierz „Radio i audiobooki”. "
                                + "Do słuchania radia czytnik potrzebuje dostępu do internetu.");
                    }
                }).setNegativeButton("Zamknij", null).show();
    }

    private interface RadioSettingsAction { void show(RadioSettings.Config config); }

    private void loadRadioSettings(RadioSettingsAction action) {
        final RadioSettings.Config[] config = new RadioSettings.Config[1];
        deviceAction("Pobieram ustawienia radia z Kindle’a…", () -> {
            Session session = openPairedDevice();
            try { config[0] = RadioSettings.read(session); return "Gotowe"; }
            finally { session.disconnect(); }
        }, result -> action.show(config[0]));
    }

    private Spinner radioChoice(LinearLayout form, String label, String[] names, int selected) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(INK);
        form.addView(title);
        Spinner choices = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        choices.setAdapter(adapter);
        choices.setSelection(selected);
        choices.setMinimumHeight(dp(45));
        form.addView(choices);
        return choices;
    }

    private void editRadioSettings(RadioSettings.Config config) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(10));
        Spinner mode = radioChoice(form, "Tryb jakości", new String[]{"Ultraoszczędny", "Oszczędny", "Automatyczny", "Wysoka jakość"},
                RadioSettings.index(RadioSettings.MODES, config.mode, 1));
        Spinner limit = radioChoice(form, "Maksymalny bitrate", new String[]{"Według trybu", "24 kb/s", "32 kb/s",
                "48 kb/s", "64 kb/s", "96 kb/s", "128 kb/s", "Bez limitu w trybach Auto i Wysoka"},
                RadioSettings.index(RadioSettings.LIMITS, config.limit, 0));
        Spinner codec = radioChoice(form, "Preferowany kodek", new String[]{"Według trybu", "AAC jako pierwszy", "MP3 jako pierwszy"},
                RadioSettings.index(RadioSettings.CODECS, config.codec, 0));
        Spinner buffer = radioChoice(form, "Bufor silnika (jeśli obsługiwany)", new String[]{"64 KiB", "128 KiB", "256 KiB"},
                RadioSettings.index(RadioSettings.BUFFERS, config.buffer, 1));
        CheckBox minimum = new CheckBox(this);
        minimum.setText("Najmniejszy transfer");
        minimum.setChecked(config.minimum);
        form.addView(minimum);
        CheckBox screen = new CheckBox(this);
        screen.setText("Ekranowe przyciski głośności +/−");
        screen.setChecked(config.showVolume);
        form.addView(screen);
        TextView hint = new TextView(this);
        hint.setText("Oszczędny: AAC do 48 kb/s, zapasowe MP3 do 64 kb/s. Limit ręczny obowiązuje dla obu formatów. "
                + "Na Kindle ustawienia zaczną działać przy kolejnym wyborze stacji.");
        form.addView(hint);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new DialogUi.Builder(this).setTitle("Jakość radia na Kindle’u").setView(scroll)
                .setNegativeButton("Anuluj", null).setPositiveButton("Wyślij przez Wi-Fi", (dialog, which) -> {
                    config.mode = RadioSettings.MODES[mode.getSelectedItemPosition()];
                    config.limit = RadioSettings.LIMITS[limit.getSelectedItemPosition()];
                    config.codec = RadioSettings.CODECS[codec.getSelectedItemPosition()];
                    config.buffer = RadioSettings.BUFFERS[buffer.getSelectedItemPosition()];
                    config.minimum = minimum.isChecked();
                    config.showVolume = screen.isChecked();
                    sendRadioSettings(config, null, null);
                }).show();
    }

    private void showRadioCatalog() {
        try {
            Map<String, List<String[]>> catalog = RadioSettings.catalog(this);
            String[] names = catalog.keySet().toArray(new String[0]);
            new DialogUi.Builder(this).setTitle("Stacje i warianty")
                    .setItems(names, (d, which) -> showRadioStation(names[which], catalog.get(names[which])))
                    .setNeutralButton("Wyślij katalog", (d, which) -> withKindle(this::installRadio))
                    .setNegativeButton("Zamknij", null).show();
        } catch (IOException error) {
            showDeviceResult("Katalog stacji", "Katalog jest niedostępny. Sprawdź aktualizację aplikacji.");
        }
    }

    private void showRadioStation(String name, List<String[]> rows) {
        StringBuilder details = new StringBuilder();
        for (String[] row : rows) {
            if (details.length() > 0) details.append("\n\n");
            details.append(row[2].isEmpty() ? "Parametry nieustalone" : row[2] + " · " + row[3] + " kb/s")
                    .append("\n").append(row[4]).append("\n").append(row[1]);
            if (!row[5].isEmpty() && !row[5].equals("-")) details.append("\nWeryfikacja próbki: ").append(row[5]);
            else details.append("\nDostępność do sprawdzenia na Kindle’u");
            if (row.length >= 9 && !row[8].isEmpty()) details.append("\nPomiar próbki: ").append(row[8]).append(" kb/s");
            if (row.length >= 8 && !row[7].isEmpty()) details.append("\nFormat: ").append(row[7]);
            if (row.length >= 7 && !row[6].isEmpty()) details.append("\nAdres po przekierowaniu próby: ").append(row[6]);
        }
        new DialogUi.Builder(this).setTitle(name).setMessage(details.toString())
                .setPositiveButton("Własne adresy", (d, which) -> withKindle(() ->
                        loadRadioSettings(config -> editRadioAddresses(name, config))))
                .setNegativeButton("Zamknij", null).show();
    }

    private void editRadioAddresses(String name, RadioSettings.Config config) {
        EditText addresses = new EditText(this);
        addresses.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addresses.setHint("Jeden adres HTTP lub HTTPS w każdym wierszu");
        List<String> saved = config.custom.get(name);
        if (saved != null) addresses.setText(TextUtils.join("\n", saved));
        AlertDialog dialog = new DialogUi.Builder(this).setTitle(name + " · własne źródła")
                .setMessage("Możesz wpisać do trzech własnych wariantów. Puste pole przywraca same adresy katalogowe.")
                .setView(addresses).setPositiveButton("Wyślij", null).setNegativeButton("Anuluj", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> urls = new ArrayList<>();
            for (String line : addresses.getText().toString().split("\n")) {
                String url = line.trim();
                if (url.isEmpty() || urls.contains(url)) continue;
                if (urls.size() == 3 || !RadioSettings.validUrl(url)) {
                    addresses.setError("Podaj maksymalnie trzy poprawne adresy HTTP lub HTTPS.");
                    return;
                }
                urls.add(url);
            }
            dialog.dismiss();
            sendRadioSettings(null, name, urls);
        });
    }

    private void sendRadioSettings(RadioSettings.Config config, String name, List<String> urls) {
        deviceAction("Wysyłam ustawienia radia przez Wi-Fi…", () -> {
            Session session = openPairedDevice();
            try {
                RadioSettings.update(session, config, name, urls);
                return "Ustawienia zapisano na Kindle’u. Zostaną odczytane przy wyborze następnej stacji.";
            } finally { session.disconnect(); }
        }, result -> showDeviceResult("Ustawienia radia", result));
    }

    private void confirmRadioChange(boolean restore) {
        DialogUi.Builder prompt = new DialogUi.Builder(this)
                .setTitle(restore ? "Przywrócić kopię IL Radio?" : "Usunąć IL Radio z KOReadera?")
                .setMessage(restore
                        ? "Przywrócę ostatnią kopię. Jeżeli IL Radio jest zainstalowane, zachowam też jego obecną wersję."
                        : "Wtyczka zniknie po ponownym uruchomieniu KOReadera. W razie potrzeby przywrócisz ją z kopii.")
                .setNegativeButton("Anuluj", null);
        if (!restore) prompt.destructive();
        prompt.setPositiveButton(restore ? "Przywróć" : "Usuń", (d, which) ->
                withKindle(() -> deviceAction(restore ? "Przywracam IL Radio…" : "Usuwam IL Radio…", () -> {
                    Session session = openPairedDevice();
                    try { return restore ? RadioInstaller.restore(this, session) : RadioInstaller.remove(this, session); }
                    finally { session.disconnect(); }
                }, result -> showDeviceResult("Radio", result)))).show();
    }

    private void installRadio() {
        deviceAction("Wysyłam IL Radio na Kindle’a przez Wi-Fi…", () -> {
            Session session = openPairedDevice();
            try { return RadioInstaller.install(this, session); }
            finally { session.disconnect(); }
        }, result -> {
            ResultPanel panel = new ResultPanel("", "Radio jest na Kindle’u",
                    result + "\n\nZamknij KOReadera i uruchom ponownie z KUAL. "
                    + "Otwórz Narzędzia → Więcej narzędzi → Radio i audiobooki. "
                    + "Jeśli radio nie odtwarza dźwięku, sprawdź silnik audio.", ResultTone.INFO);
            panel.show("Sprawdź silnik audio", () -> withKindle(this::diagnoseRadio), "Zamknij", null);
        });
    }

    private void installMplayer() {
        deviceAction("Pobieram i sprawdzam MPlayer dla Kindle K3…", () -> {
            Session session = openPairedDevice();
            try {
                MplayerInstaller.install(session);
                RadioInstaller.install(this, session);
                return "Kindle uruchomił MPlayer z obsługą ALSA. Uruchom ponownie KOReadera z KUAL, "
                        + "wybierz Radio i audiobooki → Silnik audio → Automatycznie, "
                        + "a potem sprawdź lokalny plik MP3 lub stację z adresem HTTP.";
            } finally {
                session.disconnect();
            }
        }, result -> showDeviceResult("MPlayer gotowy do próby", result));
    }

    private void diagnoseRadio() {
        deviceAction("Sprawdzam odtwarzacz dźwięku na Kindle’u…", () -> {
            Session session = openPairedDevice();
            try { return RadioInstaller.diagnostics(this, session); }
            finally { session.disconnect(); }
        }, result -> {
            String[] parts = result.split("\n", 2);
            ResultPanel panel = new ResultPanel("", "Sprawdzenie odtwarzacza", parts[0], ResultTone.INFO);
            if (parts.length == 2) panel.details(parts[1]);
            panel.show("Zamknij", null, null, null);
        });
    }

    private void confirmRecentPlugin() {
        if (sending) return;
        new DialogUi.Builder(this).setTitle("Ostatnio przesłane w KOReaderze")
                .setMessage("Zainstaluj wtyczkę, żeby otwierać niedawno przesłane książki bez szukania ich w folderze. "
                        + "Lista obejmuje przesyłki z wersji 2.16 lub nowszej.")
                .setNegativeButton("Zamknij", null)
                .setPositiveButton("Zainstaluj wtyczkę", (d, which) -> withKindle(this::installRecentPlugin)).show();
    }

    private void installRecentPlugin() {
        deviceAction("Instaluję listę przesyłek…", () -> {
            Session session = openPairedDevice();
            try {
                CompanionFiles.install(this, session, RecentUploads.PLUGIN);
                return "Uruchom ponownie KOReadera z KUAL. W przeglądarce plików wybierz "
                        + "Menu → Narzędzia → Ostatnio przesłane.";
            } finally { session.disconnect(); }
        }, message -> showDeviceResult("Wtyczka zainstalowana", message));
    }

    private void showGazette() {
        if (sending) return;
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(8), dp(22), dp(6));
        add(fields, label("Wpisz do czterech adresów RSS lub Atom, po jednym w wierszu. "
                + "Gazetka zawiera do pięciu najnowszych wpisów z każdego kanału.", 15, INK), 0);
        EditText addresses = new EditText(this);
        addresses.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        addresses.setMinLines(3);
        addresses.setMaxLines(5);
        addresses.setHint("https://…/feed/");
        addresses.setText(settings.getString("gazette_feeds", Gazette.DEFAULT_FEED));
        add(fields, addresses, 8);
        AlertDialog dialog = new DialogUi.Builder(this).setTitle("Gazetka na Kindle").setView(fields)
                .setNegativeButton("Zamknij", null).setPositiveButton("Utwórz i wyślij", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String urls = addresses.getText().toString().trim();
            final java.util.List<java.net.URI> feeds;
            try { feeds = Gazette.feeds(urls); }
            catch (Exception invalid) {
                addresses.setError(invalid.getMessage());
                return;
            }
            settings.edit().putString("gazette_feeds", urls).apply();
            dialog.dismiss();
            withKindle(() -> sendGazette(feeds));
        }));
        dialog.show();
    }

    private void sendGazette(java.util.List<java.net.URI> feeds) {
        deviceAction("Pobieram kanały RSS…", () -> {
            Gazette.Edition edition = Gazette.fetch(feeds);
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Warsaw"));
            String name = "Gazetka-" + now.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd-HHmm")) + ".epub";
            File epub = File.createTempFile("gazetka-", ".epub", getCacheDir());
            Session session = null;
            ChannelSftp channel = null;
            try {
                Gazette.write(epub, edition, now.toLocalDate());
                session = openPairedDevice();
                channel = CompanionFiles.channel(session);
                String path = VerifiedTransfer.upload(channel, epub, name, DESTINATION, message -> { });
                boolean recent = recordRecent(channel, path);
                record(java.util.UUID.randomUUID().toString(), name, savedAddress(), "Zapisano i sprawdzono", path);
                String message = "Przesłano " + edition.articles.size() + " wpisów w jednym pliku EPUB."
                        + "\n\n" + new File(path).getName();
                if (!edition.unavailable.isEmpty()) message += "\n\nPominięte kanały: "
                        + String.join(", ", edition.unavailable);
                if (!recent) message += "\n\nGazetka jest w folderze documents. Nie zapisano jej na liście ostatnich przesyłek.";
                return message;
            } finally {
                if (channel != null) channel.disconnect();
                if (session != null) session.disconnect();
                epub.delete();
            }
        }, result -> showDeviceResult("Gazetka jest na Kindle’u", result));
    }

    private boolean recordRecent(ChannelSftp channel, String path) {
        try {
            RecentUploads.record(channel, path);
            return true;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private interface PanelOperation {
        String run(ChannelSftp files) throws Exception;
    }

    private void showReaderPanel() {
        if (sending) return;
        ReaderPanelEditor.showMenu(this, selected -> {
            switch (selected) {
                case 0: withKindle(() -> editReaderPanel(true)); break;
                case 1: withKindle(() -> editReaderPanel(false)); break;
                case 2: withKindle(this::showReaderBackups); break;
                case 3: withKindle(this::showReaderPanelStatus); break;
                default: withKindle(this::installReaderPanel); break;
            }
        });
    }

    private void installReaderPanel() {
        deviceAction("Instaluję panel ustawień…", () -> {
            Session session = openPairedDevice();
            try {
                CompanionFiles.install(this, session, ReaderPanel.PLUGIN,
                        ReaderPanel.LEGACY_PLUGIN_HASH, ReaderPanel.PREVIOUS_PLUGIN_HASH);
                return "Uruchom ponownie KOReadera z KUAL i przejdź do przeglądarki plików. "
                        + "Po chwili wróć do panelu na telefonie.";
            } finally { session.disconnect(); }
        }, result -> showDeviceResult("Panel zainstalowany", result));
    }

    private String panelOperation(String pin, PanelOperation operation) throws Exception {
        if (pin.isEmpty() || !pin.equals(settings.getString("kindle_key", "")))
            throw new IOException("Zmieniono sparowany czytnik. Odczytaj ustawienia ponownie.");
        Session session = openPairedDevice();
        ChannelSftp files = null;
        try {
            files = CompanionFiles.channel(session);
            return operation.run(files);
        } finally {
            if (files != null) files.disconnect();
            session.disconnect();
        }
    }

    private void loadReaderPanel(String pin, java.util.function.Consumer<ReaderPanel.Snapshot> display) {
        deviceAction("Odczytuję ustawienia KOReadera…", () -> panelOperation(pin, ReaderPanel::readForEditor), text -> {
            try { display.accept(ReaderPanel.editorSnapshot(text)); }
            catch (IOException error) { showDeviceResult("Odczyt ustawień", error.getMessage()); }
        });
    }

    private void editReaderPanel(boolean reading) {
        String pin = settings.getString("kindle_key", "");
        loadReaderPanel(pin, snapshot -> new ReaderPanelEditor(this, snapshot, done -> new Thread(() -> {
            java.util.Map<String, Typeface> faces = new java.util.LinkedHashMap<>();
            try {
                final java.util.Map<String, Typeface> loaded = faces;
                panelOperation(pin, files -> {
                    loaded.putAll(FontPreviewStore.load(files, getCacheDir(), pin, snapshot.fonts));
                    return "";
                });
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            final java.util.Map<String, Typeface> result = faces;
            runOnUiThread(() -> done.accept(result));
        }, "reader-font-previews").start()).show(reading,
                changes -> sendReaderPanel(pin, snapshot, changes)));
    }

    private android.content.SharedPreferences panelBackups() {
        return getSharedPreferences("koreader_panel_backups", MODE_PRIVATE);
    }

    private void sendReaderPanel(String pin, ReaderPanel.Snapshot snapshot, java.util.Map<String, String> changes) {
        deviceAction("Przesyłam ustawienia do KOReadera…", () -> panelOperation(pin, files -> {
            android.content.SharedPreferences copies = panelBackups();
            String previousId = copies.getString(pin + "/pending", "");
            String id = ReaderPanel.queue(files, snapshot, changes, requestId -> {
                String slot = pin + "/" + requestId;
                if (!copies.edit().putString(slot + "/backup", snapshot.backup())
                        .putString(slot + "/keys", String.join(",", changes.keySet()))
                        .putLong(slot + "/time", System.currentTimeMillis()).commit())
                    throw new IOException("Nie udało się zapisać kopii na telefonie.");
            });
            String previousSlot = pin + "/" + previousId;
            copies.edit().putString(pin + "/pending", id).remove(previousSlot + "/backup")
                    .remove(previousSlot + "/keys").remove(previousSlot + "/time").apply();
            return ReaderPanel.status(files, id);
        }), status -> {
            if (!status.startsWith("Zmiany czekają.")) {
                showDeviceResult("Ustawienia KOReadera", status);
                return;
            }
            ResultPanel panel = new ResultPanel("Ustawienia KOReadera", "Zmiany wysłane",
                    "KOReader czeka na powrót do listy plików.", ResultTone.INFO);
            panel.section("Co dalej?", "Na Kindle’u wyjdź z książki. Potem sprawdź stan zmian w aplikacji.", Glyph.HELP);
            panel.show("Sprawdź stan zmian", this::showReaderPanelStatus, "Zamknij", null);
        });
    }

    private void showReaderPanelStatus() {
        String pin = settings.getString("kindle_key", "");
        deviceAction("Sprawdzam stan zmian…", () -> panelOperation(pin,
                files -> ReaderPanel.status(files, panelBackups().getString(pin + "/pending", ""))),
                status -> {
                    if (!status.startsWith("Zmiany czekają.")) {
                        showDeviceResult("Stan zmian", status);
                        return;
                    }
                    ResultPanel panel = new ResultPanel("Ustawienia KOReadera", "Zmiany czekają na Kindle’a",
                            "Ustawienia są już na czytniku. KOReader zastosuje je po powrocie do listy plików.",
                            ResultTone.ATTENTION);
                    panel.show("Zamknij", null, "Anuluj oczekującą zmianę",
                            () -> deviceAction("Zlecam anulowanie…", () -> panelOperation(pin,
                                    files -> ReaderPanel.cancel(files, panelBackups().getString(pin + "/pending", ""))),
                                    result -> showDeviceResult("Stan zmian", result)));
                });
    }

    private void showReaderBackups() {
        String pin = settings.getString("kindle_key", "");
        android.content.SharedPreferences copies = panelBackups();
        String manual = backupDate(copies.getLong(pin + "/manual-time", 0));
        String pending = copies.getString(pin + "/pending", "");
        String slot = pin + "/" + pending;
        String automatic = backupDate(copies.getLong(slot + "/time", 0));
        new DialogUi.Builder(this).setTitle("Kopia ustawień na telefonie")
                .setItems(new String[] { "Zapisz aktualne ustawienia", "Przywróć zapisaną kopię" + manual,
                        "Cofnij ostatnią zmianę z telefonu" + automatic }, (d, which) -> {
                    if (which == 0) {
                        loadReaderPanel(pin, snapshot -> {
                            boolean saved = copies.edit().putString(pin + "/manual", snapshot.backup())
                                    .putLong(pin + "/manual-time", System.currentTimeMillis()).commit();
                            showDeviceResult("Kopia ustawień", saved ? "Zapisano ustawienia obsługiwane przez panel."
                                    : "Nie udało się zapisać kopii.");
                        });
                    } else if (which == 1) {
                        restoreReaderPanel(pin, copies.getString(pin + "/manual", ""), ReaderPanel.KEYS);
                    } else {
                        String previous = copies.getString(slot + "/backup", "");
                        if (previous.isEmpty()) {
                            showDeviceResult("Kopia ustawień", "Brak zmiany do cofnięcia.");
                            return;
                        }
                        deviceAction("Sprawdzam, czy KOReader zastosował zmianę…", () -> panelOperation(pin,
                                files -> ReaderPanel.resultCode(files, pending)), code -> {
                            if ("applied".equals(code)) restoreReaderPanel(pin, previous,
                                    java.util.Arrays.asList(copies.getString(slot + "/keys", "").split(",")));
                            else showDeviceResult("Kopia ustawień", "KOReader jeszcze nie potwierdził ostatniej zmiany.");
                        });
                    }
                }).setNegativeButton("Zamknij", null).show();
    }

    private String backupDate(long millis) {
        return millis == 0 ? " · brak kopii" : "\n" + warsawTime(millis / 1000);
    }

    private void restoreReaderPanel(String pin, String saved, java.util.List<String> keysToRestore) {
        if (saved.isEmpty()) {
            showDeviceResult("Kopia ustawień", "Brak kopii dla tego czytnika.");
            return;
        }
        loadReaderPanel(pin, live -> {
            final ReaderPanel.Snapshot backup;
            try { backup = ReaderPanel.Snapshot.parse(saved); }
            catch (IOException error) {
                showDeviceResult("Kopia ustawień", "Nie można odczytać kopii.");
                return;
            }
            java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();
            for (String key : ReaderPanel.KEYS) {
                if (!keysToRestore.contains(key)) continue;
                String previous = backup.values.get(key);
                String now = live.values.get(key);
                if (!previous.equals(now) && !"!".equals(previous) && !"!".equals(now)) changes.put(key, previous);
            }
            if (changes.isEmpty()) {
                showDeviceResult("Kopia ustawień", "Ustawienia zgodne z kopią.");
                return;
            }
            new DialogUi.Builder(this).setTitle("Przywrócić ustawienia?")
                    .setMessage("Do KOReadera trafią zapisane wartości. Aktualne ustawienia zostaną zachowane w kopii.")
                    .setNegativeButton("Anuluj", null)
                    .setPositiveButton("Przywróć", (d, which) -> sendReaderPanel(pin, live, changes)).show();
        });
    }

    private void showLibrary() {
        if (sending) return;
        String host = savedAddress();
        busy(true);
        fileManager = new FileManager(this, () -> connect(host), () -> {
            fileManager = null;
            busy(false);
        });
        fileManager.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private Button action(String text, Glyph icon, int foreground, int fill, int stroke) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] { (foreground & 0x00ffffff) | 0x88000000, foreground }));
        button.setLetterSpacing(0);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ActionIcon.attach(button, icon);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(0));
        button.setMinimumWidth(dp(0));
        button.setPadding(dp(13), dp(9), dp(13), dp(9));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x220c3328),
                rounded(fill, 15, stroke), null));
        return button;
    }

    private Button secondaryButton(String text, Glyph icon) {
        return action(text, icon, GREEN, SURFACE, BORDER);
    }

    private enum ResultTone {
        SUCCESS(0xffe2f0e7, 0xff176440, Glyph.CHECK),
        ATTENTION(0xffffefcf, 0xff704c1b, Glyph.INFO),
        INFO(0xffedf3ed, GREEN, Glyph.INFO);

        final int fill;
        final int ink;
        final Glyph icon;

        ResultTone(int fill, int ink, Glyph icon) {
            this.fill = fill;
            this.ink = ink;
            this.icon = icon;
        }
    }

    private final class ResultPanel {
        private final LinearLayout frame = column();
        private final LinearLayout content = column();
        private String technicalDetails;

        ResultPanel(String context, String title, String message, ResultTone tone) {
            frame.setBackground(rounded(SURFACE, 24, 0));
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            frame.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));
            scroll.addView(content);
            content.setPadding(dp(20), dp(18), dp(20), dp(14));
            if (!context.isEmpty()) {
                TextView caption = heading(context, 14, MUTED);
                markHeading(caption);
                add(content, caption, 0);
            }

            LinearLayout card = column();
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackground(rounded(tone.fill, 16, 0));
            TextView headline = heading(title, 20, tone.ink);
            markHeading(headline);
            ActionIcon.attach(headline, tone.icon, 21, false);
            add(card, headline, 0);
            if (!message.isEmpty()) add(card, label(message, 15, INK), 7);
            add(content, card, context.isEmpty() ? 0 : 9);
        }

        void section(String title, String message, Glyph icon) {
            if (message.isEmpty()) return;
            LinearLayout section = column();
            section.setPadding(dp(13), dp(11), dp(13), dp(11));
            section.setBackground(rounded(SURFACE, 14, BORDER));
            TextView caption = heading(title, 14, GREEN);
            markHeading(caption);
            ActionIcon.attach(caption, icon);
            add(section, caption, 0);
            add(section, label(message, 15, INK), 4);
            add(content, section, 9);
        }

        void details(String message) {
            technicalDetails = message;
        }

        void show(String primary, Runnable primaryAction, String secondary, Runnable secondaryAction) {
            DialogUi.addDetails(MainActivity.this, content, technicalDetails);
            View divider = new View(MainActivity.this);
            divider.setBackgroundColor(BORDER);
            frame.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            LinearLayout buttons = column();
            buttons.setPadding(dp(20), dp(11), dp(20), dp(15));
            frame.addView(buttons);
            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this).setView(frame).create();
            addDialogButton(buttons, dialog, primary, primaryAction, true);
            if (secondary != null) addDialogButton(buttons, dialog, secondary, secondaryAction, false);
            dialog.show();
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                int available = getResources().getDisplayMetrics().widthPixels - dp(36);
                window.setLayout(Math.max(dp(160), Math.min(dp(456), available)), -2);
            }
        }

        private void addDialogButton(LinearLayout parent, AlertDialog dialog, String title,
                                     Runnable onClick, boolean primary) {
            Button button = action(title, onClick == null ? Glyph.CLOSE : Glyph.LEVEL_UP,
                    primary ? Color.WHITE : GREEN, primary ? GREEN : SURFACE, primary ? 0 : BORDER);
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(false);
            button.setOnClickListener(v -> {
                dialog.dismiss();
                if (onClick != null) onClick.run();
            });
            add(parent, button, primary ? 0 : 6);
        }
    }

    private void markHeading(TextView view) {
        if (android.os.Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
    }

    static String technicalMessage(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) return error.getClass().getSimpleName();
        if (error instanceof SftpException) return "SFTP " + ((SftpException) error).id + " · " + detail;
        return detail;
    }

    private void helpConnection() {
        new DialogUi.Builder(this)
                .setTitle("Jak połączyć Kindle?")
                .setMessage("1. Połącz telefon i Kindle z tą samą siecią Wi‑Fi albo podłącz Kindle do hotspotu tego telefonu.\n\n"
                        + "2. Na Kindle otwórz KOReader i włącz serwer SSH.\n\n"
                        + "3. W aplikacji stuknij „Znajdź Kindle”.\n\n"
                        + "4. Przy pierwszym połączeniu potwierdź czytnik. Przy kolejnych aplikacja spróbuje go znaleźć automatycznie.\n\n"
                        + "Dla bezpieczeństwa wyłącz serwer na Kindle po przesłaniu książek.")
                .setPositiveButton("Rozumiem", null).show();
    }

    private void renderConfirmed(java.util.ArrayList<String> names) {
        confirmedRows.removeAllViews();
        confirmedRows.setVisibility(names.isEmpty() ? View.GONE : View.VISIBLE);
        int limit = Math.min(names.size(), 3);
        for (int i = 0; i < limit; i++) {
            TextView item = label("✓  " + names.get(i), 14, SUCCESS);
            item.setMaxLines(2);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(dp(4), dp(4), dp(4), dp(4));
            confirmedRows.addView(item);
        }
        if (names.size() > limit) {
            add(confirmedRows, label("Pozostałe pliki znajdziesz w szczegółach.", 13, GREEN), 4);
        }
    }

    private void showDetails() {
        TextView content = label(lastDetails.isEmpty() ? "Brak dodatkowych informacji." : lastDetails, 15, INK);
        content.setTextIsSelectable(true);
        content.setPadding(dp(18), dp(15), dp(18), dp(15));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new DialogUi.Builder(this).setTitle("Szczegóły przesyłki")
                .setView(scroll).setPositiveButton("Zamknij", null).show();
    }

    private void showWorking() {
        stateCard.setBackground(rounded(SURFACE, 20, BORDER));
        stateCard.setVisibility(View.VISIBLE);
        stateTitle.setText("Wysyłanie książek");
        stateIcon.animate().cancel();
        stateIcon.setAlpha(1f);
        stateIcon.setScaleX(1f);
        stateIcon.setScaleY(1f);
        stateIcon.setText("↑");
        stateIcon.setTextColor(GREEN);
        stateIcon.setBackground(rounded(MINT, 99, 0));
        progressLabel.setText("Przygotowanie");
        status.setText("Przygotowuję przesyłkę…");
        detailsButton.setVisibility(View.GONE);
        retryQueue.setVisibility(View.GONE);
        confirmedRows.setVisibility(View.GONE);
        screen.post(() -> {
            if (stateCard.getVisibility() == View.VISIBLE) cancelButton.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, cancelButton.getWidth(), cancelButton.getHeight()),
                    isReducedMotion());
        });
    }

    private void showSuccess(String heading, String detail) {
        stateTitle.setText(heading);
        status.setText(detail);
        stateIcon.setText("✓");
        stateIcon.setTextColor(SUCCESS);
        stateIcon.setBackground(rounded(0xffdcefe1, 99, 0));
        stateCard.setBackground(rounded(0xfff0f8f0, 20, 0xffcee5d4));
        if (!isReducedMotion()) {
            stateIcon.setAlpha(0.45f);
            stateIcon.setScaleX(0.82f);
            stateIcon.setScaleY(0.82f);
            stateIcon.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new DecelerateInterpolator()).setDuration(190).start();
        }
    }

    private void showNotComplete(boolean stopped, int success, int partial, int all) {
        retryQueue.setVisibility(sharedFiles.isEmpty() ? View.GONE : View.VISIBLE);
        stateTitle.setText(stopped ? "Przesyłanie przerwane"
                : success == 0 && partial == 0 ? "Wysyłanie się nie udało" : "Przesyłka niekompletna");
        stateIcon.setText("!");
        stateIcon.setTextColor(0xff744915);
        stateIcon.setBackground(rounded(0xfffaecd2, 99, 0));
        stateCard.setBackground(rounded(0xfffffaf1, 20, 0xffefe0c5));
        status.setText("W pełni wysłane: " + success + " z " + all + "."
                + (partial > 0 ? " Część plików trafiła już na Kindle." : "")
                + " Zajrzyj do szczegółów przed ponowną próbą.");
    }

    private void add(LinearLayout layout, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        layout.addView(child, params);
    }

    private void pickFile() {
        if (sending) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Nie można otworzyć wyboru plików.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AudioLibrary.PICK_AUDIO) {
            if (result == RESULT_OK && audioLibrary != null) audioLibrary.receive(data);
            return;
        }
        if (request == FileManager.PICK_BACKUP_FOLDER) {
            if (fileManager != null) fileManager.backupTo(result == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (request != PICK_FILE || result != RESULT_OK || data == null || sending) return;
        java.util.ArrayList<Uri> picked = collectFiles(data, false);
        if (picked.isEmpty()) return;
        stageFiles(picked);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveShare(intent);
    }

    private java.util.ArrayList<Uri> collectFiles(Intent intent, boolean share) {
        java.util.LinkedHashSet<Uri> files = new java.util.LinkedHashSet<>();
        try {
            if (share && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                java.util.ArrayList<?> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null) for (Object stream : streams) {
                    if (stream instanceof Uri) files.add((Uri) stream);
                }
            } else if (share) {
                Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream instanceof Uri) files.add((Uri) stream);
            }
        } catch (RuntimeException ignored) {
            // Malformed share extras do not prevent reading valid ClipData.
        }
        if (intent.getData() != null) files.add(intent.getData());
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) files.add(uri);
            }
        }
        java.util.ArrayList<Uri> safe = new java.util.ArrayList<>();
        for (Uri uri : files) {
            if (uri != null && "content".equals(uri.getScheme())) safe.add(uri);
        }
        return safe;
    }

    private void receiveShare(Intent intent) {
        if (intent == null || !(Intent.ACTION_SEND.equals(intent.getAction())
                || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) return;
        java.util.ArrayList<Uri> incoming = collectFiles(intent, true);
        if (incoming.isEmpty()) {
            android.widget.Toast.makeText(this, "Udostępnij plik książki, np. EPUB lub PDF.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        stageFiles(incoming);
    }

    private void stageFiles(java.util.List<Uri> incoming) {
        ImportBatch batch = new ImportBatch();
        pendingImports.add(batch);
        updateChoose();
        imports.execute(() -> {
            java.util.ArrayList<Uri> added = new java.util.ArrayList<>();
            String reason = "";
            for (Uri uri : incoming) {
                if (batch.stopped) break;
                try {
                    String name = displayName(uri, batch.signal);
                    if (!RemoteFiles.book(name)) { reason = "Wybierz plik książki w obsługiwanym formacie."; continue; }
                    android.content.res.AssetFileDescriptor descriptor =
                            getContentResolver().openAssetFileDescriptor(uri, "r", batch.signal);
                    if (descriptor == null) throw new IOException("Brak dostępu do pliku.");
                    try (android.content.res.AssetFileDescriptor opened = descriptor;
                            InputStream source = opened.createInputStream()) {
                        batch.source = source;
                        Uri item = Uri.fromFile(queue.add(name, source, () -> batch.stopped));
                        added.add(item);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (!sharedFiles.contains(item)) sharedFiles.add(item);
                            updateChoose();
                        });
                    } finally { batch.source = null; }
                } catch (Exception error) {
                    if (!batch.stopped)
                        reason = error instanceof IOException ? error.getMessage() : "Nie można otworzyć pliku.";
                }
            }
            final String importError = reason;
            runOnUiThread(() -> {
                pendingImports.remove(batch);
                if (isFinishing() || isDestroyed()) return;
                updateChoose();
                int missed = incoming.size() - added.size();
                String result = batch.stopped ? "Przerwano dodawanie. Zapisane pliki: " + added.size()
                        : "Dodano do kolejki: " + added.size()
                        + (missed == 0 ? "" : ". Pominięto: " + missed + ". " + importError);
                android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showQueue() {
        java.util.ArrayList<Uri> items = new java.util.ArrayList<>(sharedFiles);
        queueActions.execute(() -> {
            java.util.ArrayList<String> descriptions = new java.util.ArrayList<>();
            java.util.ArrayList<Uri> visible = new java.util.ArrayList<>();
            for (Uri item : items) {
                try {
                    File book = queuedFile(item);
                    java.util.Properties info = queue.info(book);
                    String state = info.getProperty("state", "Czeka na wysłanie");
                    descriptions.add(info.getProperty("name", "Książka") + "\n"
                            + android.text.format.Formatter.formatFileSize(this, book.length()) + " · " + state);
                    visible.add(item);
                } catch (IOException ignored) {}
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || sending) return;
                new DialogUi.Builder(this).setTitle("Kolejka książek")
                        .setItems(descriptions.toArray(new String[0]), (d, index) -> {
                            Uri item = visible.get(index);
                            new DialogUi.Builder(this).setTitle("Usunąć tę pozycję z kolejki?")
                                    .setMessage(descriptions.get(index))
                                    .setNegativeButton("Zostaw", null)
                                    .destructive().setPositiveButton("Usuń z kolejki", (dialog, choice) ->
                                            removeQueuedItems(java.util.Collections.singletonList(item))).show();
                        })
                        .setNeutralButton("Wyczyść kolejkę", (d, w) -> clearSelection.performClick())
                        .setPositiveButton("Zamknij", null).show();
            });
        });
    }

    private void clearQueue() {
        removeQueuedItems(new java.util.ArrayList<>(sharedFiles));
    }

    private void removeQueuedItems(java.util.List<Uri> items) {
        busy(true);
        queueActions.execute(() -> {
            java.util.ArrayList<Uri> removed = new java.util.ArrayList<>();
            for (Uri item : items) {
                try {
                    if (queue.remove(queuedFile(item))) removed.add(item);
                } catch (IOException ignored) {}
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                sharedFiles.removeAll(removed);
                busy(false);
                updateChoose();
                if (removed.size() != items.size()) android.widget.Toast.makeText(this,
                        "Nie wszystkie pliki udało się usunąć z kolejki.", android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }

    private File queuedFile(Uri uri) { return new File(uri.getPath()); }

    private InputStream openBook(Uri uri) throws IOException {
        return "file".equals(uri.getScheme()) ? new java.io.FileInputStream(queuedFile(uri))
                : getContentResolver().openInputStream(uri);
    }

    @Override protected void onDestroy() {
        guestPortalServer.stop();
        for (ImportBatch batch : pendingImports) batch.stop();
        Thread worker = transferThread;
        if (worker != null) { cancellationRequested = true; worker.interrupt(); }
        super.onDestroy();
    }

    private void updateChoose() {
        int count = sharedFiles.size();
        sendQueue.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        sendQueue.setEnabled(!sending);
        sendQueue.setText(count <= 1 ? "Wyślij na Kindle" : "Wyślij na Kindle (" + count + ")");
        retryQueue.setEnabled(!sending);
        if (count == 0) retryQueue.setVisibility(View.GONE);
        boolean importing = !pendingImports.isEmpty();
        boolean stopping = importing && pendingImports.stream().allMatch(batch -> batch.stopped);
        stopImport.setVisibility(importing ? View.VISIBLE : View.GONE);
        stopImport.setEnabled(!stopping);
        selectionNote.setText(stopping ? "Przerywam dodawanie książek…"
                : importing ? "Zapisuję pliki w kolejce…"
                : count == 0 ? "Dodaj EPUB, MOBI lub PDF z telefonu."
                : count == 1 ? "W kolejce czeka 1 plik." : "Pliki w kolejce: " + count);
        importProgress.setVisibility(importing && !isReducedMotion() ? View.VISIBLE : View.GONE);
        choose.setEnabled(!sending && !importing);
        clearSelection.setEnabled(!sending);
        skipIdentical.setEnabled(!sending);
        clearSelection.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        viewQueue.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        viewQueue.setEnabled(!sending);
    }

    private void busy(boolean value) {
        sending = value;
        connectionButton.setEnabled(!value);
        connectionSettings.setEnabled(!value);
        deviceTools.setEnabled(!value);
        choose.setEnabled(!value && pendingImports.isEmpty());
        sendQueue.setEnabled(!value);
        retryQueue.setEnabled(!value);
        clearSelection.setEnabled(!value);
        viewQueue.setEnabled(!value);
        skipIdentical.setEnabled(!value);
        list.setEnabled(!value);
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void prepareBatch(java.util.ArrayList<Uri> files) {
        if (sending || files.isEmpty()) return;
        busy(true);
        new Thread(() -> {
            java.util.LinkedHashMap<Uri, String> names = new java.util.LinkedHashMap<>();
            int unavailable = 0;
            for (Uri uri : files) {
                try {
                    names.put(uri, queue.info(queuedFile(uri)).getProperty("name", "plik"));
                } catch (IOException | RuntimeException e) {
                    unavailable++;
                }
            }
            int missing = unavailable;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy(false);
                if (missing > 0) {
                    new DialogUi.Builder(this).setTitle("Część plików jest niedostępna")
                            .setMessage("Brak dostępu do plików: " + missing
                                    + ". Możesz ponownie wybrać te pliki z telefonu.")
                            .setNegativeButton("Zamknij", null)
                            .setPositiveButton(names.isEmpty() ? "Wybierz pliki" : "Wyślij dostępne",
                                    (dialog, which) -> {
                                        if (names.isEmpty()) pickFile();
                                        else showBatchOptions(names);
                                    }).show();
                } else showBatchOptions(names);
            });
        }, "kindle-file-details").start();
    }

    private void showBatchOptions(java.util.LinkedHashMap<Uri, String> names) {
        Uri firstPdf = null;
        int pdfCount = 0;
        for (java.util.Map.Entry<Uri, String> item : names.entrySet()) {
            if (item.getValue().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                if (firstPdf == null) firstPdf = item.getKey();
                pdfCount++;
            }
        }
        if (firstPdf == null) { pdfOptions = null; pdfMode = 0; startBatch(names); return; }
        final int numberPdf = pdfCount;
        final Uri previewUri = firstPdf;
        ScrollView scroll = new ScrollView(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(16),dp(8),dp(16),dp(8));
        scroll.addView(fields);
        add(fields,label("Wybierz format, w którym chcesz czytać na Kindle. Oryginalny PDF zostanie na telefonie.",16,INK),0);
        RadioGroup modes = new RadioGroup(this);
        String[] labels = {"Wyślij PDF", "Wyślij EPUB", "Wyślij PDF i EPUB"};
        for (int i=0;i<3;i++) {
            RadioButton radio = new RadioButton(this);
            radio.setText(labels[i]); radio.setId(4000+i);
            modes.addView(radio);
        }
        modes.check(4000 + settings.getInt("pdf_send_mode", 2));
        add(fields,modes,8);
        Button more = secondaryButton("Dodatkowe ustawienia", Glyph.SETTINGS);
        add(fields, more, 8);
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        add(fields, advanced, 0);
        more.setOnClickListener(v -> {
            boolean opening = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(opening ? View.VISIBLE : View.GONE);
            more.setText(opening ? "Ukryj ustawienia" : "Dodatkowe ustawienia");
            if (opening && !isReducedMotion()) {
                advanced.setAlpha(0.6f);
                advanced.animate().alpha(1f).setDuration(160).start();
            }
        });
        EditText title = field(advanced, "Tytuł (pusty = nazwa PDF)", numberPdf==1 ? stem(names.get(previewUri)) : "");
        EditText author = field(advanced, "Autor (opcjonalnie)", "");
        EditText language = field(advanced, "Język (np. pl, de, en)", "pl");
        EditText first = field(advanced, "Pierwsza strona", "1");
        first.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText last = field(advanced, "Ostatnia strona (puste = koniec)", "");
        last.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox hyphens = new CheckBox(this); hyphens.setText("Łącz wyrazy dzielone na końcu linii"); hyphens.setChecked(true); add(advanced,hyphens,4);
        CheckBox numbers = new CheckBox(this); numbers.setText("Usuń samodzielne numery stron"); numbers.setChecked(true); add(advanced,numbers,0);
        CheckBox images = new CheckBox(this); images.setText("Dodaj osadzone ilustracje (większy EPUB)"); images.setChecked(false); add(advanced,images,0);
        CheckBox ocr = new CheckBox(this); ocr.setText("Rozpoznawaj tekst na skanach"); ocr.setChecked(true); add(advanced,ocr,4);
        CheckBox forceOcr = new CheckBox(this); forceOcr.setText("Rozpoznawaj tekst na każdej stronie (wolniej)"); add(advanced,forceOcr,0);
        add(advanced,label("Rozpoznawanie działa bez internetu. Wynik zależy od jakości skanu i układu strony.",13,INK),8);
        Button preview = secondaryButton("Próbka tekstu EPUB", Glyph.PREVIEW);
        add(fields, preview, 8);
        ProgressBar previewProgress = processIndicator(GREEN);
        add(fields, previewProgress, 3);
        AlertDialog dialog = new DialogUi.Builder(this).setTitle("W jakim formacie wysłać PDF?")
                .setView(scroll).setNegativeButton("Anuluj", null)
                .setPositiveButton("Wyślij", null).create();
        final Thread[] previewWorker = new Thread[1];
        dialog.setOnDismissListener(d -> {
            if (previewWorker[0] != null) previewWorker[0].interrupt();
        });
        preview.setOnClickListener(v -> {
            PdfEpubConverter.Options opts;
            try {
                opts = getPdfOptions(title, author, language, first, last, hyphens, numbers, images, ocr, forceOcr);
            } catch (IOException e) {
                new DialogUi.Builder(this).setTitle("Sprawdź ustawienia PDF")
                        .setMessage(e.getMessage()).setPositiveButton("Wróć do ustawień", null).show();
                return;
            }
            preview.setEnabled(false);
            preview.setText("Przygotowuję próbkę…");
            previewProgress.setVisibility(isReducedMotion() ? View.GONE : View.VISIBLE);
            previewWorker[0] = new Thread(() -> {
                File pdfCopy = null;
                CharSequence result;
                try {
                    pdfCopy = cachePdf(previewUri);
                    result = PdfEpubConverter.previewEpub(this, pdfCopy, opts);
                } catch (Exception error) {
                    result = "Nie można przygotować podglądu. " + userMessage(error);
                } finally {
                    if (pdfCopy != null) pdfCopy.delete();
                }
                CharSequence shown = result;
                runOnUiThread(() -> {
                    previewWorker[0] = null;
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                    preview.setEnabled(true);
                    preview.setText("Próbka tekstu EPUB");
                    previewProgress.setVisibility(View.GONE);
                    TextView view = label("", 15, INK);
                    view.setText(shown);
                    view.setPadding(dp(16), dp(12), dp(16), dp(12));
                    view.setTextIsSelectable(true);
                    ScrollView box = new ScrollView(this);
                    box.addView(view);
                    new DialogUi.Builder(this).setTitle("Próbka tekstu EPUB · do 3 stron").setView(box)
                            .setPositiveButton("Zamknij", null).show();
                });
            }, "pdf-preview");
            previewWorker[0].start();
        });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int mode = modes.getCheckedRadioButtonId()-4000;
                if(mode<0 || mode>2) throw new IOException("Wybierz format przesyłki.");
                PdfEpubConverter.Options opts = mode == 0 ? null : getPdfOptions(title,author,language,first,last,hyphens,numbers,images,ocr,forceOcr);
                pdfMode = mode; pdfOptions = opts; batchPdfCount = numberPdf;
                if (!preview.isEnabled()) { android.widget.Toast.makeText(this, "Poczekaj na zakończenie podglądu.", android.widget.Toast.LENGTH_SHORT).show(); return; }
                settings.edit().putInt("pdf_send_mode", mode).apply();
                dialog.dismiss(); startBatch(names);
            } catch(Exception error) { new DialogUi.Builder(this).setTitle("Sprawdź ustawienia PDF")
                        .setMessage(userMessage(error)).setPositiveButton("Wróć do ustawień", null).show(); }
        }));
        dialog.show();
    }

    private EditText field(LinearLayout parent, String label, String defaultValue) {
        add(parent, label(label,13,INK),9);
        EditText edit = new EditText(this); edit.setSingleLine(true); edit.setText(defaultValue);
        add(parent,edit,0); return edit;
    }
    private static String stem(String name) { int dot=name.lastIndexOf('.'); return dot>0?name.substring(0,dot):name; }
    private static int numeric(EditText field, int fallback) throws IOException {
        String s=field.getText().toString().trim();
        if(s.isEmpty()) return fallback;
        try { return Integer.parseInt(s); } catch(NumberFormatException e) { throw new IOException("Nieprawidłowy zakres stron."); }
    }
    private PdfEpubConverter.Options getPdfOptions(EditText title,EditText author,EditText language,
            EditText first,EditText last,CheckBox hyphens,CheckBox numbers,CheckBox images,CheckBox ocr,CheckBox forceOcr) throws IOException {
        PdfEpubConverter.Options o=new PdfEpubConverter.Options();
        o.title=title.getText().toString().trim(); o.author=author.getText().toString().trim();
        o.language=language.getText().toString().trim();
        if(!o.language.matches("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?")) throw new IOException("Język wpisz jako pl, de lub en.");
        o.firstPage=numeric(first,1); o.lastPage=numeric(last,0);
        if(o.firstPage<1 || (o.lastPage!=0 && o.lastPage<o.firstPage)) throw new IOException("Nieprawidłowy zakres stron.");
        o.joinHyphens=hyphens.isChecked(); o.removePageNumbers=numbers.isChecked(); o.includeImages=images.isChecked();
        o.ocrScans=ocr.isChecked(); o.forceOcr=forceOcr.isChecked();
        return o;
    }
    private File cachePdf(Uri uri) throws IOException {
        File copy=File.createTempFile("pdf-source-",".pdf",getCacheDir());
        try (InputStream in=openBook(uri); java.io.OutputStream out=new java.io.FileOutputStream(copy)) {
            if (in==null) throw new IOException("Nie można odczytać PDF.");
            byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Przerwano odczyt książki.");
                out.write(buffer,0,n);
            }
        } catch(IOException | RuntimeException e) { copy.delete(); throw e; }
        if(copy.length()==0) { copy.delete(); throw new IOException("PDF jest pusty."); }
        return copy;
    }
    /** Each part of a two-format transfer is reported separately, including partial success. */
    private static final class SendOutcome {
        private final StringBuilder details = new StringBuilder();
        boolean complete = true;
        boolean anyOnKindle;
        String pdfPath, epubPath;
        String pdfHash = "", epubHash = "", localEpub = "", options = "";
        void saved(String description, String location) {
            details.append("✓ ").append(description).append(": ").append(location).append('\n');
        }
        void failed(String description, Exception problem) {
            complete = false;
            details.append("✗ ").append(description).append(": ")
                    .append(userMessage(problem)).append('\n');
        }
        @Override public String toString() { return details.toString().trim(); }
    }

    private void removeFromQueue(Uri uri) throws IOException {
        if (!queue.remove(queuedFile(uri))) throw new IOException("Książka dotarła na Kindle, ale pozostała w kolejce.");
    }

    private void saveCheckpoint(Uri uri, String reader, SendOutcome outcome) throws IOException {
        File book = queuedFile(uri);
        java.util.Properties data = queue.info(book);
        data.setProperty("reader", reader);
        data.setProperty("pdf", outcome.pdfPath == null ? "" : outcome.pdfPath);
        data.setProperty("epub", outcome.epubPath == null ? "" : outcome.epubPath);
        data.setProperty("pdfHash", outcome.pdfHash);
        data.setProperty("epubHash", outcome.epubHash);
        data.setProperty("localUri", outcome.localEpub);
        data.setProperty("options", outcome.options);
        queue.update(book, data);
    }

    private static String fileHash(File file) throws Exception {
        try (InputStream input = new java.io.FileInputStream(file)) {
            return VerifiedTransfer.sha256(input);
        }
    }

    private boolean localEpubMatches(SendOutcome outcome) throws Exception {
        if (outcome.localEpub.isEmpty() || outcome.epubHash.isEmpty()) return false;
        try (InputStream input = openBook(Uri.parse(outcome.localEpub))) {
            return input != null && outcome.epubHash.equals(VerifiedTransfer.sha256(input));
        } catch (IOException | SecurityException missing) {
            checkCancelled();
            return false;
        }
    }

    private void convertAndSend(String host, String reader, Uri uri, String name, boolean sendPdf, SendOutcome outcome) throws Exception {
        if (outcome.pdfPath != null && sendPdf) outcome.saved("PDF jest już na Kindle", outcome.pdfPath);
        if (outcome.epubPath != null) outcome.saved("EPUB jest już na Kindle", outcome.epubPath);
        outcome.anyOnKindle = outcome.epubPath != null || (sendPdf && outcome.pdfPath != null);
        if (outcome.epubPath != null && !outcome.localEpub.isEmpty() && (!sendPdf || outcome.pdfPath != null)) return;
        File cached = cachePdf(uri);
        File epub = null;
        try {
            epub = File.createTempFile("kindle-converted-", ".epub", getCacheDir());
            epub.delete();
            String base = stem(name);
            String epubName = base + ".epub";
            if (sendPdf && outcome.pdfPath == null) {
                checkCancelled();
                runOnUiThread(() -> status.setText("Wysyłam PDF…"));
                try {
                    outcome.pdfHash = fileHash(cached);
                    outcome.pdfPath = uploadFile(host, cached, name);
                    outcome.saved("PDF na Kindle", outcome.pdfPath);
                    outcome.anyOnKindle = true;
                    saveCheckpoint(uri, reader, outcome);
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || cancellationRequested) throw e;
                    outcome.failed("PDF na Kindle", e);
                }
            }
            checkCancelled();
            if (outcome.epubPath != null && !outcome.localEpub.isEmpty()) return;
            if (!outcome.localEpub.isEmpty()) {
                try (InputStream input = openBook(Uri.parse(outcome.localEpub))) {
                    if (input == null) throw new IOException("Lokalna kopia EPUB jest niedostępna.");
                    java.nio.file.Files.copy(input, epub.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (!outcome.epubHash.equals(fileHash(epub)))
                    throw new IOException("Lokalna kopia EPUB zmieniła się. Wybierz ponowienie.");
                outcome.epubPath = uploadFile(host, epub, epubName);
                outcome.anyOnKindle = true;
                outcome.saved("EPUB na Kindle", outcome.epubPath);
                saveCheckpoint(uri, reader, outcome);
                return;
            }
            showPhase("Tworzę EPUB…", "Przygotowuję tekst do czytania.", true);
            PdfEpubConverter.Options current = new PdfEpubConverter.Options();
            current.title = pdfOptions.title; current.author = pdfOptions.author; current.language = pdfOptions.language;
            current.firstPage = pdfOptions.firstPage; current.lastPage = pdfOptions.lastPage;
            current.joinHyphens = pdfOptions.joinHyphens; current.removePageNumbers = pdfOptions.removePageNumbers;
            current.includeImages = pdfOptions.includeImages; current.ocrScans = pdfOptions.ocrScans;
            current.forceOcr = pdfOptions.forceOcr;
            // A custom title applies to one PDF only. Every file keeps its own filename.
            if (batchPdfCount > 1 || current.title.isEmpty()) current.title = base;
            try {
                PdfEpubConverter.ConversionSummary summary = PdfEpubConverter.convert(this, cached, epub, current,
                        (page, total) -> runOnUiThread(() -> status.setText("Przygotowuję książkę: strona " + page + " z " + total)));
                outcome.saved("Konwersja EPUB", summary.pages + " stron");
                if (!summary.emptyPages.isEmpty())
                    outcome.saved("Uwaga: strony bez rozpoznanego tekstu", summary.emptyPages.toString());
                if (!summary.ocrErrors.isEmpty())
                    outcome.saved("OCR nie zakończył się na stronach", summary.ocrErrors.toString());
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || cancellationRequested) throw e;
                outcome.failed("Konwersja EPUB", e);
                return;
            }
            checkCancelled();
            try {
                if (outcome.localEpub.isEmpty()) {
                    String hash = fileHash(epub);
                    if (!hash.equals(outcome.epubHash)) outcome.epubPath = null;
                    outcome.epubHash = hash;
                    outcome.localEpub = saveEpub(epub, epubName).toString();
                    saveCheckpoint(uri, reader, outcome);
                    outcome.saved("EPUB zapisany w telefonie", "Pobrane / Kindle przez WiFi (Android 10+) lub pliki aplikacji");
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || cancellationRequested) throw e;
                outcome.failed("Lokalny EPUB", e);
            }
            checkCancelled();
            try {
                if (outcome.epubPath == null) {
                    outcome.epubHash = fileHash(epub);
                    outcome.epubPath = uploadFile(host, epub, epubName);
                    outcome.saved("EPUB na Kindle", outcome.epubPath);
                }
                outcome.anyOnKindle = true;
                saveCheckpoint(uri, reader, outcome);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || cancellationRequested) throw e;
                outcome.failed("EPUB na Kindle", e);
            }
        } finally {
            cached.delete();
            if (epub != null) epub.delete();
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancellationRequested || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Użytkownik przerwał operację.");
    }

    private void copyEpub(File epub, java.io.OutputStream out) throws IOException {
        if (out == null) throw new IOException("Nie można zapisać EPUB na telefonie.");
        try (InputStream in = new java.io.FileInputStream(epub)) {
            byte[] bytes = new byte[32768];
            int count;
            while ((count = in.read(bytes)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Przerwano zapis EPUB.");
                out.write(bytes, 0, count);
            }
        }
    }

    private Uri saveEpub(File epub, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip");
            values.put("relative_path", Environment.DIRECTORY_DOWNLOADS + "/Kindle przez WiFi");
            values.put("is_pending", 1);
            Uri target = getContentResolver().insert(Uri.parse("content://media/external/downloads"), values);
            if (target == null) throw new IOException("Nie udało się zapisać EPUB w Pobranych.");
            boolean published = false;
            try {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                    copyEpub(epub, out);
                }
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Przerwano zapis EPUB.");
                ContentValues ready = new ContentValues();
                ready.put("is_pending", 0);
                published = getContentResolver().update(target, ready, null, null) > 0;
                if (!published) throw new IOException("Nie udało się udostępnić EPUB w Pobranych.");
                return target;
            } finally {
                if (!published) getContentResolver().delete(target, null, null);
            }
        } else {
            File documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (documents == null) throw new IOException("Pamięć telefonu jest niedostępna.");
            File dir = new File(documents, "EPUB");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Brak folderu na EPUB.");
            File dest = new File(dir, name);
            int i = 2;
            while (dest.exists()) dest = new File(dir, stem(name) + " (" + i++ + ").epub");
            File temporary = File.createTempFile(".kindle-epub-", ".part", dir);
            try {
                try (java.io.OutputStream out = new java.io.FileOutputStream(temporary)) {
                    copyEpub(epub, out);
                }
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Przerwano zapis EPUB.");
                if (!temporary.renameTo(dest)) throw new IOException("Nie udało się zapisać EPUB na telefonie.");
                return Uri.fromFile(dest);
            } finally {
                temporary.delete();
            }
        }
    }

    private void startBatch(java.util.LinkedHashMap<Uri, String> names) {
        if (sending || names.isEmpty()) return;
        java.util.ArrayList<Uri> files = new java.util.ArrayList<>(names.keySet());
        final String host = savedAddress();
        if (!privateAddress(host)) return;
        if (!batchRunning.compareAndSet(false, true)) {
            android.widget.Toast.makeText(this, "Poczekaj na zakończenie poprzedniej wysyłki.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String reader = settings.getString("kindle_key", host);
        recoveredHost = host;

        cancellationRequested = false;
        recentReceiptFailed = false;
        busy(true);
        cancelButton.setEnabled(true);
        cancelButton.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        progress.setVisibility(isReducedMotion() ? View.INVISIBLE : View.VISIBLE);
        showWorking();
        transferThread = new Thread(() -> {
            int ok = 0;
            int partial = 0;
            int attempted = 0;
            java.util.ArrayList<String> confirmed = new java.util.ArrayList<>();
            java.util.ArrayList<Uri> finished = new java.util.ArrayList<>();
            StringBuilder report = new StringBuilder();
            fileCount = files.size();
            try {
                for (int i = 0; i < files.size(); i++) {
                    if (cancellationRequested || Thread.currentThread().isInterrupted()) break;
                    currentFile = i + 1;
                    Uri uri = files.get(i);
                    String name = names.get(uri);
                    String id = java.util.UUID.randomUUID().toString();
                    attempted++;
                    SendOutcome outcome = new SendOutcome();
                    try {
                        java.util.Properties checkpoint = queue.info(queuedFile(uri));
                        boolean pdf = name.toLowerCase(Locale.ROOT).endsWith(".pdf");
                        if (pdf && pdfMode != 0 && pdfOptions != null) {
                            outcome.options = java.util.Arrays.asList(
                                    pdfOptions.title, pdfOptions.author, pdfOptions.language,
                                    pdfOptions.firstPage, pdfOptions.lastPage, pdfOptions.joinHyphens,
                                    pdfOptions.removePageNumbers, pdfOptions.includeImages,
                                    pdfOptions.ocrScans, pdfOptions.forceOcr, batchPdfCount > 1).toString();
                        }
                        if (pdf && reader.equals(checkpoint.getProperty("reader"))) {
                            if (pdfMode != 1) {
                                outcome.pdfHash = checkpoint.getProperty("pdfHash", "");
                                outcome.pdfPath = checkedReceipt(host, checkpoint.getProperty("pdf"), outcome.pdfHash);
                            }
                            if (!outcome.options.isEmpty() && outcome.options.equals(checkpoint.getProperty("options"))) {
                                outcome.epubHash = checkpoint.getProperty("epubHash", "");
                                outcome.epubPath = checkedReceipt(host, checkpoint.getProperty("epub"), outcome.epubHash);
                                outcome.localEpub = checkpoint.getProperty("localUri", "");
                                if (!localEpubMatches(outcome)) outcome.localEpub = "";
                            }
                        }
                        record(id, name, host, "W trakcie / jeszcze niepotwierdzona", "");
                        final String shownName = name;
                        final int index = currentFile, total = fileCount;
                        runOnUiThread(() -> {
                            progress.setIndeterminate(true);
                            progress.setVisibility(isReducedMotion() ? View.INVISIBLE : View.VISIBLE);
                            stateTitle.setText("Przygotowuję książkę…");
                            progressLabel.setText("Książka " + index + " z " + total);
                            status.setText("Teraz: " + shownName);
                        });
                        if (pdf && pdfMode != 0 && pdfOptions != null) {
                            convertAndSend(host, reader, uri, name, pdfMode == 2, outcome);
                            String result = outcome.toString();
                            String state = outcome.complete ? "Zapisano i sprawdzono"
                                    : outcome.anyOnKindle ? "Częściowo wykonano" : "Niepotwierdzona";
                            record(id, name, host, state, result);
                            if (outcome.complete && outcome.anyOnKindle) {
                                removeFromQueue(uri);
                                ok++;
                                confirmed.add(name);
                                finished.add(uri);
                            } else {
                                if (outcome.anyOnKindle) partial++;
                                markQueued(uri, outcome.anyOnKindle ? "Częściowo wysłano" : "Do ponowienia");
                            }
                            report.append(result).append('\n');
                        } else {
                            String path = pdf && outcome.pdfPath != null ? outcome.pdfPath : send(host, uri, name);
                            removeFromQueue(uri);
                            record(id, name, host, "Zapisano i sprawdzono", path);
                            ok++;
                            confirmed.add(name);
                            finished.add(uri);
                            report.append("✓ ").append(path).append('\n');
                        }
                    } catch (Exception error) {
                        if (cancellationRequested || Thread.currentThread().isInterrupted() || error instanceof InterruptedException) {
                            if (outcome.anyOnKindle) partial++;
                            markQueued(uri, "Przerwano · do ponowienia");
                            String saved = outcome.toString();
                            record(id, name, host, "Przerwano przez użytkownika", saved.isEmpty()
                                    ? "Wysyłka tej książki nie została potwierdzona." : saved);
                            report.append("■ Przerwano: ").append(name).append('\n');
                            if (!saved.isEmpty()) report.append(saved).append('\n');
                            cancellationRequested = true;
                            break;
                        }
                        String detail = userMessage(error);
                        markQueued(uri, "Do ponowienia");
                        record(id, name, host, "Niepotwierdzona", detail);
                        report.append("✗ ").append(name).append(": ").append(detail).append('\n');
                    }
                }
            } finally {
                boolean stopped = cancellationRequested;
                final String result = (stopped ? "Przerwano. " : "Zakończono. ")
                        + "W pełni wysłane: " + ok + " z " + files.size()
                        + (partial > 0 ? ", częściowo: " + partial : "")
                        + (attempted < files.size() ? ", pominięte: " + (files.size() - attempted) : "")
                        + "\n\n" + report
                        + (recentReceiptFailed ? "\nNie zaktualizowano części listy ostatnich przesyłek. Książki są w documents.\n" : "")
                        + "\nW KOReaderze otwórz documents. Wyjdź z folderu i wejdź ponownie, aby odświeżyć listę.";
                settings.edit().putString("lastResult", result).apply();
                transferThread = null;
                batchRunning.set(false);
                final int sentOk = ok;
                final int sentPartial = partial;
                final java.util.ArrayList<String> confirmedOnKindle = new java.util.ArrayList<>(confirmed);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    sharedFiles.removeAll(finished);
                    busy(false);
                    progress.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                    progressLabel.setText(stopped ? "Przerwano" : "Przesyłka zakończona");
                    lastDetails = result;
                    detailsButton.setVisibility(View.VISIBLE);
                    renderConfirmed(confirmedOnKindle);
                    if (!stopped && sentOk == files.size() && sentPartial == 0) {
                        showSuccess(files.size() == 1 ? "Książka jest na Kindle!" : "Książki są na Kindle!", files.size() == 1
                                ? "Książka została przesłana i sprawdzona. Możesz zacząć czytać."
                                : "Przesłano i sprawdzono wszystkie książki: " + files.size() + ". Miłego czytania!");
                    } else {
                        showNotComplete(stopped, sentOk, sentPartial, files.size());
                    }
                    updateChoose();
                });
            }
        }, "kindle-batch");
        transferThread.start();
    }

    private synchronized void record(String id, String name, String host, String result, String detail) {
        try {
            org.json.JSONArray old = new org.json.JSONArray(settings.getString("history", "[]"));
            org.json.JSONArray next = new org.json.JSONArray();
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("id", id); item.put("name", name); item.put("host", host);
            item.put("time", System.currentTimeMillis()); item.put("result", result); item.put("detail", detail);
            next.put(item);
            for (int i = 0; i < old.length() && next.length() < 100; i++) {
                org.json.JSONObject existing = old.getJSONObject(i);
                if (!id.equals(existing.optString("id"))) next.put(existing);
            }
            settings.edit().putString("history", next.toString()).commit();
        } catch (org.json.JSONException ignored) { }
    }

    private void showHistory() {
        LinearLayout entries = column();
        entries.setPadding(dp(18), dp(8), dp(18), dp(14));
        try {
            org.json.JSONArray items = new org.json.JSONArray(settings.getString("history", "[]"));
            java.text.DateFormat date = new java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault());
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.getJSONObject(i);
                String name = item.optString("name");
                String result = item.optString("result");
                String detail = item.optString("detail");
                LinearLayout entry = column();
                entry.setPadding(dp(13), dp(11), dp(13), dp(8));
                entry.setBackground(rounded(BG, 14, BORDER));
                add(entry, heading(name, 15, INK), 0);
                add(entry, label(result, 14, MUTED), 3);
                add(entry, label(date.format(new java.util.Date(item.optLong("time"))), 13, MUTED), 3);
                if (!detail.isEmpty()) {
                    Button more = action("Szczegóły", Glyph.INFO, GREEN, BG, 0);
                    more.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    more.setOnClickListener(v -> new DialogUi.Builder(this).setTitle(name)
                            .setMessage(detail).setPositiveButton("Zamknij", null).show());
                    add(entry, more, 0);
                }
                add(entries, entry, i == 0 ? 0 : 8);
            }
        } catch (org.json.JSONException e) {
            add(entries, label("Nie można odczytać części zapisanej historii.", 15, INK), 0);
        }
        if (entries.getChildCount() == 0) {
            add(entries, label("Tutaj pojawią się informacje o Twoich przesyłkach.", 15, INK), 0);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(entries);
        new DialogUi.Builder(this).setTitle("Ostatnio wysłane")
                .setView(scroll).setPositiveButton("Zamknij", null).show();
    }

    @Override public void onBackPressed() {
        if (sending) android.widget.Toast.makeText(this, "Poczekaj na zakończenie przesyłania.", android.widget.Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }

    static boolean privateAddress(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = host.split("\\.");
        int[] octet = new int[4];
        for (int i = 0; i < 4; i++) {
            octet[i] = Integer.parseInt(parts[i]);
            if (octet[i] > 255 || !Integer.toString(octet[i]).equals(parts[i])) return false;
        }
        return octet[0] == 10 || (octet[0] == 172 && octet[1] >= 16 && octet[1] <= 31)
                || (octet[0] == 192 && octet[1] == 168);
    }

    private Session connect(String host) throws Exception {
        KindleFinder.Pin pin = detectedPin;
        if (pin == null && !settings.getString("kindle_key", "").isEmpty()) pin = pairedKindle(host);
        return connect(host, detectedKindle, pin);
    }

    private Session connect(String host, KindleFinder.Match match, KindleFinder.Pin pin) throws Exception {
        boolean useKey = settings.getBoolean("ssh_use_key", false);
        UserInfo user = new UserInfo() {
            public String getPassphrase() { return null; }
            public String getPassword() { return useKey ? null : "root"; }
            public boolean promptPassword(String message) { return false; }
            public boolean promptPassphrase(String message) { return false; }
            public void showMessage(String message) { }
            public boolean promptYesNo(String message) {
                if (message == null || !message.contains("authenticity") || !message.contains("fingerprint")) {
                    return false;
                }
                return confirmHost(host, message);
            }
        };
        return KindleConnection.open(knownKindleHosts(), sshIdentity, host, match, pin, useKey, user, 12000);
    }

    private String send(String host, Uri file, String name) throws Exception {
        File cached = File.createTempFile("upload-", ".tmp", getCacheDir());
        try {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".epub") || lower.endsWith(".pdf") || lower.endsWith(".mobi")
                    || lower.endsWith(".azw") || lower.endsWith(".azw3") || lower.endsWith(".fb2")
                    || lower.endsWith(".txt") || lower.endsWith(".djvu") || lower.endsWith(".cbz"))) {
                throw new IOException("Wybierz książkę, np. plik z końcówką .epub lub .pdf.");
            }
            try (InputStream in = openBook(file);
                 java.io.OutputStream out = new java.io.FileOutputStream(cached)) {
                if (in == null) throw new IOException("Nie można otworzyć pliku na telefonie.");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || cancellationRequested)
                        throw new java.io.InterruptedIOException("Przerwano przygotowywanie książki.");
                    out.write(buffer, 0, count);
                }
            }
            if (lower.endsWith(".epub")) {
                try (java.util.zip.ZipFile epub = new java.util.zip.ZipFile(cached)) {
                    if (epub.getEntry("META-INF/container.xml") == null)
                        throw new IOException("Ten plik nie zawiera struktury książki EPUB.");
                }
            }
            return uploadFile(host,cached,name);
        } finally { cached.delete(); }
    }

    private void markQueued(Uri uri, String state) {
        try {
            File book = queuedFile(uri);
            java.util.Properties info = queue.info(book);
            info.setProperty("state", state);
            queue.update(book, info);
        } catch (IOException ignored) {}
    }

    private interface SftpAction<T> {
        T run(ChannelSftp channel, boolean retry) throws Exception;
    }

    private boolean connectionLost(Exception error, Session session, ChannelSftp channel) {
        if (error instanceof SftpException) {
            int code = ((SftpException) error).id;
            return code == ChannelSftp.SSH_FX_NO_CONNECTION || code == ChannelSftp.SSH_FX_CONNECTION_LOST
                    || channel != null && !channel.isConnected();
        }
        if (error instanceof java.net.SocketException || error instanceof java.net.SocketTimeoutException) return true;
        if (error instanceof JSchException) {
            String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
            return !message.contains("auth") && !message.contains("hostkey") && !message.contains("algorithm")
                    && (session == null || !session.isConnected() || channel == null || !channel.isConnected());
        }
        return error instanceof IOException && (session != null && !session.isConnected()
                || channel != null && !channel.isConnected());
    }

    private <T> T onReader(String host, SftpAction<T> action) throws Exception {
        KindleFinder.Pin pin = detectedPin != null ? detectedPin : pairedKindle(host);
        String address = recoveredHost == null ? host : recoveredHost;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled();
            KindleFinder.Match route = detectedKindle;
            if (attempt > 0) {
                final int remaining = fileCount - currentFile + 1;
                showPhase("Czekam na Kindle…", "Pozostało w tej przesyłce: " + remaining, true);
                Thread.sleep(1200);
                java.util.List<KindleFinder.Found> devices;
                try { devices = new KindleFinder(this).find(address, pin); }
                catch (KindleFinder.ScanIssue changed) { throw changed; }
                catch (InterruptedException stopped) { throw stopped; }
                catch (Exception unavailable) { continue; }
                if (devices.isEmpty()) continue;
                route = devices.get(0).match;
                address = route.host;
            }
            Session session = null;
            ChannelSftp channel = null;
            try {
                session = connect(address, route, pin);
                channel = (ChannelSftp)session.openChannel("sftp");
                channel.connect(12000);
                if (!channel.stat("/mnt/us/koreader").isDir())
                    throw new IOException("Pod tym adresem nie znaleziono folderu KOReadera.");
                if (attempt > 0) {
                    recoveredHost = address;
                    detectedKindle = route;
                    settings.edit().putString("address", address).apply();
                }
                return action.run(channel, attempt > 0);
            } catch (Exception failure) {
                checkCancelled();
                if (pin == null || !connectionLost(failure, session, channel)) throw failure;
                last = failure;
            } finally {
                if (channel != null) channel.disconnect();
                if (session != null) session.disconnect();
            }
        }
        throw last == null ? new IOException("Kindle jest poza zasięgiem. Połącz ponownie i spróbuj jeszcze raz.") : last;
    }

    private String checkedReceipt(String host, String path, String hash) throws Exception {
        if (path == null || path.isEmpty() || hash.isEmpty()) return null;
        showPhase("Sprawdzam wcześniejszy zapis…", "", true);
        return onReader(host, (channel, retry) -> VerifiedTransfer.matches(channel, DESTINATION, path, hash) ? path : null);
    }

    private void showPhase(String title, String detail, boolean indeterminate) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || cancellationRequested) return;
            if (!title.contentEquals(stateTitle.getText())) {
                stateTitle.animate().cancel();
                stateTitle.setText(title);
                stateTitle.setAlpha(isReducedMotion() ? 1f : 0.72f);
                if (!isReducedMotion()) stateTitle.animate().alpha(1f).setDuration(150).start();
            }
            status.setText(detail);
            progress.setVisibility(indeterminate && isReducedMotion() ? View.INVISIBLE : View.VISIBLE);
            progress.setIndeterminate(indeterminate);
        });
    }

    private String uploadFile(String host, File source, String name) throws Exception {
        java.util.UUID transferId = java.util.UUID.randomUUID();
        return onReader(host, (channel, retry) -> {
            VerifiedTransfer.cleanAbandoned(channel, DESTINATION);
            if (retry) VerifiedTransfer.removeAttempt(channel, DESTINATION, transferId);
            if (retry || settings.getBoolean("skip_identical", true)) {
                showPhase("Sprawdzam, czy książka już jest na Kindle…", name, true);
                String match = VerifiedTransfer.identical(channel, source, DESTINATION, name);
                if (match != null) {
                    if (!recordRecent(channel, match)) recentReceiptFailed = true;
                    return match;
                }
            }
            String path = VerifiedTransfer.upload(channel, source, name, DESTINATION,
                text -> showPhase(text.startsWith("Sprawdzam") ? "Sprawdzam zapis na Kindle…" : "Wysyłam na Kindle…",
                        name, false),
                percent -> {
                    final int index = currentFile, total = fileCount;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || cancellationRequested) return;
                        progress.setVisibility(View.VISIBLE);
                        progress.setIndeterminate(false);
                        progress.setProgress(percent);
                        progressLabel.setText("Książka " + index + " z " + total + " • " + percent + "%");
                    });
                }, transferId);
            if (!recordRecent(channel, path)) recentReceiptFailed = true;
            return path;
        });
    }

    private boolean confirmHost(String host, String fingerprint) {
        AtomicBoolean accepted = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                latch.countDown();
                return;
            }
            java.util.regex.Matcher key = java.util.regex.Pattern.compile("fingerprint is ([^\\s]+)").matcher(fingerprint);
            String displayedKey = key.find() ? key.group(1).replaceFirst("\\.$", "") : fingerprint;
            AlertDialog dialog = new DialogUi.Builder(this)
                    .setTitle("Czy to Twój Kindle?")
                    .setMessage("Adres znalezionego czytnika\n" + host
                            + "\n\nPotwierdź połączenie, jeśli ten sam adres widzisz w KOReaderze.")
                    .setDetails("Odcisk klucza SSH: " + displayedKey)
                    .setPositiveButton("Połącz z tym Kindle’em", (d, which) -> accepted.set(true))
                    .setNegativeButton("Anuluj", (d, which) -> {})
                    .create();
            dialog.setOnDismissListener(d -> latch.countDown());
            dialog.show();
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                if (dialog.isShowing()) dialog.dismiss();
            }, 60000);
        });
        try {
            return latch.await(62, TimeUnit.SECONDS) && accepted.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String displayName(Uri file, android.os.CancellationSignal signal) {
        String name = file.getLastPathSegment() == null ? "plik" : file.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(file,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null, signal)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                name = cursor.getString(0);
            }
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) name = "plik";
        if (!RemoteFiles.book(name)) {
            String mime = getContentResolver().getType(file);
            if ("application/epub+zip".equals(mime)) name += ".epub";
            else if ("application/pdf".equals(mime)) name += ".pdf";
            else if ("application/x-mobipocket-ebook".equals(mime)) name += ".mobi";
        }
        if (name.startsWith(".")) name = "plik" + name;
        int dot = name.lastIndexOf('.');
        String suffix = dot > 0 && name.length() - dot <= 20 ? name.substring(dot) : "";
        if (name.getBytes(StandardCharsets.UTF_8).length > 220) {
            String stem = name.substring(0, name.length() - suffix.length());
            while ((stem + suffix).getBytes(StandardCharsets.UTF_8).length > 220) {
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
            }
            name = stem + suffix;
        }
        return name;
    }

    static String userMessage(Exception error) {
        String detail = error.getMessage() == null ? "" : error.getMessage();
        if (error instanceof JSchException) {
            String lower = detail.toLowerCase(Locale.ROOT);
            if (lower.contains("reject hostkey") || lower.contains("hostkey has been changed")) {
                return "Nie potwierdzono tożsamości Kindle’a. Sprawdź adres na ekranie KOReadera.";
            }
            if (lower.contains("auth")) {
                return "Kindle nie zaakceptował połączenia. Sprawdź ustawienia serwera SSH w KOReaderze.";
            }
            return "Nie udało się połączyć z Kindle. Sprawdź sieć Wi‑Fi lub hotspot i upewnij się, że w KOReaderze jest włączony serwer SSH.";
        }
        if (error instanceof SftpException) {
            return "Nie udało się dokończyć pracy z książkami na Kindle. Sprawdź połączenie i wolne miejsce na czytniku.";
        }
        return "Zapis niepotwierdzony. " + detail;
    }
}
