package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Football tab: the big leagues like the Apple Sports app, from ESPN's public data (no key). A pill per league
 * (focusing one shows it); then the matches being played, the results, the coming matches (cards with both teams'
 * logos, names and scores; the winner in bold; Real Madrid, Barcelona and PSG in blue) and the standings. Live scores
 * refresh every minute while this screen shows. Logos are kept on disk (96 px, ~9 KB each).
 */
public class FootballActivity extends Activity {
    private static final String[][] LEAGUES = {
        {"uefa.champions", "Champions League"}, {"esp.1", "LaLiga"}, {"eng.1", "Premier League"}, {"fra.1", "Ligue 1"},
    };
    private static final String[] FAVORITES = {"real madrid", "barcelona", "paris saint-germain"};
    private static final String API = "https://site.api.espn.com/apis/";
    private static final int ACCENT = 0xFF0A84FF, LIVE = 0xFFFF453A;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> logos = new HashMap<>();
    private float k;
    private boolean dark;
    private int ink, dim, card;
    private LinearLayout content;
    private final List<TextView> pills = new ArrayList<>();
    private int league = 1, generation;
    private boolean hasLive;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            // Only with a match being played, and not while browsing the cards (rebuilding would move the focus).
            if (hasLive && pills.contains(getCurrentFocus())) load(false);
            main.postDelayed(this, 60_000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        dark = !"light".equals(Prefs.theme());
        ink = dark ? 0xFFF2F2F7 : 0xFF1C1C1E;
        dim = dark ? 0xFF98989D : 0xFF6E6E73;
        card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(80), px(56), px(80), px(80));
        col.addView(text(Ui.tr("Football"), Ui.semibold, 56, ink));
        LinearLayout row = row();
        row.setPadding(px(10), px(20), 0, px(10));   // room for the focused pill's zoom
        col.setClipChildren(false);
        for (int i = 0; i < LEAGUES.length; i++) {
            final int index = i;
            TextView p = text(LEAGUES[i][1], Ui.semibold, 28, ink);
            p.setPadding(px(34), px(16), px(34), px(16));
            p.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, px(18), 0);
            p.setLayoutParams(lp);
            p.setOnFocusChangeListener((v, has) -> {
                if (has && league != index) {
                    league = index;
                    load(true);
                }
                stylePills();
            });
            pills.add(p);
            row.addView(p);
        }
        col.addView(row);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        col.addView(content);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(dark ? 0xFF000000 : 0xFFF2F2F7);
        scroll.addView(col);
        setContentView(scroll);
        stylePills();
        pills.get(league).requestFocus();
        load(true);
    }

    @Override protected void onResume() {
        super.onResume();
        main.postDelayed(refresh, 60_000);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void stylePills() {
        for (int i = 0; i < pills.size(); i++) {
            TextView p = pills.get(i);
            boolean selected = i == league, focused = p.hasFocus();
            p.setBackground(round(focused ? (dark ? 0xFFF2F2F7 : 0xFF1C1C1E) : selected ? card : 0, 40, 0, 0));
            p.setTextColor(focused ? (dark ? 0xFF1C1C1E : 0xFFF2F2F7) : selected ? ink : dim);
            p.animate().scaleX(focused ? 1.06f : 1).scaleY(focused ? 1.06f : 1).setDuration(120).start();
        }
    }

    // MARK: - Data

    private static final class Team {
        String name, logo;
        String score = "";
        boolean winner, favorite;
    }

    private static final class Match {
        Team home, away;
        String state, detail;
        long at;
    }

    private static final class Row {
        String rank, name, logo, played, wins, draws, losses, diff, points;
        boolean favorite;
    }

    /** Loads the league shown; `clear` first shows "Loading…" (a new league) instead of the old one's matches. */
    private void load(boolean clear) {
        int gen = ++generation;
        String id = LEAGUES[league][0];
        if (clear) {
            content.removeAllViews();
            content.addView(label(Ui.tr("Loading…")));
        }
        io.execute(() -> {
            List<Match> matches = new ArrayList<>();
            List<Row> table = new ArrayList<>();
            try {
                for (String month : months()) matches.addAll(parseMatches(get(API + "site/v2/sports/soccer/" + id + "/scoreboard?dates=" + month)));
            } catch (Exception e) {
                AppLog.w("football " + id + ": " + e.getMessage());
            }
            try {
                table = parseTable(get(API + "v2/sports/soccer/" + id + "/standings"));
            } catch (Exception e) {
                AppLog.w("football standings " + id + ": " + e.getMessage());
            }
            List<Row> rows = table;
            main.post(() -> {
                if (gen != generation || isFinishing()) return;
                show(matches, rows);
            });
        });
    }

    /** This month, and the one before (first week) or after (last ten days) so results and fixtures don't run dry. */
    private static List<String> months() {
        List<String> out = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyyMM", Locale.US);
        Calendar c = Calendar.getInstance();
        int day = c.get(Calendar.DAY_OF_MONTH);
        if (day <= 7) {
            Calendar prev = (Calendar) c.clone();
            prev.add(Calendar.MONTH, -1);
            out.add(f.format(prev.getTime()));
        }
        out.add(f.format(c.getTime()));
        if (day >= 21) {
            Calendar next = (Calendar) c.clone();
            next.add(Calendar.MONTH, 1);
            out.add(f.format(next.getTime()));
        }
        return out;
    }

    private static List<Match> parseMatches(String json) throws Exception {
        List<Match> out = new ArrayList<>();
        JSONArray events = new JSONObject(json).optJSONArray("events");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray teams = comp.optJSONArray("competitors");
            if (teams == null || teams.length() < 2) continue;
            Match m = new Match();
            JSONObject type = e.optJSONObject("status") == null ? null : e.optJSONObject("status").optJSONObject("type");
            m.state = type == null ? "pre" : type.optString("state", "pre");
            m.detail = type == null ? "" : type.optString("shortDetail", "");
            Date d = iso.parse(e.optString("date"));
            m.at = d == null ? 0 : d.getTime();
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t);
                JSONObject j = c.optJSONObject("team");
                Team team = new Team();
                team.name = j == null ? "?" : j.optString("shortDisplayName", j.optString("displayName", "?"));
                team.logo = j == null ? null : j.optString("logo", null);
                team.score = c.optString("score", "");
                team.winner = c.optBoolean("winner", false);
                team.favorite = favorite(j == null ? "" : j.optString("displayName", ""));
                if ("home".equals(c.optString("homeAway"))) m.home = team;
                else m.away = team;
            }
            if (m.home != null && m.away != null) out.add(m);
        }
        return out;
    }

    private static List<Row> parseTable(String json) throws Exception {
        List<Row> out = new ArrayList<>();
        JSONArray groups = new JSONObject(json).optJSONArray("children");
        for (int g = 0; groups != null && g < groups.length(); g++) {
            JSONObject standings = groups.getJSONObject(g).optJSONObject("standings");
            JSONArray entries = standings == null ? null : standings.optJSONArray("entries");
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                JSONObject team = e.optJSONObject("team");
                Row r = new Row();
                r.name = team == null ? "?" : team.optString("shortDisplayName", team.optString("displayName", "?"));
                JSONArray ls = team == null ? null : team.optJSONArray("logos");
                r.logo = ls != null && ls.length() > 0 ? ls.getJSONObject(0).optString("href", null) : null;
                r.favorite = favorite(team == null ? "" : team.optString("displayName", ""));
                Map<String, String> stats = new HashMap<>();
                JSONArray st = e.optJSONArray("stats");
                for (int s = 0; st != null && s < st.length(); s++) {
                    JSONObject o = st.getJSONObject(s);
                    stats.put(o.optString("name"), o.optString("displayValue"));
                }
                r.rank = stats.containsKey("rank") ? stats.get("rank") : String.valueOf(i + 1);
                r.played = stats.get("gamesPlayed");
                r.wins = stats.get("wins");
                r.draws = stats.get("ties");
                r.losses = stats.get("losses");
                r.diff = stats.get("pointDifferential");
                r.points = stats.get("points");
                out.add(r);
            }
        }
        out.sort((a, b) -> {
            try {
                return Integer.parseInt(a.rank) - Integer.parseInt(b.rank);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        return out;
    }

    private static boolean favorite(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String f : FAVORITES) if (n.contains(f)) return true;
        return false;
    }

    // MARK: - Screen

    private void show(List<Match> matches, List<Row> table) {
        View focused = getCurrentFocus();
        boolean keepFocus = focused != null && pills.contains(focused);
        content.removeAllViews();
        long now = System.currentTimeMillis();
        List<Match> live = new ArrayList<>(), results = new ArrayList<>(), coming = new ArrayList<>();
        for (Match m : matches) {
            if (m.state.equals("in")) live.add(m);
            else if (m.state.equals("post")) results.add(m);
            else if (m.at > now - 3 * 3600_000L) coming.add(m);
        }
        hasLive = !live.isEmpty();
        results.sort((a, b) -> Long.compare(b.at, a.at));
        coming.sort((a, b) -> Long.compare(a.at, b.at));
        if (!live.isEmpty()) section(Ui.tr("Live"), live, 20);
        if (!results.isEmpty()) section(Ui.tr("Results"), results, 16);
        if (!coming.isEmpty()) section(Ui.tr("Upcoming"), coming, 16);
        if (!table.isEmpty()) standings(table);
        if (content.getChildCount() == 0) content.addView(label(Ui.tr("Nothing to show right now.")));
        if (keepFocus) focused.requestFocus();
    }

    private void section(String title, List<Match> list, int max) {
        content.addView(label(title));
        LinearLayout row = row();
        for (int i = 0; i < Math.min(max, list.size()); i++) row.addView(matchCard(list.get(i)));
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.addView(row);
        content.addView(h);
    }

    /** Apple Sports style: the status on top, then each team on its line with its logo, its name and its score. */
    private View matchCard(Match m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(26), px(20), px(26), px(20));
        box.setBackground(round(card, 22, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(400), -2);
        lp.setMargins(0, px(12), px(24), px(12));
        box.setLayoutParams(lp);
        boolean live = m.state.equals("in"), done = m.state.equals("post");
        String status = live ? "● " + m.detail : done ? m.detail + " · " + day(m.at) : when(m.at);
        TextView top = text(status, Ui.semibold, 21, live ? LIVE : dim);
        top.setPadding(0, 0, 0, px(12));
        box.addView(top);
        box.addView(teamLine(m.home, m, done));
        box.addView(teamLine(m.away, m, done));
        focusable(box);
        return box;
    }

    private View teamLine(Team t, Match m, boolean done) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, px(6), 0, px(6));
        ImageView logo = new ImageView(this);
        line.addView(logo, new LinearLayout.LayoutParams(px(48), px(48)));
        loadLogo(t.logo, logo);
        boolean lost = done && !t.winner && (m.home.winner || m.away.winner);
        TextView name = text(t.name, t.winner ? Ui.semibold : Ui.medium, 27, t.favorite ? ACCENT : lost ? dim : ink);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(px(18), 0, px(12), 0);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (!m.state.equals("pre")) {
            TextView score = text(t.score, Ui.semibold, 32, lost ? dim : ink);
            line.addView(score);
        }
        return line;
    }

    private void standings(List<Row> table) {
        content.addView(label(Ui.tr("Standings")));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(30), px(16), px(30), px(16));
        box.setBackground(round(card, 22, 0, 0));
        box.addView(tableRow(null, "#", Ui.tr("Team"), Ui.tr("P"), Ui.tr("W"), Ui.tr("D"), Ui.tr("L"), Ui.tr("GD"), Ui.tr("Pts"), false, true));
        for (Row r : table) {
            View v = tableRow(r.logo, r.rank, r.name, r.played, r.wins, r.draws, r.losses, r.diff, r.points, r.favorite, false);
            focusable(v);   // focusable rows: the arrows can scroll down the whole table
            box.addView(v);
        }
        content.addView(box, new LinearLayout.LayoutParams(px(1100), -2));
    }

    private View tableRow(String logoUrl, String rank, String name, String p, String w, String d, String l, String gd,
                          String pts, boolean favorite, boolean header) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(px(10), px(8), px(10), px(8));
        int color = header ? dim : favorite ? ACCENT : ink;
        Typeface face = header ? Ui.medium : Ui.semibold;
        line.addView(cell(rank, face, header ? dim : dim, 60, Gravity.START));
        ImageView logo = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(40));
        lp.setMargins(0, 0, px(16), 0);
        line.addView(logo, lp);
        if (logoUrl != null) loadLogo(logoUrl, logo);
        TextView n = text(name, face, 26, color);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        for (String v : new String[] {p, w, d, l, gd}) line.addView(cell(v, Ui.medium, dim, 80, Gravity.CENTER));
        line.addView(cell(pts, Ui.semibold, color, 90, Gravity.CENTER));
        return line;
    }

    private TextView cell(String s, Typeface face, int color, float width, int gravity) {
        TextView t = text(s == null ? "" : s, face, 25, color);
        t.setGravity(gravity);
        t.setLayoutParams(new LinearLayout.LayoutParams(px(width), -2));
        return t;
    }

    /** Focus: a white (or dark) outline and a slight zoom. */
    private void focusable(View v) {
        v.setFocusable(true);
        GradientDrawable rest = v.getBackground() instanceof GradientDrawable ? (GradientDrawable) v.getBackground() : null;
        v.setOnFocusChangeListener((view, has) -> {
            GradientDrawable bg = round(rest != null ? card : has ? (dark ? 0xFF2C2C2E : 0xFFE5E5EA) : 0, 22,
                    has && rest != null ? 4 : 0, dark ? Color.WHITE : 0xFF1C1C1E);
            view.setBackground(bg);
            if (rest != null) {
                view.animate().scaleX(has ? 1.05f : 1).scaleY(has ? 1.05f : 1).translationZ(has ? px(10) : 0).setDuration(140).start();
            }
        });
    }

    private void loadLogo(String url, ImageView into) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached;
        synchronized (logos) {
            cached = logos.get(url);
        }
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            Bitmap b = logo(url);
            if (b == null) return;
            synchronized (logos) {
                logos.put(url, b);
            }
            main.post(() -> into.setImageBitmap(b));
        });
    }

    /** A team logo at 96 px (ESPN's resizer), from the disk cache when it's there. */
    private Bitmap logo(String url) {
        String path = url.replaceFirst("^https?://a\\.espncdn\\.com", "");
        File dir = new File(getCacheDir(), "logos");
        File f = new File(dir, path.replaceAll("[^A-Za-z0-9.]", "_"));
        try {
            if (!f.exists()) {
                dir.mkdirs();
                byte[] data = getBytes("https://a.espncdn.com/combiner/i?img=" + path + "&h=96&w=96");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Exception e) {
            return null;
        }
    }

    private static String get(String url) throws Exception {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 << 10];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 8 << 20) throw new java.io.IOException("too big");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String day(long at) {
        return new SimpleDateFormat("EEE d MMM", Ui.locale()).format(new Date(at));
    }

    /** "Today 3:00 PM", "Tomorrow 3:00 PM", "Sat 1 Nov · 3:00 PM". */
    private static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        int days = then.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
                + 365 * (then.get(Calendar.YEAR) - now.get(Calendar.YEAR));
        String d = days == 0 ? Ui.tr("Today") : days == 1 ? Ui.tr("Tomorrow") : day(at) + " ·";
        return d + " " + Ui.time(at);
    }

    // MARK: - Views

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(px(6), 0, px(40), 0);
        return r;
    }

    private TextView label(String s) {
        TextView t = text(s, Ui.semibold, 30, dim);
        t.setPadding(0, px(32), 0, px(4));
        return t;
    }

    private TextView text(String s, Typeface face, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        return t;
    }

    private GradientDrawable round(int fill, float radius, float stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius * k);
        if (stroke > 0) d.setStroke(px(stroke), strokeColor);
        return d;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
