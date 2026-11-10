package pl.ispinalokalnie.kindlewifi;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import android.widget.*;
import android.view.*;
import android.text.*;
import com.jcraft.jsch.*;
import java.util.*;
import pl.ispinalokalnie.kindlewifi.ActionIcon.Glyph;

public final class FileManager {
    static final int PICK_BACKUP_FOLDER = 104;
    private List<RemoteFiles.Entry> pendingBackup;
    public interface Connection { Session open() throws Exception; }
    interface Task { Object run(RemoteFiles files) throws Exception; }
    interface Done { void accept(Object result); }
    private final Activity activity;
    private final Connection connection;
    private final Runnable onClose;
    private final String[] roots={"/mnt/us/documents","/mnt/us/Books"};
    private final String[] rootLabels={"Przesłane książki","Starsze książki"};
    private String path=roots[0];
    private List<RemoteFiles.Entry> entries=new ArrayList<>();
    private final LinkedHashMap<String, RemoteFiles.Entry> selected = new LinkedHashMap<>();
    private ListView rows;
    private BaseAdapter adapter;
    private final ArrayList<RemoteFiles.Entry> filtered = new ArrayList<>();
    private LinearLayout selectionActions;
    private volatile boolean stopBackup;
    private volatile Thread worker;
    private volatile Session activeSession;
    private Button stopDownload;
    private EditText search;
    private TextView location, info;
    private ProgressBar workingProgress;
    private AlertDialog dialog;
    private boolean working;
    public FileManager(Activity a,Connection c,Runnable close) {activity=a;connection=c;onClose=close;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private Button button(String text, Glyph icon, Runnable click) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(icon == Glyph.DELETE ? 0xff80352d : 0xff175748);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ActionIcon.attach(button, icon, 17, false);
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(icon == Glyph.DELETE ? 0xfff8eae5 : 0xffe9f1eb);
        surface.setCornerRadius(dp(12));
        button.setStateListAnimator(null);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24205e48), surface, null));
        button.setOnClickListener(v -> { if (!working) click.run(); });
        return button;
    }

    private Button navigationButton(String text, Glyph icon, Runnable click) {
        Button button = button(text, icon, click);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(3), dp(6), dp(3), dp(6));
        ActionIcon.attach(button, icon, 17, true);
        return button;
    }

    private void decorateBook(CheckBox book, boolean checked) {
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(checked ? 0xffe2f0e7 : 0xfff6f5ee);
        surface.setCornerRadius(dp(12));
        book.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24175748), surface, null));
    }

    private void addAction(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) params.leftMargin = dp(6);
        row.addView(button, params);
    }

    private String friendlyPath(String target) {
        for (int i = 0; i < roots.length; i++) {
            String root = roots[i];
            if (target.equals(root) || target.startsWith(root + "/")) {
                return rootLabels[i] + target.substring(root.length()).replace("/", " › ");
            }
        }
        return target;
    }

    public void show() {
        LinearLayout page=new LinearLayout(activity);page.setOrientation(1);page.setPadding(dp(16),dp(14),dp(16),dp(12));
        location=new TextView(activity);location.setTextColor(0xff19764b);
        location.setTextSize(17);location.setTypeface(null,android.graphics.Typeface.BOLD);
        location.setPadding(0,dp(6),0,dp(8));page.addView(location);
        LinearLayout nav=new LinearLayout(activity);
        addAction(nav, navigationButton("Foldery", Glyph.FOLDER, () -> new DialogUi.Builder(activity).setTitle("Foldery na Kindle")
                .setItems(rootLabels, (d, w) -> load(roots[w])).show()));
        addAction(nav, navigationButton("Folder wyżej", Glyph.LEVEL_UP, () -> {
            if (!Arrays.asList(roots).contains(path)) load(path.substring(0, path.lastIndexOf('/')));
        }));
        addAction(nav, navigationButton("Odśwież", Glyph.REFRESH, () -> load(path)));
        page.addView(nav);
        search=new EditText(activity);search.setSingleLine(true);search.setHint("Szukaj w tym folderze");ActionIcon.attach(search, Glyph.SEARCH);page.addView(search);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(Editable e){}});
        info=new TextView(activity);info.setTextColor(0xff62756a);info.setTextSize(14);
        info.setPadding(0,dp(6),0,dp(6));page.addView(info);
        info.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        workingProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        workingProgress.setIndeterminate(true);
        workingProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xff175748));
        workingProgress.setVisibility(View.GONE);
        page.addView(workingProgress);
        rows = new ListView(activity);
        rows.setDivider(null);
        adapter = new BaseAdapter() {
            public int getCount() { return filtered.size(); }
            public RemoteFiles.Entry getItem(int index) { return filtered.get(index); }
            public long getItemId(int index) { return index; }
            public View getView(int index, View recycled, ViewGroup parent) { return bookRow(getItem(index)); }
        };
        rows.setAdapter(adapter);
        page.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        selectionActions = new LinearLayout(activity);
        selectionActions.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(activity);
        addAction(actions, button("Zmień nazwę", Glyph.RENAME, this::rename));
        addAction(actions, button("Do kosza", Glyph.DELETE, this::delete));
        selectionActions.addView(actions);
        LinearLayout backup = new LinearLayout(activity);
        addAction(backup, button("Przenieś", Glyph.MOVE, this::move));
        addAction(backup, button("Pobierz", Glyph.DOWNLOAD, this::chooseBackupFolder));
        selectionActions.addView(backup);
        selectionActions.setVisibility(View.GONE);
        page.addView(selectionActions);
        LinearLayout folders = new LinearLayout(activity);
        addAction(folders, button("Nowy folder", Glyph.NEW_FOLDER, this::mkdir));
        addAction(folders, button("Otwórz kosz", Glyph.DELETE, this::showTrash));
        page.addView(folders);
        stopDownload = button("Przerwij pobieranie", Glyph.CLOSE, () -> {});
        stopDownload.setVisibility(View.GONE);
        stopDownload.setOnClickListener(v -> {
            stopBackup = true;
            stopDownload.setEnabled(false);
            info.setText("Zatrzymuję pobieranie…");
            Thread running = worker;
            if (running != null) running.interrupt();
            Session session = activeSession;
            if (session != null) new Thread(session::disconnect, "kindle-backup-stop").start();
        });
        page.addView(stopDownload);
        dialog=new DialogUi.Builder(activity).setTitle("Moje książki na Kindle").setView(page).setNegativeButton("Zamknij",null).create();
        dialog.setOnDismissListener(d->onClose.run());dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        load(path);
    }
    private void run(Task task,Done done) {
        if(working)return; working=true;info.setText("Łączę z Kindle…");dialog.setCancelable(false);dialog.getButton(-2).setEnabled(false);
        if (android.provider.Settings.Global.getFloat(activity.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f)
            workingProgress.setVisibility(View.VISIBLE);
        worker = new Thread(()->{
            Session session=null;ChannelSftp channel=null;Object value=null;Exception error=null;
            try{session=connection.open();activeSession=session;
                if (stopBackup) throw new java.io.InterruptedIOException();
                channel=(ChannelSftp)session.openChannel("sftp");channel.connect(12000);value=task.run(new RemoteFiles(channel,roots));}
            catch(Exception e){error=e;}
            finally{if(channel!=null)channel.disconnect();if(session!=null)session.disconnect();activeSession=null;}
            final Object result=value;final Exception failure=error;
            activity.runOnUiThread(()->{
                working=false;
                worker = null;
                boolean stopped = stopBackup;
                stopBackup = false;
                if (activity.isDestroyed() || activity.isFinishing() || !dialog.isShowing()) return;
                stopDownload.setVisibility(View.GONE);
                workingProgress.setVisibility(View.GONE);
                dialog.setCancelable(true);dialog.getButton(-2).setEnabled(true);
                if (stopped && failure != null) info.setText("Pobieranie przerwane.");
                else if(failure!=null){info.setText("Nie udało się połączyć lub zapisać zmian.");new DialogUi.Builder(activity).setTitle("Nie udało się dokończyć")
                        .setMessage(MainActivity.userMessage(failure))
                        .setDetails(MainActivity.technicalMessage(failure)).setPositiveButton("Zamknij", null).show();}
                else done.accept(result);
            });
        },"kindle-files");
        worker.start();
    }
    private void load(String target){run(f->f.list(target),r->{path=target;entries=(List<RemoteFiles.Entry>)r;selected.clear();search.setText("");render();});}
    private void render() {
        if (rows == null) return;
        location.setText(friendlyPath(path));
        String filter = search.getText().toString().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (RemoteFiles.Entry entry : entries) {
            if (entry.name.toLowerCase(Locale.ROOT).contains(filter)) filtered.add(entry);
        }
        adapter.notifyDataSetChanged();
        updateSelection();
    }

    private void updateSelection() {
        selectionActions.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        if (!working) info.setText("Widoczne pozycje: " + filtered.size() + "  •  zaznaczono: " + selected.size());
    }

    private View bookRow(RemoteFiles.Entry entry) {
        if (entry.directory) return button("Otwórz: " + entry.name, Glyph.FOLDER, () -> load(entry.path));
        CheckBox check = new CheckBox(activity);
        check.setText(entry.name);
        check.setTextSize(16);
        check.setTextColor(0xff172d28);
        check.setPadding(dp(10), dp(8), dp(10), dp(8));
        check.setMinHeight(dp(48));
        check.setChecked(selected.containsKey(entry.path));
        decorateBook(check, check.isChecked());
        check.setOnCheckedChangeListener((button, checked) -> {
            if (checked) selected.put(entry.path, entry);
            else selected.remove(entry.path);
            decorateBook(check, checked);
            updateSelection();
        });
        return check;
    }

    private void message(String title,String text){new DialogUi.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("Zamknij",null).show();}
    private void input(String title, String initial, String hint, String save,
                       java.util.function.Consumer<String> action) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(14), dp(20), dp(12));
        TextView instruction = new TextView(activity);
        instruction.setText(hint);
        instruction.setTextSize(15);
        form.addView(instruction);
        EditText edit = new EditText(activity);
        edit.setSingleLine(true);
        edit.setHint("Wpisz nazwę");
        edit.setText(initial);
        edit.setSelectAllOnFocus(true);
        form.addView(edit);
        new DialogUi.Builder(activity).setTitle(title).setView(form).setNegativeButton("Anuluj", null)
                .setPositiveButton(save, (d, w) -> action.accept(edit.getText().toString())).show();
    }
    private void rename(){
        if(selected.size()!=1){message("Zmiana nazwy","Zaznacz jedną książkę.");return;}
        RemoteFiles.Entry file = selected.values().iterator().next();String name=file.name;int dot=name.lastIndexOf('.');String ext=name.substring(dot);
        input("Zmień nazwę pliku",name.substring(0,dot),"Końcówka "+ext+" zostanie zachowana. Zamknij książkę w KOReaderze przed zmianą. Zmiana może wpłynąć na odnajdywanie jej zakładek i postępu czytania.", "Zmień nazwę",
            text->run(f->{f.rename(file,text+ext);return null;},r->load(path)));
    }
    private void mkdir(){input("Nowy folder","","Folder powstanie w "+friendlyPath(path),"Utwórz folder",name->run(f->{f.mkdir(path,name);return null;},r->load(path)));}
    private void delete(){
        if(selected.isEmpty()){message("Usuwanie","Zaznacz książki do usunięcia.");return;}
        ArrayList<RemoteFiles.Entry> files=new ArrayList<>(selected.values());StringBuilder names=new StringBuilder();
        for(RemoteFiles.Entry file:files)names.append(file.name).append('\n');
        new DialogUi.Builder(activity).setTitle("Przenieść książki do kosza: "+files.size()+"?")
            .setMessage(names+"\nMożesz je później przywrócić z kosza na Kindle. Zamknij te książki w KOReaderze.")
            .setNegativeButton("Anuluj",null).setPositiveButton("Przenieś do kosza",(d,w)->batch(files,null)).show();
    }
    private void chooseBackupFolder() {
        if (selected.isEmpty()) {
            message("Pobieranie na telefon", "Zaznacz książki, które chcesz pobrać.");
            return;
        }
        pendingBackup = new ArrayList<>(selected.values());
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { activity.startActivityForResult(intent, PICK_BACKUP_FOLDER); }
        catch (android.content.ActivityNotFoundException e) {
            pendingBackup = null;
            message("Pobieranie na telefon", "Telefon nie udostępnia wyboru folderu.");
        }
    }

    void backupTo(Uri tree) {
        List<RemoteFiles.Entry> items = pendingBackup;
        pendingBackup = null;
        if (tree == null || items == null || dialog == null || !dialog.isShowing()) return;
        stopDownload.setEnabled(true);
        stopDownload.setVisibility(View.VISIBLE);
        run(files -> {
            PhoneBackup target = new PhoneBackup(activity.getContentResolver(), tree);
            StringBuilder report = new StringBuilder();
            for (int i = 0; i < items.size() && !stopBackup; i++) {
                RemoteFiles.Entry item = items.get(i);
                int number = i + 1;
                activity.runOnUiThread(() -> info.setText("Pobieram " + number + " z " + items.size() + " · " + item.name));
                File copy = null;
                try {
                    copy = File.createTempFile("kindle-backup-", ".part", activity.getCacheDir());
                    files.download(item, copy);
                    if (stopBackup) break;
                    report.append("✓ ").append(target.save(copy, item.name));
                } catch (Exception error) {
                    if (stopBackup) break;
                    report.append("✗ ").append(item.name).append(" — ").append(MainActivity.userMessage(error));
                } finally {
                    if (copy != null) copy.delete();
                }
                report.append('\n');
            }
            if (stopBackup) report.append("Pobieranie przerwane.");
            return report.toString();
        }, result -> {
            info.setText("Wynik pobierania");
            message("Pobieranie na telefon", (String) result
                    + "\nKopia zawiera pliki książek. Postęp, zakładki i zaznaczenia pozostają w KOReaderze.");
        });
    }

    @SuppressWarnings("unchecked")
    private void showTrash() {
        run(RemoteFiles::trashContents, result -> {
            List<RemoteFiles.TrashEntry> entries = (List<RemoteFiles.TrashEntry>) result;
            if (entries.isEmpty()) {
                message("Kosz na Kindle", "Kosz jest pusty.");
                return;
            }
            String[] names = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                RemoteFiles.TrashEntry entry = entries.get(i);
                names[i] = entry.name() + " · " + friendlyPath(entry.original.substring(0, entry.original.lastIndexOf('/')));
            }
            new DialogUi.Builder(activity).setTitle("Kosz na Kindle")
                    .setItems(names, (d, which) -> restore(entries.get(which)))
                    .setNegativeButton("Zamknij", null)
                    .setNeutralButton("Opróżnij kosz", (d, which) -> emptyTrash(entries)).show();
        });
    }

    private void restore(RemoteFiles.TrashEntry entry) {
        new DialogUi.Builder(activity).setTitle("Przywrócić książkę?")
                .setMessage(entry.name())
                .setNeutralButton("Zmień miejsce", (d, w) -> chooseRestoreFolder(entry))
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Przywróć", (d, which) -> run(files -> {
                    files.restore(entry);
                    return null;
                }, result -> {
                    message("Przywrócono książkę", entry.name());
                    load(path);
                })).show();
    }

    private void chooseRestoreFolder(RemoteFiles.TrashEntry entry) {
        new DialogUi.Builder(activity).setTitle("Wybierz folder do przywrócenia")
                .setItems(rootLabels, (d, index) -> pickRestoreFolder(entry, roots[index]))
                .setNegativeButton("Anuluj", null).show();
    }

    private void pickRestoreFolder(RemoteFiles.TrashEntry entry, String target) {
        run(files -> files.list(target), result -> {
            List<String> paths = new ArrayList<>();
            List<String> names = new ArrayList<>();
            paths.add(target);
            names.add("Przywróć tutaj");
            if (!Arrays.asList(roots).contains(target)) {
                paths.add(target.substring(0, target.lastIndexOf('/')));
                names.add("Folder wyżej");
            }
            for (RemoteFiles.Entry item : (List<RemoteFiles.Entry>) result) {
                if (item.directory) { paths.add(item.path); names.add(item.name); }
            }
            new DialogUi.Builder(activity).setTitle(friendlyPath(target))
                    .setItems(names.toArray(new String[0]), (d, index) -> {
                        if (index != 0) { pickRestoreFolder(entry, paths.get(index)); return; }
                        String original = entry.name();
                        int dot = original.lastIndexOf('.');
                        input("Podaj nazwę książki", original.substring(0, dot),
                                "Format " + original.substring(dot) + " zostanie zachowany.", "Przywróć książkę",
                                name -> run(files -> {
                                    files.restore(entry, target, name + original.substring(dot));
                                    return null;
                                }, ignored -> {
                                    message("Przywrócono książkę", name + original.substring(dot));
                                    load(target);
                                }));
                    }).setNegativeButton("Anuluj", null).show();
        });
    }

    private void emptyTrash(List<RemoteFiles.TrashEntry> entries) {
        new DialogUi.Builder(activity).setTitle("Opróżnić kosz?")
                .setMessage("Usuniesz bezpowrotnie pliki z kosza: " + entries.size() + ".")
                .setNegativeButton("Anuluj", null)
                .destructive().setPositiveButton("Usuń trwale", (d, which) -> run(files -> {
                    int removed = 0;
                    for (RemoteFiles.TrashEntry entry : entries) {
                        try {
                            files.purge(entry);
                            removed++;
                        } catch (Exception ignored) {}
                    }
                    return removed;
                }, result -> message("Kosz na Kindle", "Usunięto " + result + " z " + entries.size() + " plików."))).show();
    }

    private void move(){
        if(selected.isEmpty()){message("Przenoszenie","Zaznacz książki do przeniesienia.");return;}
        ArrayList<RemoteFiles.Entry> files=new ArrayList<>(selected.values());
        new DialogUi.Builder(activity).setTitle("Wybierz folder docelowy").setItems(rootLabels,(d,w)->pickDestination(roots[w],files)).show();
    }
    private void pickDestination(String target,ArrayList<RemoteFiles.Entry> files){
        run(f->f.list(target),r->{
            ArrayList<String> names=new ArrayList<>();ArrayList<String> paths=new ArrayList<>();
            names.add("✓ Przenieś tutaj ("+files.size()+")");paths.add(target);
            if(!Arrays.asList(roots).contains(target)){names.add("↑ Folder wyżej");paths.add(target.substring(0,target.lastIndexOf('/')));}
            for(RemoteFiles.Entry e:(List<RemoteFiles.Entry>)r)if(e.directory){names.add("📁 "+e.name);paths.add(e.path);}
            new DialogUi.Builder(activity).setTitle(friendlyPath(target))
                .setItems(names.toArray(new String[0]),(d,w)->{if(w==0)new DialogUi.Builder(activity).setTitle("Przenieść książki?")
                    .setMessage("Do: "+friendlyPath(target)+"\nZamknij te książki w KOReaderze. Ich zakładki i postęp mogą pozostać powiązane ze starym położeniem.")
                    .setNegativeButton("Anuluj",null).setPositiveButton("Przenieś",(q,x)->batch(files,target)).show();else pickDestination(paths.get(w),files);})
                .setNegativeButton("Anuluj",null).show();
        });
    }
    private void batch(ArrayList<RemoteFiles.Entry> files,String destination){
        run(f->{StringBuilder report=new StringBuilder();for(RemoteFiles.Entry file:files){try{if(destination==null)f.trash(file);else f.move(file,destination);report.append("✓ ");}
            catch(Exception e){report.append("✗ ").append(e.getMessage()).append(" — ");}report.append(file.name).append('\n');}return report.toString();},
            r->{String report=(String)r;
                boolean allOk=!report.contains("✗");
                message(allOk ? "✓ Gotowe!" : "Nie wszystkie zmiany się udały",report);
                load(path);});
    }
}
