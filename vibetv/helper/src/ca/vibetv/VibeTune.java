package ca.vibetv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;
import java.io.FileNotFoundException;

/** Playback tweaks against buffering: retries, quicker stall detection, and the optional live delay. */
public final class VibeTune {
    private static final long TIME_UNSET = -9223372036854775807L;
    private static final int MAX_RETRIES = 5;
    /** Live delay option: play this far behind live, for a bigger cushion. */
    private static final long LIVE_DELAY_MS = 30000;
    private static final String PREFS = "vibetv_settings";
    private static final String KEY_LIVE_DELAY = "live_delay";
    private static final String KEY_QUALITY = "video_quality";
    /** A reload this long after the previous one counts as a fresh start (playback had recovered). */
    private static final long RESTART_RESET_MS = 60000;

    private static Context appCtx;
    private static long lastRestartAt;

    private VibeTune() {}

    public static void init(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            appCtx = app != null ? app : ctx;
        } catch (Throwable ignored) {}
    }

    // ---- 1. Retry failed video chunk downloads instead of reloading the whole stream ----

    /** LoadErrorHandlingPolicy.getMinimumLoadableRetryCount (the app returned 0). */
    public static int retryCount(int dataType) {
        return MAX_RETRIES;
    }

    /** LoadErrorHandlingPolicy.getRetryDelayMsFor (the app never retried). */
    public static long retryDelayMs(LoadErrorHandlingPolicy.LoadErrorInfo info) {
        try {
            java.io.IOException e = info.exception;
            if (e instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) e).responseCode;
                // 403 etc. mean "new IP / session": let the app handle those right away, as before.
                // Server hiccups (5xx), timeouts (408) and rate limits (429) are worth a retry.
                if (code < 500 && code != 408 && code != 429) return TIME_UNSET;
            }
            if (e instanceof ParserException || e instanceof FileNotFoundException) return TIME_UNSET;
            if (info.errorCount > MAX_RETRIES) return TIME_UNSET;
            VibeLog.event("Video download failed (try " + info.errorCount + "), retrying: " + e);
            return Math.min(1000L * info.errorCount, 3000L);
        } catch (Throwable t) {
            return TIME_UNSET;
        }
    }

    // ---- 3. Reset the reload counter once playback has recovered ----

    /** Called with the app's reload counter before each reload; returns the value to use. */
    public static int restartCount(int count) {
        long now = System.currentTimeMillis();
        long since = now - lastRestartAt;
        lastRestartAt = now;
        if (count > 0 && since > RESTART_RESET_MS) {
            VibeLog.event("Stream reload after " + since / 1000 + " s of good playback: reload counter reset");
            return 0;
        }
        return count;
    }

    // ---- 4. Optional: play live TV further behind live ----

    public static boolean liveDelayEnabled() {
        try {
            return appCtx != null && prefs(appCtx).getBoolean(KEY_LIVE_DELAY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Replaces MediaItem.fromUri: same item, with a larger live offset when the option is on. */
    public static MediaItem mediaItem(Uri uri) {
        if (!liveDelayEnabled()) return MediaItem.fromUri(uri);
        try {
            return new MediaItem.Builder().setUri(uri)
                    .setLiveTargetOffsetMs(LIVE_DELAY_MS)
                    .setLiveMaxOffsetMs(LIVE_DELAY_MS + 30000)
                    .build();
        } catch (Throwable t) {
            VibeLog.e(t);
            return MediaItem.fromUri(uri);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, 0);
    }


    // ---- 5. Video quality: Auto (the app's own behaviour), Low, High, Best ----

    private static final String[] QUALITY_KEYS = {"auto", "low", "high", "best"};
    private static final String[] QUALITY_NAMES = {"Auto", "Low", "High", "Best"};
    private static final String[] QUALITY_CHOICES = {
            "Auto (adapts to your connection)",
            "Low (saves data)",
            "High (up to 720p, adapts)",
            "Best (always the highest; may buffer on slow connections)"};

    private static int qualityIndex(Context c) {
        try {
            String v = c.getSharedPreferences(PREFS, 0).getString(KEY_QUALITY, "auto");
            for (int i = 0; i < QUALITY_KEYS.length; i++) if (QUALITY_KEYS[i].equals(v)) return i;
        } catch (Throwable ignored) {}
        return 0;
    }

    /** Replaces ExoPlayer.Builder.build() where the app creates its player. */
    public static ExoPlayer buildPlayer(ExoPlayer.Builder builder) {
        ExoPlayer player = builder.build();
        try {
            int q = appCtx == null ? 0 : qualityIndex(appCtx);
            if (q == 0) return player;
            TrackSelector ts = player.getTrackSelector();
            if (!(ts instanceof DefaultTrackSelector)) return player;
            DefaultTrackSelector selector = (DefaultTrackSelector) ts;
            DefaultTrackSelector.ParametersBuilder p = selector.buildUponParameters();
            if (q == 1) {
                p.setForceLowestBitrate(true);
            } else if (q == 2) {
                p.setMaxVideoSize(1280, 720);
            } else {
                // Not limited to the screen's size, and no stepping down.
                p.clearViewportSizeConstraints().setForceHighestSupportedBitrate(true);
            }
            selector.setParameters(p);
            VibeLog.event("Video quality: " + QUALITY_NAMES[q]);
        } catch (Throwable t) {
            VibeLog.e(t);
        }
        return player;
    }

    // ---- Settings screen: a "VibeTV" section under "Player" ----

    // Resource ids of app version 3.1.37, used if a lookup by name fails.
    private static final int ID_PLAYER_SECTION = 0x7f0a03bc;
    private static final int ID_TITLE = 0x7f0a050f;
    private static final int ID_SUB_TITLE = 0x7f0a04c3;
    private static final int ID_SWITCHER = 0x7f0a04dc;
    private static final int ID_ICON_LAYOUT = 0x7f0a0286;
    private static final int LAYOUT_SETTINGS_ITEM = 0x7f0d0134;

    private static int res(Context c, String name, String type, int fallback) {
        try {
            int id = c.getResources().getIdentifier(name, type, c.getPackageName());
            return id != 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Adds the option to the Settings screen (called from SettingsFragment.onViewCreated). */
    public static void addSettings(View root) {
        try {
            final Context c = root.getContext();
            View section = root.findViewById(res(c, "player_section", "id", ID_PLAYER_SECTION));
            if (section == null) return;
            ViewParent parent = section.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup list = (ViewGroup) parent;

            LinearLayout block = new LinearLayout(c);
            block.setOrientation(LinearLayout.VERTICAL);

            // Section title styled like "Player".
            TextView header = new TextView(c);
            header.setText("VibeTV");
            if (section instanceof ViewGroup && ((ViewGroup) section).getChildCount() > 0
                    && ((ViewGroup) section).getChildAt(0) instanceof TextView) {
                TextView model = (TextView) ((ViewGroup) section).getChildAt(0);
                header.setTextColor(model.getTextColors());
                header.setTextSize(0 /* px */, model.getTextSize());
                header.setTypeface(model.getTypeface());
                if (model.getLayoutParams() != null) header.setLayoutParams(model.getLayoutParams());
            }
            block.addView(header);

            block.addView(qualityRow(c, block));

            View row = LayoutInflater.from(c).inflate(res(c, "settings_item", "layout", LAYOUT_SETTINGS_ITEM), block, false);
            TextView title = row.findViewById(res(c, "title", "id", ID_TITLE));
            if (title != null) title.setText("Live TV: play 30 s behind (fewer stalls)");
            View sub = row.findViewById(res(c, "sub_title", "id", ID_SUB_TITLE));
            if (sub != null) sub.setVisibility(View.GONE);
            View icons = row.findViewById(res(c, "icon_layout", "id", ID_ICON_LAYOUT));
            if (icons instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) icons;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View child = g.getChildAt(i);
                    if (!(child instanceof CompoundButton)) child.setVisibility(View.GONE); // the arrow
                }
            }
            final CompoundButton toggle = row.findViewById(res(c, "switcher", "id", ID_SWITCHER));
            if (toggle == null) return;
            toggle.setVisibility(View.VISIBLE);
            toggle.setChecked(prefs(c).getBoolean(KEY_LIVE_DELAY, false));
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                    prefs(c).edit().putBoolean(KEY_LIVE_DELAY, on).apply();
                    VibeLog.event("Live delay option " + (on ? "on" : "off"));
                    Toast.makeText(c, on
                            ? "Live TV will play 30 s behind live. Applies when you next open a channel."
                            : "Live TV back to normal. Applies when you next open a channel.", Toast.LENGTH_LONG).show();
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggle.toggle(); }
            });
            block.addView(row);

            list.addView(block, list.indexOfChild(section) + 1);
        } catch (Throwable t) {
            VibeLog.e(t);
        }
    }

    /** "Video quality  Auto >": opens a list to pick Auto / Low / High / Best. */
    private static View qualityRow(final Context c, ViewGroup parent) {
        View row = LayoutInflater.from(c).inflate(res(c, "settings_item", "layout", LAYOUT_SETTINGS_ITEM), parent, false);
        TextView title = row.findViewById(res(c, "title", "id", ID_TITLE));
        if (title != null) title.setText("Video quality");
        final TextView value = row.findViewById(res(c, "sub_title", "id", ID_SUB_TITLE));
        if (value != null) {
            value.setVisibility(View.VISIBLE);
            value.setText(QUALITY_NAMES[qualityIndex(c)]);
        }
        View toggle = row.findViewById(res(c, "switcher", "id", ID_SWITCHER));
        if (toggle != null) toggle.setVisibility(View.GONE);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(c)
                        .setTitle("Video quality")
                        .setSingleChoiceItems(QUALITY_CHOICES, qualityIndex(c), new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                prefs(c).edit().putString(KEY_QUALITY, QUALITY_KEYS[which]).apply();
                                if (value != null) value.setText(QUALITY_NAMES[which]);
                                VibeLog.event("Video quality option: " + QUALITY_NAMES[which]);
                                Toast.makeText(c, "Applies when you next open a channel.", Toast.LENGTH_LONG).show();
                                d.dismiss();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        return row;
    }
}
