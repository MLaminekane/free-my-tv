package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "New this week" (the drawer's shortcut): one row per streaming app on the TV, its recent films (out in the last
 * 60 days) and its shows with an episode this week, most popular first, from TMDB (the picks' key, the picks'
 * country). OK opens the app.
 */
public class NewActivity extends Activity {
    /** TMDB watch provider → the app on the TV (the first installed one is used). */
    private static final Object[][] SERVICES = {
        {8, "Netflix", new String[] {"com.netflix.ninja"}},
        {119, "Prime Video", new String[] {"com.amazon.firebat", "com.amazon.amazonvideo.livingroom", "com.amazon.avod"}},
        {337, "Disney+", new String[] {"com.disney.disneyplus"}},
        {350, "Apple TV", new String[] {"com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv"}},
        {531, "Paramount+", new String[] {"com.cbs.ca", "com.cbs.ott"}},
        {1899, "Max", new String[] {"com.wbd.stream"}},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private float k;
    private boolean dark;
    private int ink, dim;
    private LinearLayout col;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        dark = !"light".equals(Prefs.theme());
        ink = dark ? 0xFFF2F2F7 : 0xFF1C1C1E;
        dim = dark ? 0xFF98989D : 0xFF6E6E73;
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(80), px(56), px(80), px(80));
        col.addView(text(Ui.tr("New this week"), Ui.semibold, 56, ink));
        status = text(Ui.tr("Loading…"), Ui.medium, 26, dim);
        col.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(dark ? 0xFF000000 : 0xFFF2F2F7);
        scroll.addView(col);
        setContentView(scroll);
        if (!Recs.hasKey(this)) {
            status.setText(Ui.tr("This needs a TMDB key (config.properties)."));
            return;
        }
        for (Object[] s : SERVICES) {
            String pkg = installed((String[]) s[2]);
            if (pkg != null) io.execute(() -> loadRow((int) s[0], (String) s[1], pkg));
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private String installed(String[] pkgs) {
        PackageManager pm = getPackageManager();
        for (String p : pkgs) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    private static final class Title {
        String name, poster;
        double popularity;
    }

    private void loadRow(int provider, String service, String pkg) {
        List<Title> titles = new ArrayList<>();
        try {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar c = Calendar.getInstance();
            String today = day.format(c.getTime());
            c.add(Calendar.DAY_OF_YEAR, -60);
            String twoMonths = day.format(c.getTime());
            c.add(Calendar.DAY_OF_YEAR, 53);
            String weekAgo = day.format(c.getTime());
            c.add(Calendar.DAY_OF_YEAR, 14);
            String weekAhead = day.format(c.getTime());
            String common = "watch_region=" + Recs.REGION + "&with_watch_providers=" + provider
                    + "&with_watch_monetization_types=flatrate&sort_by=popularity.desc&language=" + Ui.language;
            Recs recs = Recs.get(this);
            add(titles, recs.get("/discover/movie?" + common + "&primary_release_date.gte=" + twoMonths
                    + "&primary_release_date.lte=" + today), "title");
            add(titles, recs.get("/discover/tv?" + common + "&air_date.gte=" + weekAgo + "&air_date.lte=" + weekAhead), "name");
        } catch (Exception e) {
            AppLog.w("new this week, " + service + ": " + e.getMessage());
        }
        titles.sort((a, b) -> Double.compare(b.popularity, a.popularity));
        List<Title> top = titles.subList(0, Math.min(14, titles.size()));
        main.post(() -> {
            if (isFinishing() || top.isEmpty()) return;
            status.setVisibility(View.GONE);
            col.addView(label(Ui.trf("On {app}", "app", service)));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPadding(px(6), 0, px(40), 0);
            for (Title t : top) row.addView(tile(t, pkg));
            HorizontalScrollView h = new HorizontalScrollView(this);
            h.setHorizontalScrollBarEnabled(false);
            h.setClipChildren(false);
            h.addView(row);
            col.addView(h);
            if (col.getChildCount() == 4) row.getChildAt(0).requestFocus();   // title, status, first label, first row
        });
    }

    private static void add(List<Title> out, JSONObject page, String nameKey) {
        JSONArray results = page.optJSONArray("results");
        Set<String> seen = new HashSet<>();
        for (Title t : out) seen.add(t.name);
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null || o.optString("poster_path", "").isEmpty() || o.isNull("poster_path")) continue;
            Title t = new Title();
            t.name = o.optString(nameKey);
            t.poster = o.optString("poster_path");
            t.popularity = o.optDouble("popularity", 0);
            if (seen.add(t.name)) out.add(t);
        }
    }

    private View tile(Title t, String pkg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(220), -2);
        lp.setMargins(0, px(14), px(26), px(10));
        box.setLayoutParams(lp);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF2C2C2E : 0xFFDADADF);
        bg.setCornerRadius(18 * k);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 18 * k);
            }
        });
        card.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View ring = new View(this);
        GradientDrawable r = new GradientDrawable();
        r.setCornerRadius(18 * k);
        r.setStroke(px(5), dark ? Color.WHITE : 0xFF1C1C1E);
        ring.setBackground(r);
        ring.setVisibility(View.INVISIBLE);
        card.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(px(220), px(330)));
        TextView name = text(t.name, Ui.medium, 22, dim);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, px(10), 0, 0);
        box.addView(name);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, Ui.trf("Couldn't open {app}.", "app", pkg), Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        card.setOnFocusChangeListener((v, has) -> {
            ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            name.setTextColor(has ? ink : dim);
            v.animate().scaleX(has ? 1.08f : 1).scaleY(has ? 1.08f : 1).translationZ(has ? px(12) : 0).setDuration(150).start();
        });
        io.execute(() -> {
            Bitmap b = poster(t.poster);
            if (b != null) main.post(() -> img.setImageBitmap(b));
        });
        return box;
    }

    private static Bitmap poster(String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w342" + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 << 10];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                byte[] d = out.toByteArray();
                return BitmapFactory.decodeByteArray(d, 0, d.length, o);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private TextView label(String s) {
        TextView t = text(s, Ui.semibold, 30, dim);
        t.setPadding(0, px(36), 0, px(4));
        return t;
    }

    private TextView text(String s, android.graphics.Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
