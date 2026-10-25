package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Football on the home screen's status line (HomeView.drawStatusLine), from ESPN's public scoreboards (no key): the
 * Champions League, LaLiga, the Premier League and Ligue 1. What shows, in order: matches being played (the favorite
 * teams first, then the Champions League; two at most), a favorite's result from the last 3 hours, the favorites'
 * next match in the coming week. Fetched only while the home screen asks (every minute during a live match, every
 * 15 minutes otherwise).
 */
final class Sports {
    /** Starts a line that is a live score (HomeView draws the red "● LIVE" before it). */
    static final String LIVE = "\u0001";

    private static final String[] LEAGUES = {"uefa.champions", "esp.1", "eng.1", "fra.1"};
    private static final String[] FAVORITES = {"real madrid", "barcelona", "paris saint-germain", "psg"};
    /** The favorites' schedules (their league's upcoming matches): ESPN league, team id. Every 6 hours. */
    private static final String[][] TEAMS = {{"esp.1", "86"}, {"esp.1", "83"}, {"fra.1", "160"}};
    private static final long SCHEDULE_EVERY = 6 * 3600_000L;
    private volatile List<Match> upcoming = new ArrayList<>();
    private volatile long scheduleAt;
    private static final long LIVE_EVERY = 60_000L, IDLE_EVERY = 15 * 60_000L, RECENT = 3 * 3600_000L, AHEAD = 7 * 86_400_000L;

    private static Sports instance;

    static synchronized Sports get(Context c) {
        if (instance == null) instance = new Sports();
        return instance;
    }

    private static final class Match {
        String id, home, away, homeScore, awayScore, clock, league;
        String state;   // "pre", "in", "post"
        long at;
        boolean favorite;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<Match> matches = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;
    private volatile String line = "";
    private long lineAt = -1;
    private Runnable listener;

    /** Runs (on the main thread) when new scores came in; null to stop. */
    void listen(Runnable r) {
        listener = r;
    }

    /** What the status line shows (empty: nothing to show), refreshing in the background when stale. */
    String line() {
        refreshIfStale();
        long minute = System.currentTimeMillis() / 60_000;
        if (minute != lineAt) {   // the countdown to a match and "the last 3 hours" move with the clock
            lineAt = minute;
            line = build(matches);
        }
        return line;
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        boolean live = false;
        for (Match m : matches) live |= m.state.equals("in");
        if (fetching || (now - fetchedAt >= 0 && now - fetchedAt < (live ? LIVE_EVERY : IDLE_EVERY))) return;
        fetching = true;
        io.execute(() -> {
            try {
                // Today's matches (ESPN's scoreboards take one day, not a range): live scores and results.
                List<Match> all = new ArrayList<>();
                for (String league : LEAGUES) {
                    try {
                        all.addAll(parse(get(API + league + "/scoreboard"), league));
                    } catch (IOException | org.json.JSONException e) {
                        AppLog.w("sports " + league + ": " + e.getMessage());
                    }
                }
                // The favorites' coming matches.
                if (System.currentTimeMillis() - scheduleAt > SCHEDULE_EVERY || upcoming.isEmpty()) {
                    List<Match> next = new ArrayList<>();
                    for (String[] t : TEAMS) {
                        try {
                            next.addAll(parse(get(API + t[0] + "/teams/" + t[1] + "/schedule?fixture=true"), t[0]));
                        } catch (IOException | org.json.JSONException e) {
                            AppLog.w("sports team " + t[1] + ": " + e.getMessage());
                        }
                    }
                    if (!next.isEmpty()) {
                        upcoming = next;
                        scheduleAt = System.currentTimeMillis();
                    }
                }
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (Match m : all) seen.add(m.id);
                for (Match m : upcoming) if (seen.add(m.id)) all.add(m);
                matches = all;
                fetchedAt = System.currentTimeMillis();
                lineAt = -1;
            } finally {
                fetching = false;
                main.post(() -> { if (listener != null) listener.run(); });
            }
        });
    }

    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/soccer/";

