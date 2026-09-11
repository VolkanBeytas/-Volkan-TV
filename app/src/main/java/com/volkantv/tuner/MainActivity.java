package com.volkantv.tuner;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final int PERMISSION_REQUEST = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<ChannelItem> channels = new ArrayList<>();

    private FrameLayout root;
    private TvView tvView;
    private LinearLayout statusBox;
    private TextView diagnostic;
    private LinearLayout infoBar;
    private TextView channelNumber;
    private TextView channelTitle;
    private TextView channelSource;
    private LinearLayout channelPanel;
    private ListView channelListView;
    private ChannelAdapter adapter;
    private LinearLayout numberBox;
    private TextView numberDisplay;

    private int currentIndex = -1;
    private StringBuilder numberBuffer = new StringBuilder();
    private AudioManager audioManager;

    static class ChannelItem {
        long id;
        String inputId;
        String number;
        String name;

        ChannelItem(long id, String inputId, String number, String name) {
            this.id = id;
            this.inputId = inputId;
            this.number = number != null ? number : "";
            this.name = name != null ? name : "Kanal";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemUi();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        // Donanım sesini doğrudan sistem akışına bağla
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        tvView = new TvView(this);
        root.addView(tvView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setupTvCallbacks();
        createStatusBox();
        createInfoBar();
        createChannelPanel();
        createNumberBox();

        showStatus("Simfer TV altyapı kontrol ediliyor...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{READ_TV_LISTINGS}, PERMISSION_REQUEST);
                return;
            }
        }
        loadTvSystem();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            loadTvSystem();
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setupTvCallbacks() {
        tvView.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onConnectionFailed(String inputId) {
                showStatus("TV girişi bağlantı kesildi. Kod: " + shortInputId(inputId));
            }

            @Override
            public void onDisconnected(String inputId) {
                showStatus("TV girişine bağlantı bekleniyor...");
            }

            @Override
            public void onVideoAvailable(String inputId) {
                hideStatus();
                unmuteHardwareAudio();
            }

            @Override
            public void onTracksChanged(String inputId, List<TvTrackInfo> tracks) {
                super.onTracksChanged(inputId, tracks);
                // Gelen yayın ses kanallarını otomatik aktif et
                if (tracks != null) {
                    for (TvTrackInfo track : tracks) {
                        if (track.getType() == TvTrackInfo.TYPE_AUDIO) {
                            tvView.selectTrack(TvTrackInfo.TYPE_AUDIO, track.getId());
                            break;
                        }
                    }
                }
                unmuteHardwareAudio();
            }

            @Override
            public void onTrackSelected(String inputId, int type, String trackId) {
                super.onTrackSelected(inputId, type, trackId);
                if (type == TvTrackInfo.TYPE_AUDIO) {
                    unmuteHardwareAudio();
                }
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                showStatus("Yayın bekleniyor (Kod: " + reason + ")");
            }
        });
    }

    // TELEVİZYONUN DONANIM SES KİLİDİNİ ÇÖZEN METOT
    private void unmuteHardwareAudio() {
        try {
            if (tvView != null) {
                tvView.setStreamVolume(1.0f);
            }

            if (audioManager != null) {
                // TV altyapısındaki tüm olası ses akışlarının Mute durumunu kaldır
                int[] streams = {
                        AudioManager.STREAM_MUSIC,
                        AudioManager.STREAM_SYSTEM,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.STREAM_RING
                };

                for (int s : streams) {
                    try {
                        audioManager.setStreamMute(s, false);
                    } catch (Exception ignored) {}
                }

                // Global Audio Focus isteği
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build();
                    AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(playbackAttributes)
                            .build();
                    audioManager.requestAudioFocus(focusRequest);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                }
            }
        } catch (Exception ignored) {}
    }

    private void loadTvSystem() {
        loadChannels();
    }

    private void loadChannels() {
        channels.clear();
        Uri uri = TvContract.Channels.CONTENT_URI;
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_DISPLAY_NAME
        };

        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(TvContract.Channels._ID);
                int inputCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INPUT_ID);
                int numCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NUMBER);
                int nameCol = cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    channels.add(new ChannelItem(
                            cursor.getLong(idCol),
                            cursor.getString(inputCol),
                            cursor.getString(numCol),
                            cursor.getString(nameCol)
                    ));
                }
            }
        } catch (Exception e) {
            showStatus("Kanal listesi okunamadı: " + e.getMessage());
            return;
        }

        if (channels.isEmpty()) {
            showStatus("Kanal 0 ise tuner üretici tarafından kilitli olabilir.");
            return;
        }

        Collections.sort(channels, new Comparator<ChannelItem>() {
            @Override
            public int compare(ChannelItem o1, ChannelItem o2) {
                int n1 = parseNum(o1.number);
                int n2 = parseNum(o2.number);
                if (n1 != n2) return Integer.compare(n1, n2);
                return o1.number.compareToIgnoreCase(o2.number);
            }

            private int parseNum(String s) {
                try {
                    return Integer.parseInt(s.replaceAll("[^0-9]", ""));
                } catch (Exception e) {
                    return 9999;
                }
            }
        });

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        tuneChannel(0);
    }

    private void tuneChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        currentIndex = index;
        ChannelItem c = channels.get(index);
        Uri channelUri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, c.id);

        try {
            tvView.tune(c.inputId, channelUri);
            unmuteHardwareAudio();
            showChannelInfo(c);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        } catch (Exception e) {
            showStatus("Kanal açılamadı: " + e.getMessage());
        }
    }

    private void showChannelInfo(ChannelItem c) {
        channelNumber.setText(c.number);
        channelTitle.setText(c.name);
        channelSource.setText(shortInputId(c.inputId));
        infoBar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfoRunnable);
        handler.postDelayed(hideInfoRunnable, 5000);
    }

    private final Runnable hideInfoRunnable = () -> infoBar.setVisibility(View.GONE);

    private void showStatus(String msg) {
        diagnostic.setText(msg);
        statusBox.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusBox.setVisibility(View.GONE);
    }

    private String shortInputId(String full) {
        if (full == null) return "";
        int slash = full.lastIndexOf('/');
        return slash != -1 ? full.substring(slash + 1) : full;
    }

    private void createStatusBox() {
        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(16), dp(10), dp(16), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#CC000000"));
        bg.setCornerRadius(dp(8));
        statusBox.setBackground(bg);

        diagnostic = new TextView(this);
        diagnostic.setTextColor(Color.WHITE);
        diagnostic.setTextSize(14);
        statusBox.addView(diagnostic);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(24), dp(24), 0, 0);
        root.addView(statusBox, lp);
    }

    private void createInfoBar() {
        infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.HORIZONTAL);
        infoBar.setPadding(dp(20), dp(12), dp(20), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6111111"));
        bg.setCornerRadius(dp(10));
        infoBar.setBackground(bg);

        channelNumber = new TextView(this);
        channelNumber.setTextColor(Color.parseColor("#FFD700"));
        channelNumber.setTextSize(22);
        channelNumber.setPadding(0, 0, dp(15), 0);
        infoBar.addView(channelNumber);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        channelTitle = new TextView(this);
        channelTitle.setTextColor(Color.WHITE);
        channelTitle.setTextSize(20);
        textCol.addView(channelTitle);

        channelSource = new TextView(this);
        channelSource.setTextColor(Color.LTGRAY);
        channelSource.setTextSize(13);
        textCol.addView(channelSource);

        infoBar.addView(textCol);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(30), 0, 0, dp(30));
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        infoBar.setVisibility(View.GONE);
        root.addView(infoBar, lp);
    }

    private void createChannelPanel() {
        channelPanel = new LinearLayout(this);
        channelPanel.setOrientation(LinearLayout.VERTICAL);
        channelPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        channelPanel.setBackgroundColor(Color.parseColor("#EE151515"));

        TextView panelSummary = new TextView(this);
        panelSummary.setText("KANALLAR (OK ile seç)");
        panelSummary.setTextColor(Color.parseColor("#FFD700"));
        panelSummary.setTextSize(16);
        panelSummary.setPadding(0, 0, 0, dp(10));
        channelPanel.addView(panelSummary);

        channelListView = new ListView(this);
        adapter = new ChannelAdapter();
        channelListView.setAdapter(adapter);
        channelListView.setDividerHeight(dp(1));
        channelListView.setFocusable(true);
        channelListView.setFocusableInTouchMode(true);
        channelListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        channelListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                tuneChannel(position);
                channelPanel.setVisibility(View.GONE);
                tvView.requestFocus();
            }
        });

        channelPanel.addView(channelListView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(340),
                ViewGroup.LayoutParams.MATCH_PARENT);
        channelPanel.setVisibility(View.GONE);
        root.addView(channelPanel, lp);
    }

    private void createNumberBox() {
        numberBox = new LinearLayout(this);
        numberBox.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#DD000000"));
        bg.setCornerRadius(dp(8));
        numberBox.setBackground(bg);

        numberDisplay = new TextView(this);
        numberDisplay.setTextColor(Color.WHITE);
        numberDisplay.setTextSize(28);
        numberBox.addView(numberDisplay);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(24), dp(24), 0);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        numberBox.setVisibility(View.GONE);
        root.addView(numberBox, lp);
    }

    class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() { return channels.size(); }
        @Override
        public Object getItem(int i) { return channels.get(i); }
        @Override
        public long getItemId(int i) { return i; }
        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            TextView tv = (TextView) view;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setPadding(dp(14), dp(12), dp(14), dp(12));
                tv.setTextSize(16);
            }
            ChannelItem item = channels.get(i);
            tv.setText(item.number + "   " + item.name);
            tv.setTextColor(i == currentIndex ? Color.parseColor("#FFD700") : Color.WHITE);
            return tv;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();

            // Kumandadaki fiziksel ses tuşları basıldığında donanım sesini artır/azalt
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return false; // Sistem varsayılan TV ses katmanına bıraksın
            }

            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                handleNumber(code - KeyEvent.KEYCODE_0);
                return true;
            }

            if (channelPanel.getVisibility() == View.VISIBLE) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    channelPanel.setVisibility(View.GONE);
                    tvView.requestFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    int pos = channelListView.getSelectedItemPosition();
                    if (pos != AdapterView.INVALID_POSITION) {
                        tuneChannel(pos);
                        channelPanel.setVisibility(View.GONE);
                        tvView.requestFocus();
                        return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            }

            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    tuneChannel((currentIndex + 1) % channels.size());
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    tuneChannel((currentIndex - 1 + channels.size()) % channels.size());
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    channelPanel.setVisibility(View.VISIBLE);
                    channelListView.requestFocus();
                    if (currentIndex >= 0 && currentIndex < channels.size()) {
                        channelListView.setSelection(currentIndex);
                    }
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleNumber(int n) {
        numberBuffer.append(n);
        numberDisplay.setText(numberBuffer.toString());
        numberBox.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitNumberRunnable);
        handler.postDelayed(commitNumberRunnable, 2000);
    }

    private final Runnable commitNumberRunnable = () -> {
        String num = numberBuffer.toString();
        numberBuffer.setLength(0);
        numberBox.setVisibility(View.GONE);
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number.equals(num)) {
                tuneChannel(i);
                break;
            }
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        unmuteHardwareAudio();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tvView != null) {
            tvView.reset();
        }
    }
}
