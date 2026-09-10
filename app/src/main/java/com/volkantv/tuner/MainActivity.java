package com.volkantv.tuner;

import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final int PERMISSION_REQUEST = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<ChannelItem> channels = new ArrayList<>();

    private FrameLayout root;
    private TvView tvView;
    private LinearLayout channelPanel;
    private ListView channelList;
    private LinearLayout infoBar;
    private TextView channelTitle;
    private TextView programTitle;
    private TextView programTime;
    private TextView numberBox;
    private TextView statusBox;
    private TextView panelSummary;

    private ChannelAdapter adapter;
    private int currentIndex = -1;
    private int highlightedIndex = 0;
    private String numberBuffer = "";

    private static class ChannelItem {
        long id;
        String number;
        String name;
        String inputId;
        String videoFormat;

        ChannelItem(long id, String number, String name, String inputId, String videoFormat) {
            this.id = id;
            this.number = number == null ? "" : number;
            this.name = TextUtils.isEmpty(name) ? "İsimsiz Kanal" : name;
            this.inputId = inputId == null ? "" : inputId;
            this.videoFormat = videoFormat == null ? "" : videoFormat;
        }
    }

    private final Runnable numberCommit = () -> {
        if (!numberBuffer.isEmpty()) tuneByNumber(numberBuffer);
        numberBuffer = "";
        numberBox.setVisibility(View.GONE);
    };

    private final Runnable hideInfo = () -> infoBar.setVisibility(View.GONE);

    private final Runnable refreshProgram = new Runnable() {
        @Override public void run() {
            if (currentIndex >= 0 && currentIndex < channels.size()) {
                updateProgram(channels.get(currentIndex));
            }
            handler.postDelayed(this, 60000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        buildInterface();
        setupTvCallbacks();

        showStatus("VOLKAN TV\nSimfer TV altyapısı kontrol ediliyor…", 3500);

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{READ_TV_LISTINGS}, PERMISSION_REQUEST);
        } else {
            loadTvSystem();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        tvView = new TvView(this);
        root.addView(tvView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        createChannelPanel();
        createInfoBar();
        createNumberBox();
        createStatusBox();

        setContentView(root);
        root.requestFocus();
    }

    private void createChannelPanel() {
        channelPanel = new LinearLayout(this);
        channelPanel.setOrientation(LinearLayout.VERTICAL);
        channelPanel.setPadding(dp(28), dp(26), dp(22), dp(26));

        GradientDrawable panelBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(248, 8, 10, 16), Color.argb(232, 20, 25, 34)});
        panelBg.setCornerRadii(new float[]{0,0,dp(24),dp(24),dp(24),dp(24),0,0});
        channelPanel.setBackground(panelBg);

        TextView brand = new TextView(this);
        brand.setText("VOLKAN TV");
        brand.setTextColor(Color.WHITE);
        brand.setTextSize(28);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setPadding(dp(12), dp(2), dp(12), dp(2));

        TextView subtitle = new TextView(this);
        subtitle.setText("UYDU KANALLARI");
        subtitle.setTextColor(Color.rgb(245, 158, 11));
        subtitle.setTextSize(13);
        subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        subtitle.setPadding(dp(12), dp(2), dp(12), dp(4));

        panelSummary = new TextView(this);
        panelSummary.setText("Kanal listesi hazırlanıyor…");
        panelSummary.setTextColor(Color.rgb(175, 182, 194));
        panelSummary.setTextSize(13);
        panelSummary.setPadding(dp(12), dp(2), dp(12), dp(16));

        channelPanel.addView(brand);
        channelPanel.addView(subtitle);
        channelPanel.addView(panelSummary);

        channelList = new ListView(this);
        channelList.setDivider(null);
        channelList.setDividerHeight(0);
        channelList.setSelector(android.R.color.transparent);
        channelList.setFocusable(true);
        channelList.setFocusableInTouchMode(true);
        adapter = new ChannelAdapter();
        channelList.setAdapter(adapter);
        channelList.setOnItemClickListener((parent, view, position, id) -> {
            highlightedIndex = position;
            tuneChannel(position);
            hideChannelPanel();
        });

        channelPanel.addView(channelList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(540), ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.START;
        root.addView(channelPanel, params);
        channelPanel.setVisibility(View.GONE);
    }

    private void createInfoBar() {
        infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.VERTICAL);
        infoBar.setPadding(dp(30), dp(18), dp(30), dp(18));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(215, 24, 28, 38), Color.argb(246, 6, 8, 12)});
        bg.setCornerRadius(dp(20));
        infoBar.setBackground(bg);

        channelTitle = new TextView(this);
        channelTitle.setTextColor(Color.WHITE);
        channelTitle.setTextSize(27);
        channelTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        programTitle = new TextView(this);
        programTitle.setTextColor(Color.rgb(229, 232, 238));
        programTitle.setTextSize(19);
        programTitle.setPadding(0, dp(5), 0, 0);

        programTime = new TextView(this);
        programTime.setTextColor(Color.rgb(245, 158, 11));
        programTime.setTextSize(15);
        programTime.setPadding(0, dp(5), 0, 0);

        infoBar.addView(channelTitle);
        infoBar.addView(programTitle);
        infoBar.addView(programTime);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM;
        params.setMargins(dp(32), dp(24), dp(32), dp(28));
        root.addView(infoBar, params);
        infoBar.setVisibility(View.GONE);
    }

    private void createNumberBox() {
        numberBox = new TextView(this);
        numberBox.setTextColor(Color.WHITE);
        numberBox.setTextSize(48);
        numberBox.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        numberBox.setGravity(Gravity.CENTER);
        numberBox.setPadding(dp(28), dp(14), dp(28), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(238, 13, 16, 23));
        bg.setStroke(dp(2), Color.rgb(245, 158, 11));
        bg.setCornerRadius(dp(18));
        numberBox.setBackground(bg);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(220), dp(105));
        params.gravity = Gravity.TOP | Gravity.END;
        params.setMargins(0, dp(42), dp(42), 0);
        root.addView(numberBox, params);
        numberBox.setVisibility(View.GONE);
    }

    private void createStatusBox() {
        statusBox = new TextView(this);
        statusBox.setTextColor(Color.WHITE);
        statusBox.setTextSize(15);
        statusBox.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusBox.setPadding(dp(20), dp(14), dp(20), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(232, 11, 14, 21));
        bg.setCornerRadius(dp(14));
        statusBox.setBackground(bg);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(630), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setMargins(0, dp(40), dp(40), 0);
        root.addView(statusBox, params);
        statusBox.setVisibility(View.GONE);
    }

    private void setupTvCallbacks() {
        tvView.setCallback(new TvView.TvInputCallback() {
            @Override public void onVideoAvailable(String inputId) {
                showStatus("✓ Uydu görüntüsü alındı\n" + shortInputId(inputId), 2500);
            }

            @Override public void onVideoUnavailable(String inputId, int reason) {
                showStatus("Yayın bekleniyor…\nKod: " + reason + "\n" + shortInputId(inputId), 4500);
            }

            @Override public void onConnectionFailed(String inputId) {
                showStatus("TV girişine bağlantı başarısız.\n" + shortInputId(inputId), 8000);
            }

            @Override public void onDisconnected(String inputId) {
                showStatus("TV girişi bağlantısı kesildi.", 5000);
            }

            @Override public void onVideoSizeChanged(String inputId, int width, int height) {
                showStatus("Görüntü: " + width + " × " + height +
                        "\nKaynak görüntü yeniden kodlanmadan gösteriliyor.", 2500);
            }
        });
    }

    private void loadTvSystem() {
        channels.clear();
        StringBuilder diagnostic = new StringBuilder();
        TvInputManager inputManager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);

        try {
            List<TvInputInfo> inputs = inputManager.getTvInputList();
            diagnostic.append("TV girişleri: ").append(inputs.size()).append("\n");
            for (TvInputInfo info : inputs) {
                CharSequence label;
                try { label = info.loadLabel(this); }
                catch (Exception e) { label = "TV Input"; }
                diagnostic.append("• ").append(label)
                        .append(" | tür=").append(info.getType()).append("\n");
            }
        } catch (Exception e) {
            diagnostic.append("TV Input Manager hatası: ")
                    .append(e.getClass().getSimpleName()).append("\n");
        }

        try {
            String[] projection = {
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_INPUT_ID,
                    TvContract.Channels.COLUMN_VIDEO_FORMAT
            };

            Cursor cursor = getContentResolver().query(
                    TvContract.Channels.CONTENT_URI, projection, null, null, null);

            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(TvContract.Channels._ID);
                int numCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NUMBER);
                int nameCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NAME);
                int inputCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INPUT_ID);
                int formatCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_VIDEO_FORMAT);

                while (cursor.moveToNext()) {
                    channels.add(new ChannelItem(
                            cursor.getLong(idCol),
                            cursor.getString(numCol),
                            cursor.getString(nameCol),
                            cursor.getString(inputCol),
                            cursor.getString(formatCol)));
                }
                cursor.close();
            }
        } catch (SecurityException e) {
            showStatus("Kanal listesi izni engellendi.\nREAD_TV_LISTINGS erişimi yok.", 10000);
        } catch (Exception e) {
            showStatus("Kanal veritabanı okunamadı:\n" +
                    e.getClass().getSimpleName() + "\n" + e.getMessage(), 10000);
        }

        Collections.sort(channels, new Comparator<ChannelItem>() {
            @Override public int compare(ChannelItem a, ChannelItem b) {
                int na = numberKey(a.number);
                int nb = numberKey(b.number);
                if (na != nb) return Integer.compare(na, nb);
                return a.number.compareToIgnoreCase(b.number);
            }
        });

        adapter.notifyDataSetChanged();
        panelSummary.setText(channels.size() + " kanal bulundu • OK ile seç");
        diagnostic.append("\nBulunan kanal: ").append(channels.size());

        if (!channels.isEmpty()) {
            showStatus("✓ Simfer TV sistemi erişilebilir\nKanal sayısı: " +
                    channels.size() + "\nOK = Kanal listesi", 6000);
            highlightedIndex = 0;
            tuneChannel(0);
        } else {
            showStatus(diagnostic +
                    "\n\nKanal 0 ise tuner/kanal veritabanı üretici tarafından sistem uygulamasına kilitli olabilir.", 15000);
            showChannelPanel();
        }

        handler.removeCallbacks(refreshProgram);
        handler.postDelayed(refreshProgram, 60000);
    }

    private int numberKey(String number) {
        if (number == null) return 999999;
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 999999;
        try { return Integer.parseInt(digits); }
        catch (Exception e) { return 999999; }
    }

    private void tuneChannel(int index) {
        if (channels.isEmpty()) return;
        if (index < 0) index = channels.size() - 1;
        if (index >= channels.size()) index = 0;

        currentIndex = index;
        highlightedIndex = index;
        ChannelItem channel = channels.get(index);
        Uri channelUri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channel.id);

        try {
            tvView.tune(channel.inputId, channelUri);
            showChannelInfo(channel);
        } catch (SecurityException e) {
            showStatus("Tuner erişimi Android tarafından engellendi.\n" +
                    "Bu TV üreticisinin sistem izni istiyor olabilir.", 10000);
        } catch (Exception e) {
            showStatus("Kanal açılamadı:\n" +
                    e.getClass().getSimpleName() + "\n" + e.getMessage(), 9000);
        }

        adapter.notifyDataSetChanged();
    }

    private void showChannelInfo(ChannelItem channel) {
        String number = channel.number.isEmpty() ? "" : channel.number + "   ";
        channelTitle.setText(number + channel.name);
        programTitle.setText("Program bilgisi aranıyor…");
        programTime.setText(channel.videoFormat.isEmpty() ? "Uydu yayını" : "Kaynak: " + channel.videoFormat);
        infoBar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 5500);
        updateProgram(channel);
    }

    private void updateProgram(ChannelItem channel) {
        long now = System.currentTimeMillis();
        try {
            Uri uri = TvContract.buildProgramsUriForChannel(
                    channel.id, now - 60000, now + 6L * 60L * 60L * 1000L);

            String[] projection = {
                    TvContract.Programs.COLUMN_TITLE,
                    TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                    TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS
            };

            Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String title = cursor.getString(0);
                    long start = cursor.getLong(1);
                    long end = cursor.getLong(2);
                    if (start <= now && end > now) {
                        programTitle.setText(TextUtils.isEmpty(title) ? "Program bilgisi yok" : title);
                        DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT, new Locale("tr", "TR"));
                        programTime.setText(df.format(new Date(start)) + " – " + df.format(new Date(end)));
                        cursor.close();
                        return;
                    }
                }
                cursor.close();
            }
            programTitle.setText("Program bilgisi yok");
        } catch (Exception e) {
            programTitle.setText("EPG bilgisi kullanılamıyor");
        }
    }

    private void tuneByNumber(String number) {
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number.equals(number)) {
                tuneChannel(i);
                return;
            }
        }

        try {
            int wanted = Integer.parseInt(number);
            for (int i = 0; i < channels.size(); i++) {
                String clean = channels.get(i).number.replaceAll("[^0-9]", "");
                if (!clean.isEmpty() && Integer.parseInt(clean) == wanted) {
                    tuneChannel(i);
                    return;
                }
            }
        } catch (Exception ignored) {}

        showStatus("Kanal " + number + " bulunamadı.", 2500);
    }

    private void showChannelPanel() {
        channelPanel.setVisibility(View.VISIBLE);
        if (!channels.isEmpty()) {
            highlightedIndex = currentIndex >= 0 ? currentIndex : 0;
            channelList.setSelection(highlightedIndex);
            channelList.requestFocus();
            adapter.notifyDataSetChanged();
        }
    }

    private void hideChannelPanel() {
        channelPanel.setVisibility(View.GONE);
        root.requestFocus();
    }

    private void moveHighlight(int direction) {
        if (channels.isEmpty()) return;
        highlightedIndex += direction;
        if (highlightedIndex < 0) highlightedIndex = channels.size() - 1;
        if (highlightedIndex >= channels.size()) highlightedIndex = 0;
        channelList.setSelection(highlightedIndex);
        adapter.notifyDataSetChanged();
    }

    private void zap(int direction) {
        if (channels.isEmpty()) return;
        if (currentIndex < 0) tuneChannel(0);
        else tuneChannel(currentIndex + direction);
    }

    private void handleNumber(int number) {
        if (numberBuffer.length() >= 4) numberBuffer = "";
        numberBuffer += number;
        numberBox.setText(numberBuffer);
        numberBox.setVisibility(View.VISIBLE);
        handler.removeCallbacks(numberCommit);
        handler.postDelayed(numberCommit, 1400);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();

        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            handleNumber(key - KeyEvent.KEYCODE_0);
            return true;
        }

        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (channelPanel.getVisibility() == View.VISIBLE) {
                    if (!channels.isEmpty()) {
                        tuneChannel(highlightedIndex);
                        hideChannelPanel();
                    }
                } else {
                    showChannelPanel();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (channelPanel.getVisibility() == View.VISIBLE) moveHighlight(-1);
                else zap(1);
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channelPanel.getVisibility() == View.VISIBLE) moveHighlight(1);
                else zap(-1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (channelPanel.getVisibility() != View.VISIBLE) {
                    zap(1);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (channelPanel.getVisibility() != View.VISIBLE) {
                    zap(-1);
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_MENU:
                showChannelPanel();
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (channelPanel.getVisibility() == View.VISIBLE) {
                    hideChannelPanel();
                    return true;
                }
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showStatus(String text, long duration) {
        statusBox.setText(text);
        statusBox.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> statusBox.setVisibility(View.GONE), duration);
    }

    private String shortInputId(String inputId) {
        if (inputId == null) return "";
        if (inputId.length() <= 55) return inputId;
        return "…" + inputId.substring(inputId.length() - 55);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showStatus("✓ TV kanal listesi izni verildi.", 2000);
            } else {
                showStatus("TV kanal listesi izni verilmedi.\nYine de sistem erişimini test edeceğiz.", 6000);
            }
            loadTvSystem();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { tvView.reset(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return channels.size(); }
        @Override public Object getItem(int position) { return channels.get(position); }
        @Override public long getItemId(int position) { return channels.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row;
            if (convertView instanceof TextView) {
                row = (TextView) convertView;
            } else {
                row = new TextView(MainActivity.this);
                row.setTextSize(20);
                row.setTextColor(Color.WHITE);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(15), dp(16), dp(15));
            }

            ChannelItem channel = channels.get(position);
            String label = (channel.number.isEmpty() ? "—" : channel.number) + "     " + channel.name;
            if (!channel.videoFormat.isEmpty()) label += "\n       " + channel.videoFormat;
            row.setText(label);

            GradientDrawable bg = new GradientDrawable();
            if (position == highlightedIndex) {
                bg.setColor(Color.argb(100, 245, 158, 11));
                row.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            } else {
                bg.setColor(Color.TRANSPARENT);
                row.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            }
            bg.setCornerRadius(dp(12));
            row.setBackground(bg);
            return row;
        }
    }
}
