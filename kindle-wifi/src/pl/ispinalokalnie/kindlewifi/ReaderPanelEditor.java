package pl.ispinalokalnie.kindlewifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class ReaderPanelEditor {
    interface FontLoader { void load(Consumer<Map<String, Typeface>> done); }
    private static final int INK = 0xff172d28, GREEN = 0xff175748, MUTED = 0xff546a60;
    private static final String SAMPLE = "Cichy wieczór, dobra książka.\nJeszcze tylko jeden rozdział…";
    private final Activity activity;
    private final ReaderPanel.Snapshot snapshot;
    private final FontLoader fontLoader;
    private final LinearLayout fields;
    private LinearLayout group;
    private final Map<String, java.util.function.Supplier<String>> inputs = new LinkedHashMap<>();
    private Map<String, Typeface> faces = new LinkedHashMap<>();
    private final Map<String, TextView> fontSamples = new LinkedHashMap<>();
    private String font;
    private TextView fontName, preview, previewNote, fontStatus;
    private Button retryFonts;
    private boolean loadingFonts, fontsLoaded;
    private Slider size, spacing, left, right;

    ReaderPanelEditor(Activity activity, ReaderPanel.Snapshot snapshot, FontLoader fontLoader) {
        this.activity = activity; this.snapshot = snapshot; this.fontLoader = fontLoader;
        fields = column(); fields.setPadding(dp(16), dp(8), dp(16), dp(16));
        fields.setBackgroundColor(0xfff6f5ee); group = fields;
    }

    static void showMenu(Activity activity, Consumer<Integer> select) {
        ReaderPanelEditor ui = new ReaderPanelEditor(activity, null, null);
        ui.note("Dopasuj czytnik do siebie. Wybierz, co chcesz zmienić.");
        ScrollView scroll = new ScrollView(activity); scroll.addView(ui.fields);
        AlertDialog dialog = new DialogUi.Builder(activity).setTitle("Twój KOReader")
                .setView(scroll).setNegativeButton("Zamknij", null).create();
        String[] titles = { "Wygląd książek", "Biblioteka", "Kopia ustawień", "Stan zmian", "Zainstaluj lub zaktualizuj panel" };
        String[] notes = { "Czcionki z podglądem, rozmiar i marginesy", "Okładki, folder i kolejność książek",
                "Zapisz ulubione ustawienia lub wróć do poprzednich", "Sprawdź, czy Kindle zapisał Twoje ustawienia",
                "Pierwsza instalacja lub odświeżenie panelu" };
        for (int i = 0; i < titles.length; i++) {
            final int item = i;
            ui.section(titles[i] + "  ›", notes[i]);
            ui.group.setFocusable(true);
            ui.group.setContentDescription(titles[i] + ". " + notes[i]);
            ui.group.setOnClickListener(v -> { dialog.dismiss(); select.accept(item); });
        }
        dialog.show(); ui.fit(dialog);
    }

    void show(boolean reading, Consumer<Map<String, String>> save) {
        if (reading) readingFields(); else libraryFields();
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.addView(fields);
        AlertDialog dialog = new DialogUi.Builder(activity).setTitle(reading ? "Wygląd książek" : "Twoja biblioteka")
                .setView(scroll).setNegativeButton("Anuluj", null).setPositiveButton("Zapisz na Kindle’u", null).create();
        dialog.setOnShowListener(event -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Map<String, String> changes = new LinkedHashMap<>();
                try {
                    for (Map.Entry<String, java.util.function.Supplier<String>> field : inputs.entrySet()) {
                        String value = field.getValue().get();
                        if (!value.equals(snapshot.values.get(field.getKey()))) changes.put(field.getKey(), value);
                    }
                } catch (IllegalArgumentException error) {
                    new DialogUi.Builder(activity).setTitle("Sprawdź ustawienia").setMessage(error.getMessage())
                            .setPositiveButton("Wróć do edycji", null).show(); return;
                }
                if (changes.isEmpty()) { Toast.makeText(activity, "Wszystko jest już tak ustawione.", Toast.LENGTH_SHORT).show(); return; }
                dialog.dismiss(); save.accept(changes);
            });
        });
        dialog.show(); fit(dialog);
    }

    private String savedStateNote() {
        String time = snapshot.savedAt <= 0 ? "" : "Zapis na Kindle’u "
                + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(snapshot.savedAt * 1000L)) + ". ";
        return time + "Aby odświeżyć ustawienia, przejdź na czytniku do listy plików.";
    }

    private void readingFields() {
        note(savedStateNote());
        section("Czcionka", "Dotknij nazwy, aby porównać kroje pisma.");
        font = snapshot.values.get("font");
        fontName = text("", 18, INK); fontName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        fontName.setMinHeight(dp(52)); fontName.setGravity(Gravity.CENTER_VERTICAL);
        fontName.setBackground(background(0xffe2f0e7, 12)); fontName.setPadding(dp(12), dp(8), dp(12), dp(8));
        fontName.setOnClickListener(v -> chooseFont()); group.addView(fontName);
        inputs.put("font", () -> font);
        size = slider("Rozmiar tekstu", "size", 8, 70, 22, "", false);
        section("Układ strony", "Dostosuj odstępy i ilość miejsca po bokach tekstu.");
        spacing = slider("Odstęp między wierszami", "spacing", 60, 220, 100, "%", false);
        String original = snapshot.values.get("margins");
        String[] pair = original.split(",", -1);
        Switch defaults = defaultSwitch("Marginesy domyślne", original.isEmpty());
        left = new Slider("Lewy margines", pair.length == 2 ? pair[0] : "", 0, 80, 10, " px", false);
        right = new Slider("Prawy margines", pair.length == 2 ? pair[1] : "", 0, 80, 10, " px", false);
        left.enable(!defaults.isChecked()); right.enable(!defaults.isChecked());
        defaults.setOnCheckedChangeListener((v, enabled) -> { left.enable(!enabled); right.enable(!enabled); updatePreview(); });
        inputs.put("margins", () -> defaults.isChecked() ? "" : left.value() + "," + right.value());
        note("px oznacza piksele na ekranie Kindle’a.");
        section("Podgląd tekstu", "Przykład pomaga ocenić wybór. Układ na Kindle’u może się różnić.");
        preview = text(SAMPLE, 22, INK); preview.setBackground(background(0xfffffdf6, 12));
        preview.setPadding(dp(14), dp(18), dp(14), dp(18)); group.addView(preview);
        previewNote = text("", 12, MUTED); group.addView(previewNote);
        section("Dokumenty PDF", "Wybierz, jak wyświetlać tekst dokumentów.");
        select("Wygląd PDF", "reflow", options("", "Tak jak w KOReaderze", "0", "Oryginalna strona", "1", "Tekst dopasowany do ekranu"));
        group = fields;
        note("To ustawienia domyślne. Książka z własnymi ustawieniami zachowa swój wygląd. Aby zastosować zmiany, wróć na Kindle’u do listy plików.");
        updatePreview();
    }

    private void chooseFont() {
        LinearLayout list = column(); list.setPadding(dp(16), dp(8), dp(16), dp(16));
        fontStatus = text("", 13, MUTED); list.addView(fontStatus);
        retryFonts = new Button(activity); retryFonts.setText("Pobierz podglądy z Kindle’a"); retryFonts.setAllCaps(false);
        list.addView(retryFonts); retryFonts.setOnClickListener(v -> loadFonts());
        ScrollView scroll = new ScrollView(activity); scroll.addView(list);
        AlertDialog picker = new DialogUi.Builder(activity).setTitle("Wybierz czcionkę")
                .setView(scroll).setNegativeButton("Wróć", null).create();
        ArrayList<String> names = new ArrayList<>(snapshot.fonts);
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (!font.isEmpty() && !names.contains(font)) names.add(0, font);
        names.add(0, ""); fontSamples.clear();
        for (String name : names) {
            LinearLayout row = column(); row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackground(background(name.equals(font) ? 0xffe2f0e7 : 0xffffffff, 12));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(8); list.addView(row, layout);
            TextView title = text((name.equals(font) ? "✓  " : "") + (name.isEmpty() ? "Domyślna KOReadera" : name), 15, GREEN);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); row.addView(title);
            TextView sample = text("", 19, INK); sample.setPadding(0, dp(6), 0, dp(4)); row.addView(sample);
            fontSamples.put(name, sample); renderFontSample(name, sample);
            row.setContentDescription((name.isEmpty() ? "Domyślna KOReadera" : name) + (name.equals(font) ? ", wybrana" : ""));
            row.setOnClickListener(v -> { font = name; updatePreview(); picker.dismiss(); });
            row.setFocusable(true);
        }
        if (snapshot.fonts.isEmpty()) {
            TextView empty = text("Lista czcionek nie dotarła jeszcze z Kindle’a. W KOReaderze wybierz Narzędzia → Ustawienia z telefonu, a potem otwórz ten ekran ponownie.", 14, MUTED);
            list.addView(empty);
        }
        picker.show(); fit(picker); updateFontStatus();
        if (!fontsLoaded && !loadingFonts && !snapshot.fonts.isEmpty()) loadFonts();
    }

    private void loadFonts() {
        if (loadingFonts) return;
        loadingFonts = true; updateFontStatus();
        fontLoader.load(result -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            loadingFonts = false; fontsLoaded = true; faces = result;
            for (Map.Entry<String, TextView> entry : fontSamples.entrySet()) renderFontSample(entry.getKey(), entry.getValue());
            updateFontStatus(); updatePreview();
        });
    }

    private void updateFontStatus() {
        if (fontStatus == null) return;
        fontStatus.setText(loadingFonts ? "Pobieram kroje pisma z Kindle’a… Możesz już wybrać czcionkę."
                : faces.isEmpty() ? "Podglądy wymagają połączenia z Kindle’em."
                : "Podglądy z Kindle’a: " + faces.size() + " z " + snapshot.fonts.size() + ".");
        retryFonts.setEnabled(!loadingFonts);
        retryFonts.setVisibility(!loadingFonts && faces.size() == snapshot.fonts.size() ? View.GONE : View.VISIBLE);
    }

    private void renderFontSample(String name, TextView sample) {
        Typeface face = faces.get(name);
        sample.setTypeface(face == null ? Typeface.DEFAULT : face);
        sample.setText(name.isEmpty() ? "Krój wybrany przez KOReadera" : face == null
                ? "Podgląd jeszcze niedostępny" : "Zażółć gęślą jaźń. Jeszcze rozdział…");
        sample.setTextSize(face == null ? 13 : 19); sample.setTextColor(face == null ? MUTED : INK);
    }

    private void updatePreview() {
        if (fontName != null) fontName.setText((font.isEmpty() ? "Domyślna KOReadera" : font) + "  ›");
        if (preview == null) return;
        Typeface face = faces.get(font); preview.setTypeface(face == null ? Typeface.SERIF : face);
        preview.setTextSize(Math.max(10, Math.min(40, size.visual())));
        preview.setLineSpacing(0, spacing.visual() / 100f);
        preview.setPadding(dp(8 + left.visual() / 2), dp(18), dp(8 + right.visual() / 2), dp(18));
        previewNote.setText(face == null ? "Przykładowy krój. Otwórz wybór czcionki, aby pobrać jej podgląd."
                : "Krój z Twojego Kindle’a: " + font + ". Wielkość i marginesy są orientacyjne.");
    }

    private Slider slider(String title, String key, int min, int max, int fallback, String unit, boolean unused) {
        String original = snapshot.values.get(key);
        Switch defaults = defaultSwitch((key.equals("size") ? "Rozmiar domyślny" : "Odstęp domyślny"), original.isEmpty());
        Slider slider = new Slider(title, original, min, max, fallback, unit, true);
        slider.enable(!defaults.isChecked());
        defaults.setOnCheckedChangeListener((v, enabled) -> { slider.enable(!enabled); updatePreview(); });
        inputs.put(key, () -> defaults.isChecked() ? "" : slider.value());
        return slider;
    }

    private final class Slider {
        final String original, unit; final int fallback, min;
        final SeekBar bar; final TextView value; final Button minus, plus;
        boolean touched, enabled;
        Slider(String title, String original, int min, int max, int fallback, String unit, boolean unused) {
            this.original = original; this.unit = unit; this.fallback = fallback; this.min = min;
            LinearLayout head = new LinearLayout(activity); head.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(title, 15, INK); head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            value = text("", 15, GREEN); value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); head.addView(value); group.addView(head);
            LinearLayout controls = new LinearLayout(activity); controls.setGravity(Gravity.CENTER_VERTICAL);
            minus = stepButton("−", "Zmniejsz: " + title); plus = stepButton("+", "Zwiększ: " + title);
            controls.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
            bar = new SeekBar(activity); bar.setMax(max - min);
            int initial = fallback;
            try { if (!original.isEmpty()) initial = Math.round(Float.parseFloat(original)); } catch (NumberFormatException ignored) { }
            bar.setProgress(Math.max(0, Math.min(max - min, initial - min)));
            bar.setProgressTintList(ColorStateList.valueOf(GREEN)); bar.setThumbTintList(ColorStateList.valueOf(GREEN));
            bar.setContentDescription(title); controls.addView(bar, new LinearLayout.LayoutParams(0, dp(48), 1));
            controls.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48))); group.addView(controls);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar b, int progress, boolean fromUser) { if (fromUser) touched = true; refresh(); updatePreview(); }
                public void onStartTrackingTouch(SeekBar b) { }
                public void onStopTrackingTouch(SeekBar b) { }
            });
            minus.setOnClickListener(v -> step(-1)); plus.setOnClickListener(v -> step(1));
        }
        void step(int delta) { touched = true; bar.setProgress(bar.getProgress() + delta); refresh(); updatePreview(); }
        String value() { return !touched && !original.isEmpty() ? original : Integer.toString(min + bar.getProgress()); }
        int visual() { return enabled ? min + bar.getProgress() : fallback; }
        void enable(boolean state) { enabled = state; bar.setEnabled(state); refresh(); }
        void refresh() {
            value.setText(enabled ? value() + unit : "Domyślnie");
            minus.setEnabled(enabled && bar.getProgress() > 0); plus.setEnabled(enabled && bar.getProgress() < bar.getMax());
        }
    }

    private void libraryFields() {
        note(savedStateNote());
        section("Folder z książkami", "Zostaw puste pole, aby zachować domyślny folder KOReadera.");
        EditText home = new EditText(activity); home.setSingleLine(true); home.setTextSize(16);
        home.setInputType(InputType.TYPE_CLASS_TEXT); home.setHint("np. /mnt/us/documents");
        home.setText(snapshot.values.get("home")); group.addView(home);
        inputs.put("home", () -> {
            String path = home.getText().toString().trim();
            if (!path.equals(snapshot.values.get("home")) && !path.isEmpty() && !path.equals("/mnt/us") && !path.startsWith("/mnt/us/"))
                throw new IllegalArgumentException("Wybierz folder w pamięci Kindle’a, np. /mnt/us/documents.");
            return path;
        });
        section("Kolejność książek", "Ułóż listę tak, jak lubisz.");
        LinkedHashMap<String, String> sort = options("", "Tak jak w KOReaderze");
        for (String id : snapshot.sorts) sort.put(id, sortLabel(id));
        select("Sortuj według", "sort", sort);
        select("Kierunek", "reverse", options("", "Tak jak w KOReaderze", "false", "Zwykła kolejność", "true", "Od końca"));
        section("Wygląd biblioteki", "Lista lub siatka — z okładkami albo bez nich.");
        if ("!".equals(snapshot.values.get("view"))) note("Aby zmieniać widok, włącz w KOReaderze wtyczkę Cover Browser.");
        else select("Pokaż książki jako", "view", options("classic", "Prosta lista", "list_only_meta", "Lista tytułów",
                "list_image_meta", "Lista z okładkami", "list_image_filename", "Pliki z okładkami", "mosaic_image", "Siatka okładek", "mosaic_text", "Siatka tytułów"));
    }

    private String sortLabel(String id) {
        switch (id) {
            case "strcoll": return "Nazwa"; case "natural": return "Nazwa i numer";
            case "access": return "Ostatnio otwierane"; case "date": return "Data zmiany";
            case "size": return "Rozmiar pliku"; case "type": return "Format pliku";
            case "title": return "Tytuł"; case "authors": return "Autor"; case "series": return "Seria"; default: return id;
        }
    }
    private void select(String title, String key, LinkedHashMap<String, String> options) {
        TextView label = text(title, 14, MUTED); label.setPadding(0, dp(10), 0, 0); group.addView(label);
        String current = snapshot.values.get(key); if (!options.containsKey(current)) options.put(current, current);
        ArrayList<String> keys = new ArrayList<>(options.keySet());
        Spinner spinner = new Spinner(activity); spinner.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, new ArrayList<>(options.values()));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setSelection(keys.indexOf(current)); group.addView(spinner);
        inputs.put(key, () -> keys.get(spinner.getSelectedItemPosition()));
    }
    private static LinkedHashMap<String, String> options(String... pairs) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]); return result;
    }
    private Switch defaultSwitch(String label, boolean checked) {
        Switch toggle = new Switch(activity); toggle.setText(label); toggle.setTextSize(14); toggle.setTextColor(MUTED);
        toggle.setMinHeight(dp(48)); toggle.setChecked(checked); group.addView(toggle); return toggle;
    }
    private Button stepButton(String text, String description) {
        Button button = new Button(activity); button.setText(text); button.setTextSize(20); button.setTextColor(GREEN);
        button.setPadding(0, 0, 0, 0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setContentDescription(description); return button;
    }
    private void section(String title, String subtitle) {
        group = column(); group.setPadding(dp(14), dp(14), dp(14), dp(14)); group.setBackground(background(0xffffffff, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12); fields.addView(group, lp);
        TextView heading = text(title, 19, INK); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD); group.addView(heading); note(subtitle);
    }
    private void note(String message) { TextView label = text(message, 13, MUTED); label.setPadding(0, dp(5), 0, dp(10)); group.addView(label); }
    private TextView text(String message, int size, int color) {
        TextView result = new TextView(activity); result.setText(message); result.setTextSize(size); result.setTextColor(color); return result;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void fit(AlertDialog dialog) {
        android.util.DisplayMetrics m = activity.getResources().getDisplayMetrics();
        dialog.getWindow().setLayout(Math.min(m.widthPixels - dp(20), dp(600)), (int) (m.heightPixels * .88f));
    }
}