    private static List<Match> parse(String json, String league) throws org.json.JSONException {
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
            m.league = league;
            m.id = e.optString("id", league + i);
            // Scoreboards put the status on the event, team schedules on the competition.
            JSONObject status = e.has("status") ? e.optJSONObject("status") : comp.optJSONObject("status");
            JSONObject type = status == null ? null : status.optJSONObject("type");
            m.state = type == null ? "pre" : type.optString("state", "pre");
            m.clock = type == null ? "" : type.optString("shortDetail", "");
            try {
                Date d = iso.parse(e.optString("date"));
                m.at = d == null ? 0 : d.getTime();
            } catch (java.text.ParseException ex) {
                continue;
            }
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t);
                JSONObject team = c.optJSONObject("team");
                String name = team == null ? "?" : team.optString("shortDisplayName", team.optString("displayName", "?"));
                String full = team == null ? "" : team.optString("displayName", name).toLowerCase(Locale.ROOT);
                for (String f : FAVORITES) m.favorite |= full.contains(f) || name.toLowerCase(Locale.ROOT).contains(f);
                // A plain string on scoreboards, {value, displayValue} on schedules.
                Object sc = c.opt("score");
                String score = sc instanceof JSONObject ? ((JSONObject) sc).optString("displayValue", "") : sc == null ? "" : sc.toString();
                if ("home".equals(c.optString("homeAway"))) {
                    m.home = name;
                    m.homeScore = score;
                } else {
                    m.away = name;
                    m.awayScore = score;
                }
            }
            if (m.home != null && m.away != null) out.add(m);
        }
        return out;
    }

    private static String build(List<Match> all) {
        long now = System.currentTimeMillis();
        List<Match> live = new ArrayList<>();
        for (Match m : all) if (m.state.equals("in")) live.add(m);
        // Favorites first, then the Champions League, then the leagues in LEAGUES' order.
        live.sort((a, b) -> rank(a) - rank(b));
        if (!live.isEmpty()) {
            StringBuilder sb = new StringBuilder(LIVE);
            for (int i = 0; i < Math.min(2, live.size()); i++) {
                Match m = live.get(i);
                if (i > 0) sb.append("     ");
                sb.append(m.home).append(' ').append(m.homeScore).append('–').append(m.awayScore).append(' ').append(m.away);
                if (!m.clock.isEmpty()) sb.append("  ").append(m.clock);
            }
            return sb.toString();
        }
        Match result = null, next = null;
        for (Match m : all) {
            if (!m.favorite) continue;
            if (m.state.equals("post") && now - m.at < RECENT + 2 * 3600_000L && (result == null || m.at > result.at)) result = m;
            if (m.state.equals("pre") && m.at > now && m.at - now < AHEAD && (next == null || m.at < next.at)) next = m;
        }
        if (result != null) {
            return "⚽ " + Ui.tr("FT") + "  " + result.home + " " + result.homeScore + "–" + result.awayScore + " " + result.away;
        }
        if (next != null) return "⚽ " + when(next.at) + "  " + next.home + " – " + next.away;
        return "";
    }

    private static int rank(Match m) {
        if (m.favorite) return 0;
        for (int i = 0; i < LEAGUES.length; i++) if (LEAGUES[i].equals(m.league)) return 1 + i;
        return LEAGUES.length + 1;
    }

    /** "Today 3:00 PM", "Tomorrow 3:00 PM", "Sat 3:00 PM". */
    private static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        int days = then.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
                + 365 * (then.get(Calendar.YEAR) - now.get(Calendar.YEAR));
        String day = days == 0 ? Ui.tr("Today") : days == 1 ? Ui.tr("Tomorrow")
                : new SimpleDateFormat("EEE", Ui.locale()).format(then.getTime());
        return day + " " + Ui.time(at);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 << 10];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 << 20) throw new IOException("too big");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
