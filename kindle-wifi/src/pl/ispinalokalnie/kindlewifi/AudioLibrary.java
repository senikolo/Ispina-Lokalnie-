package pl.ispinalokalnie.kindlewifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import android.text.*;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import java.io.*;
import java.util.*;

/** Phone-side audio library. All file operations are executed over paired SFTP. */
final class AudioLibrary {
    static final int PICK_AUDIO = 106;
    private static final long MAX_AUDIO = 512L*1024*1024;
    interface Connection { Session open() throws Exception; }
    interface Task { Object perform(AudioFiles files) throws Exception; }
    interface Done { void accept(Object result); }
    private final Activity activity;
    private final Connection connection;
    private final Runnable onClose;
    private String path = AudioFiles.MUSIC;
    private final ArrayList<AudioFiles.Entry> entries = new ArrayList<>();
    private final ArrayList<AudioFiles.Entry> shown = new ArrayList<>();
    private AlertDialog dialog;
    private TextView title, status;
    private EditText filter;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private ProgressBar progress;
    private Button add, newFolder, close, up, cancel, remove;
    private volatile Thread worker;
    private volatile boolean cancelled;
    private boolean working;

    AudioLibrary(Activity activity, String startFolder, Connection conn, Runnable close) {
        this.activity = activity;
        this.path = startFolder;
        this.connection = conn;
        this.onClose = close;
    }
    private int dp(int px) { return Math.round(px*activity.getResources().getDisplayMetrics().density); }
    private Button button(String label) {
        Button b = new Button(activity); b.setAllCaps(false); b.setText(label);
        b.setTextSize(14); b.setMinHeight(dp(48));
        return b;
    }
    private void info(String head, String message) {
        new DialogUi.Builder(activity).setTitle(head).setMessage(message)
                .setPositiveButton("Zamknij",null).show();
    }
    void show() {
        LinearLayout page=new LinearLayout(activity);
        page.setOrientation(1);page.setPadding(dp(14),dp(12),dp(14),dp(8));
        title=new TextView(activity);title.setTextSize(18);title.setTextColor(0xff175748);
        title.setPadding(dp(8),dp(7),dp(8),dp(7));page.addView(title);
        TextView hint=new TextView(activity);
        hint.setText("Do słuchania na Kindle wybierz MP3. Inne formaty możesz przesłać, a w razie potrzeby wcześniej przekonwertować na MP3 w telefonie.");
        hint.setTextSize(13);hint.setPadding(dp(8),0,dp(8),dp(8));page.addView(hint);
        LinearLayout tabs=new LinearLayout(activity);tabs.setOrientation(0);
        Button music=button("♫ Muzyka");Button books=button("◷ Audiobooki");
        tabs.addView(music,new LinearLayout.LayoutParams(0,dp(52),1));
        tabs.addView(books,new LinearLayout.LayoutParams(0,dp(52),1));page.addView(tabs);
        music.setOnClickListener(v->load(AudioFiles.MUSIC));
        books.setOnClickListener(v->load(AudioFiles.BOOKS));
        filter=new EditText(activity);filter.setSingleLine(true);filter.setHint("Wyszukaj plik lub folder");
        page.addView(filter);
        filter.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int after) {}
            public void onTextChanged(CharSequence s,int st,int before,int count){render();}
            public void afterTextChanged(Editable e){}
        });
        list=new ListView(activity);list.setDividerHeight(dp(1));
        adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());
        list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id)->{
            if(working || position<0 || position>=shown.size()) return;
            AudioFiles.Entry item=shown.get(position);
            if(item.dir) load(item.path);else actions(item);
        });
        LinearLayout toolbar=new LinearLayout(activity);toolbar.setOrientation(0);
        up=button("← W górę");newFolder=button("Nowy folder");
        toolbar.addView(up,new LinearLayout.LayoutParams(0,dp(50),1));
        toolbar.addView(newFolder,new LinearLayout.LayoutParams(0,dp(50),1));
        page.addView(toolbar);
        up.setOnClickListener(v->{
            if(path.equals(AudioFiles.MUSIC)||path.equals(AudioFiles.BOOKS)) return;
            load(path.substring(0,path.lastIndexOf('/')));
        });
        newFolder.setOnClickListener(v->promptFolder());
        add=button("+ Dodaj audio z telefonu przez Wi-Fi");
        add.setTextColor(0xff175748);
        page.addView(add,new LinearLayout.LayoutParams(-1,dp(54)));
        add.setOnClickListener(v->pick());
        remove = button("Zaznacz pliki do usunięcia");
        remove.setOnClickListener(v -> selectFiles());
        page.addView(remove);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        page.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        status = new TextView(activity);
        status.setTextSize(13);
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        page.addView(status);
        cancel=button("Przerwij przesyłanie");cancel.setVisibility(View.GONE);
        page.addView(cancel);
        cancel.setOnClickListener(v->{cancelled=true;if(worker!=null)worker.interrupt();
            cancel.setEnabled(false);status.setText("Zatrzymuję przesyłanie…");});
        dialog=new DialogUi.Builder(activity).setTitle("Biblioteka audio Kindle’a")
                .setView(page).setNegativeButton("Zamknij",null).create();
        dialog.setOnDismissListener(v->{if(worker!=null)worker.interrupt();onClose.run();});
        dialog.show();dialog.getWindow().setLayout(-1,-1);
        close=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        load(path);
    }
    private String currentTitle(){
        String base=path.startsWith(AudioFiles.MUSIC)?"Muzyka":"Audiobooki";
        String root=path.startsWith(AudioFiles.MUSIC)?AudioFiles.MUSIC:AudioFiles.BOOKS;
        return base+path.substring(root.length()).replace("/","  ›  ");
    }
    private void render(){
        if(adapter==null)return;
        title.setText(currentTitle());
        String text=filter.getText().toString().toLowerCase(Locale.ROOT);
        shown.clear();adapter.clear();
        for(AudioFiles.Entry item:entries) if(item.name.toLowerCase(Locale.ROOT).contains(text)) {
            shown.add(item);
            adapter.add(item.dir?"📁  "+item.name:"♫  "+item.name+"  ·  "+readable(item.bytes));
        }
        adapter.notifyDataSetChanged();
        up.setEnabled(!working && !isRoot(path));
        remove.setEnabled(!working && shown.stream().anyMatch(item -> !item.dir));
        if (!working) status.setText("Połączono z Kindle’em  ·  Pozycje: " + shown.size());
    }
    private static boolean isRoot(String folder) {
        return folder.equals(AudioFiles.MUSIC) || folder.equals(AudioFiles.BOOKS);
    }
    private static String readable(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1024*1024)return (bytes/1024)+" KB";
        return String.format(Locale.ROOT,"%.1f MB",bytes/(1024d*1024d));
    }
    private void work(String initial,Task task,Done done) {
        work(initial, false, task, done);
    }
    private void work(String initial, boolean canCancel, Task task, Done done) {
        if (working) return;
        working = true;
        cancelled = false;
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
        status.setText(initial);
        add.setEnabled(false);
        newFolder.setEnabled(false);
        remove.setEnabled(false);
        up.setEnabled(false);
        close.setEnabled(false);
        dialog.setCancelable(false);
        cancel.setEnabled(true);
        cancel.setVisibility(canCancel ? View.VISIBLE : View.GONE);
        worker=new Thread(()->{
            Object result=null;Exception error=null;Session session=null;ChannelSftp channel=null;
            try{
                session=connection.open();
                channel=(ChannelSftp)session.openChannel("sftp");channel.connect(12000);
                AudioFiles files=new AudioFiles(channel);files.ensureRoots();
                result=task.perform(files);
            }catch(Exception ex){error=ex;}
            finally{if(channel!=null)channel.disconnect();if(session!=null)session.disconnect();}
            final Object response=result;final Exception problem=error;
            activity.runOnUiThread(()->{
                worker=null;working=false;
                if(dialog==null || !dialog.isShowing()||activity.isFinishing()||activity.isDestroyed())return;
                progress.setVisibility(View.INVISIBLE);
                cancel.setVisibility(View.GONE);
                add.setEnabled(true);
                newFolder.setEnabled(true);
                remove.setEnabled(shown.stream().anyMatch(item -> !item.dir));
                up.setEnabled(!isRoot(path));
                close.setEnabled(true);
                dialog.setCancelable(true);
                if(problem!=null) {
                    status.setText(cancelled?"Przesyłanie przerwane.":"Nie udało się dokończyć operacji.");
                    if(!cancelled) new DialogUi.Builder(activity).setTitle("Nie udało się")
                            .setMessage(MainActivity.userMessage(problem))
                            .setDetails(MainActivity.technicalMessage(problem))
                            .setPositiveButton("Zamknij",null).show();
                }else done.accept(response);
                cancelled=false;
            });
        },"kindle-audio");worker.start();
    }
    @SuppressWarnings("unchecked")
    private void load(String directory){
        work("Odczytuję bibliotekę na Kindle…",f->f.list(directory),result->{
            path=directory;entries.clear();entries.addAll((List<AudioFiles.Entry>)result);
            filter.setText("");render();
        });
    }
    private void edit(String header,String value,String hint,java.util.function.Consumer<String> onOK){
        EditText input=new EditText(activity);input.setSingleLine(true);input.setText(value);
        input.setSelection(input.length());input.setPadding(dp(24),dp(16),dp(24),dp(16));
        new DialogUi.Builder(activity).setTitle(header).setMessage(hint).setView(input)
                .setNegativeButton("Anuluj",null)
                .setPositiveButton("Zapisz",(d,which)->onOK.accept(input.getText().toString())).show();
    }
    private void promptFolder(){
        edit("Nowy folder","","Pliki można porządkować w folderach na Kindle.",name->
                work("Tworzę folder…",f->{f.mkdir(path,name);return null;},r->load(path)));
    }
    private void actions(AudioFiles.Entry file) {
        String[] options = {"Zmień nazwę", "Przenieś do folderu", "Usuń plik"};
        int dot = file.name.lastIndexOf('.');
        String ext = file.name.substring(dot);
        new DialogUi.Builder(activity).setTitle(file.name + "\n" + ext.substring(1).toUpperCase(Locale.ROOT) + "  ·  " + readable(file.bytes))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        edit("Zmień nazwę audio", file.name.substring(0, dot),
                                "Rozszerzenie " + ext + " zostanie zachowane.", name ->
                                        work("Zmieniam nazwę…", f -> {
                                            f.rename(file.path, name + ext);
                                            return null;
                                        }, result -> load(path)));
                    } else if (which == 1) {
                        new DialogUi.Builder(activity).setTitle("Wybierz kolekcję docelową")
                                .setItems(new String[]{"Muzyka", "Audiobooki"}, (d, selected) ->
                                        chooseDestination(file, selected == 0 ? AudioFiles.MUSIC : AudioFiles.BOOKS))
                                .setNegativeButton("Anuluj", null).show();
                    } else {
                        confirmDelete(Collections.singletonList(file));
                    }
                }).setNegativeButton("Zamknij", null).show();
    }

    @SuppressWarnings("unchecked")
    private void chooseDestination(AudioFiles.Entry file, String folder) {
        work("Odczytuję foldery na Kindle…", f -> f.list(folder), result -> {
            List<String> labels = new ArrayList<>();
            List<String> targets = new ArrayList<>();
            labels.add("Przenieś tutaj");
            targets.add(folder);
            if (!isRoot(folder)) {
                labels.add("↑ Folder nadrzędny");
                targets.add(folder.substring(0, folder.lastIndexOf('/')));
            }
            for (AudioFiles.Entry entry : (List<AudioFiles.Entry>) result) {
                if (entry.dir) {
                    labels.add(entry.name + "  ›");
                    targets.add(entry.path);
                }
            }
            String label = folder.startsWith(AudioFiles.MUSIC)
                    ? "Muzyka" + folder.substring(AudioFiles.MUSIC.length())
                    : "Audiobooki" + folder.substring(AudioFiles.BOOKS.length());
            new DialogUi.Builder(activity).setTitle(label.replace("/", "  ›  "))
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        if (which != 0) {
                            chooseDestination(file, targets.get(which));
                            return;
                        }
                        work("Przenoszę plik…", f -> {
                            f.move(file.path, folder);
                            return null;
                        }, response -> {
                            info("Zapisano", "Wybrany plik znajduje się w folderze „" + label + "”.");
                            load(path);
                        });
                    }).setNegativeButton("Anuluj", null).show();
        });
    }

    private void selectFiles() {
        List<AudioFiles.Entry> files = new ArrayList<>();
        for (AudioFiles.Entry entry : shown) if (!entry.dir) files.add(entry);
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = files.get(i).name;
        boolean[] selected = new boolean[files.size()];
        new DialogUi.Builder(activity).setTitle("Wybierz pliki audio")
                .setMultiChoiceItems(names, selected, (d, which, checked) -> selected[which] = checked)
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Dalej", (d, which) -> {
                    List<AudioFiles.Entry> chosen = new ArrayList<>();
                    for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(files.get(i));
                    if (chosen.isEmpty()) info("Wybierz plik", "Zaznacz co najmniej jeden plik audio.");
                    else confirmDelete(chosen);
                }).show();
    }

    private void confirmDelete(List<AudioFiles.Entry> files) {
        String detail = files.size() == 1 ? files.get(0).name : "Wybrane pliki audio (" + files.size() + ")";
        new DialogUi.Builder(activity).setTitle("Usunąć z Kindle’a?")
                .setMessage(detail + "\n\nPliki zostaną usunięte trwale z biblioteki audio.")
                .setNegativeButton("Anuluj", null).destructive()
                .setPositiveButton("Usuń trwale", (d, which) -> work("Usuwam wybrane pliki…", f -> {
                    int count = 0;
                    StringBuilder failures = new StringBuilder();
                    for (AudioFiles.Entry file : files) {
                        try {
                            f.delete(file);
                            count++;
                        } catch (Exception failure) {
                            failures.append("\n").append(file.name).append(" – ").append(MainActivity.userMessage(failure));
                            if (!f.connected()) break;
                        }
                    }
                    return "Potwierdzono usunięcie " + count + " z " + files.size() + " plików." + failures;
                }, result -> {
                    info("Usuwanie plików", (String) result);
                    load(path);
                })).show();
    }
    private void pick(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try{activity.startActivityForResult(intent,PICK_AUDIO);}
        catch(Exception error){info("Wybór plików","Nie można otworzyć plików telefonu: "+MainActivity.userMessage(error));}
    }
    void receive(Intent data){
        if(data==null||dialog==null||!dialog.isShowing()||working)return;
        LinkedHashSet<Uri> uris=new LinkedHashSet<>();
        if(data.getData()!=null)uris.add(data.getData());
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)
            uris.add(data.getClipData().getItemAt(i).getUri());
        if(uris.isEmpty())return;
        String folder=path;
        work("Przygotowuję pliki audio…", true, files -> {
            StringBuilder report=new StringBuilder();int success=0;
            for(Uri uri:uris){
                if(Thread.currentThread().isInterrupted()||cancelled)break;
                String name = "Wybrany plik";
                File temp = null;
                try {
                    name = filename(uri);
                    AudioFiles.validName(name);
                    if(!AudioFiles.audio(name))throw new IOException("Nieobsługiwane rozszerzenie audio.");
                    final String displayName = name;
                    updateTransfer("Przygotowuję „" + displayName + "”…", -1);
                    temp = File.createTempFile("kindle-audio-", ".part", activity.getCacheDir());
                    copyBounded(uri, temp);
                    String saved = upload(files, temp, displayName, folder);
                    success++;
                    report.append("✓ ").append(saved.substring(saved.lastIndexOf('/') + 1)).append("\n");
                }catch(Exception e){
                    report.append("✗ ").append(name).append(": ").append(MainActivity.userMessage(e)).append("\n");
                }finally{if(temp!=null)temp.delete();}
            }
            if(cancelled||Thread.currentThread().isInterrupted())report.append("Przerwano dodawanie.\n");
            return success+" plików wysłanych na Kindle.\n\n"+report;
        },result->{
            info("Wysyłanie audio",(String)result);
            load(folder);
        });
    }
    private String upload(AudioFiles files, File source, String name, String folder) throws Exception {
        AudioFiles.Upload attempt = new AudioFiles.Upload();
        VerifiedTransfer.Progress phase = message -> updateTransfer(message + "  " + name, -1);
        VerifiedTransfer.Percent percent = value -> updateTransfer(null, value);
        try {
            return files.upload(source, name, folder, phase, percent, attempt, false);
        } catch (Exception failure) {
            if (cancelled || Thread.currentThread().isInterrupted() || files.connected()) throw failure;
        }
        updateTransfer("Odzyskuję połączenie i sprawdzam zapis…", -1);
        Thread.sleep(1200);
        if (cancelled) throw new InterruptedIOException("Przerwano przesyłanie.");
        Session retry = connection.open();
        ChannelSftp channel = null;
        try {
            channel = CompanionFiles.channel(retry);
            return new AudioFiles(channel).upload(source, name, folder, phase, percent, attempt, true);
        } finally {
            if (channel != null) channel.disconnect();
            retry.disconnect();
        }
    }

    private void updateTransfer(String message, int value) {
        activity.runOnUiThread(() -> {
            if (cancelled || !working || !dialog.isShowing()) return;
            if (message != null) status.setText(message);
            progress.setIndeterminate(value < 0);
            if (value >= 0) progress.setProgress(value, true);
        });
    }

    private String filename(Uri uri) throws IOException {
        String name=null;
        try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);
        }catch(SecurityException e){throw new IOException("Brak dostępu do wybranego pliku.",e);}
        if(name==null||name.isEmpty()){
            String path=uri.getLastPathSegment();
            name=path==null?"":path.substring(path.lastIndexOf('/')+1);
        }
        return name;
    }
    private void copyBounded(Uri uri,File destination) throws Exception {
        try(InputStream in=activity.getContentResolver().openInputStream(uri);
            FileOutputStream out=new FileOutputStream(destination)){
            if(in==null)throw new IOException("Nie można odczytać pliku z telefonu.");
            byte[] buffer=new byte[32768];long count=0;int n;
            while((n=in.read(buffer))!=-1){
                if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Przerwano kopiowanie.");
                count+=n;
                if(count>MAX_AUDIO)throw new IOException("Plik jest większy niż 512 MB. Podziel audiobook na części.");
                out.write(buffer,0,n);
            }
            out.getFD().sync();
            if(count==0)throw new IOException("Pusty plik.");
        }
    }
}
