package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's wallpaper (the wallpaper button at the top, WallpaperActivity): the picks' posters (as before), or
 * one photo of your choice, kept recognizable behind the home screen (Backdrop's "own" version: lightened by day,
 * darkened at night). The photo comes from the wallpaper bank (Picsum: Unsplash photos, free, no key) or from a phone
 * on the same Wi-Fi (Server, a small web page that is up only while the wallpaper screen is open). Either way it is
 * imported like your other photos (Photos.add): upright, cropped to 16:9, resized, without its GPS and other tags.
 */
final class Wallpaper {
    private Wallpaper() {}

    /** The chosen photo, or null: the picks' posters (or nothing chosen yet, or the photo was deleted). */
    static File chosen(Context c) {
        String name = Prefs.wallpaper();
        if (name.isEmpty() || name.contains("/")) return null;
        File f = new File(Photos.dir(c), name);
        return f.isFile() ? f : null;
    }

    // MARK: - The home screen's background

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static File built, building;
    private static Backdrop backdrop;

    /** The chosen photo ready to draw, or null (none chosen, or still being built: then `ready` runs once it is). */
    static synchronized Backdrop backdrop(Context c, Runnable ready) {
        File want = chosen(c);
        if (want == null) {
            backdrop = null;
            built = null;
            return null;
        }
        if (want.equals(built)) return backdrop;
        if (!want.equals(building)) {
            building = want;
            worker.execute(() -> {
                Backdrop b = null;
                try {
                    Bitmap src = BitmapFactory.decodeFile(want.getPath());
                    if (src != null) b = Backdrop.make(src, true, true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("wallpaper: " + want.getName() + ": " + e.getMessage());
                }
                synchronized (Wallpaper.class) {
                    building = null;
                    if (b == null) return;
                    backdrop = b;
                    built = want;
                }
                main.post(ready);
            });
        }
        return null;    // the previous wallpaper's backdrop isn't kept: HomeView keeps showing what it has
    }

    // MARK: - The wallpaper bank (picsum.photos: Unsplash photos, free to use, no key)

    static final class Photo {
        String id, author;
    }

    private static final String BANK = "https://picsum.photos";

    /** One page of the bank (landscape photos only), on the caller's thread. */
    static List<Photo> bank(int page) throws IOException {
        JSONArray list;
        try {
            list = new JSONArray(new String(get(BANK + "/v2/list?limit=40&page=" + page), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from the wallpaper bank");
        }
        List<Photo> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || o.optInt("width") < o.optInt("height") * 1.3f) continue;   // portraits crop badly to 16:9
            Photo p = new Photo();
            p.id = o.optString("id");
            p.author = o.optString("author");
            if (!p.id.isEmpty()) out.add(p);
        }
        return out;
    }

    /** A bank photo's thumbnail (16:9), decoded small. */
    static Bitmap thumbnail(Photo p, int w, int h) throws IOException {
        byte[] data = get(BANK + "/id/" + p.id + "/" + w + "/" + h + ".jpg");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) throw new IOException("unreadable thumbnail");
        return b;
    }

    /** Downloads a bank photo at the photos' size and imports it (Photos.add); on the caller's thread. */
    static File download(Context c, Photo p) throws IOException {
        File tmp = new File(c.getCacheDir(), "wallpaper-" + p.id + ".jpg");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(get(BANK + "/id/" + p.id + "/" + Photos.W + "/" + Photos.H + ".jpg"));
        }
        try {
            return Photos.add(c, tmp);
        } finally {
            tmp.delete();
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);   // picsum answers /id/… with a redirect to its CDN (same https host family)
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return readAll(in, 20 << 20);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 << 10];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("too big");
        }
        return out.toByteArray();
    }

    // MARK: - The screensaver button (ScreensaverActivity): your photos and the bank's, as a slideshow

    /** Bank photos kept for the slideshow (in the cache: Android may clear them, they come back). */
    private static final int SLIDES_KEPT = 20, SLIDES_PER_FETCH = 8;
    private static final ExecutorService slideWorker = Executors.newSingleThreadExecutor();
    private static boolean fetchingSlides;

    private static File slidesDir(Context c) {
        return new File(c.getCacheDir(), "slides");
    }

    /**
     * What the slideshow shows, in a fixed order (DreamView goes on from the photo it showed last): the wallpaper,
     * the bank photos already downloaded, then your other photos.
     */
    static List<Recs.Pick> slides(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        File chosen = chosen(c);
        if (chosen != null) out.add(slide("wallpaper", 0, chosen));
        File[] bank = slidesDir(c).listFiles((d, name) -> name.endsWith(".jpg"));
        if (bank != null) {
            java.util.Arrays.sort(bank);
            for (File f : bank) {
                try {
                    out.add(slide("bank", Integer.parseInt(f.getName().replace(".jpg", "")), f));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        List<File> mine = Photos.files(c);
        for (int i = 0; i < mine.size(); i++) if (!mine.get(i).equals(chosen)) out.add(slide("photo", i, mine.get(i)));
        return out;
    }

    private static Recs.Pick slide(String kind, int id, File f) {
        Recs.Pick p = new Recs.Pick();
        p.kind = kind;
        p.id = id;
        p.photo = f;
        return p;
    }

    /**
     * Downloads a few more bank photos for the slideshow, in the background (a random page each time, so it changes);
     * `done` runs on the main thread after each one, so the slideshow can start as soon as the first is there.
     */
    static void fetchSlides(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        synchronized (Wallpaper.class) {
            if (fetchingSlides) return;
            fetchingSlides = true;
        }
        slideWorker.execute(() -> {
            try {
                File d = slidesDir(app);
                if (!d.isDirectory() && !d.mkdirs()) return;
                List<Photo> page = bank(1 + new java.util.Random().nextInt(25));
                Collections.shuffle(page);
                int got = 0;
                for (Photo p : page) {
                    if (got >= SLIDES_PER_FETCH) break;
                    File f = new File(d, p.id + ".jpg");
                    if (f.exists()) continue;
                    File part = new File(d, p.id + ".part");
                    try (OutputStream os = new FileOutputStream(part)) {
                        os.write(get(BANK + "/id/" + p.id + "/" + Photos.W + "/" + Photos.H + ".jpg"));
                    } catch (IOException e) {
                        part.delete();
                        continue;
                    }
                    if (!part.renameTo(f)) {
                        part.delete();
                        continue;
                    }
                    got++;
                    trimSlides(d);
                    main.post(done);
                }
                AppLog.w("slides: " + got + " new bank photos");
            } catch (IOException | RuntimeException e) {
                AppLog.w("slides: " + e.getMessage());
            } finally {
                synchronized (Wallpaper.class) {
                    fetchingSlides = false;
                }
            }
        });
    }

    /** Keeps the newest SLIDES_KEPT. */
    private static void trimSlides(File d) {
        File[] all = d.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (all == null || all.length <= SLIDES_KEPT) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < all.length - SLIDES_KEPT; i++) all[i].delete();
    }

    // MARK: - From a phone on the same Wi-Fi

    /** The TV's address on the local network (Wi-Fi or Ethernet), or null. */
    static String localAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    interface Listener {
        /** On the main thread: a photo came from the phone and was imported. */
        void onReceived(File photo);
    }

    /**
     * A tiny web server for the phone: GET /<pin> is the page (pick photos from the gallery), POST /<pin>/photo sends
     * one. Only the address with the right 4-digit pin answers, and only while the wallpaper screen is open.
     */
    static final class Server {
        static final int PORT = 8080;
        private static final int MAX_PHOTO = 40 << 20;

        final String pin = String.format(Locale.US, "%04d", new SecureRandom().nextInt(10_000));
        private final Context app;
        private final Listener listener;
        private ServerSocket socket;
        private int port;

        Server(Context c, Listener l) {
            app = c.getApplicationContext();
            listener = l;
        }

        /** The address to type on the phone, or null when the TV has no local address or the server didn't start. */
        String url() {
            String ip = localAddress();
            return ip == null || socket == null ? null : "http://" + ip + (port == 80 ? "" : ":" + port) + "/" + pin;
        }

        void start() {
            if (socket != null) return;
            for (int p : new int[] {PORT, PORT + 1, 0}) {
                try {
                    socket = new ServerSocket(p);
                    port = socket.getLocalPort();
                    break;
                } catch (IOException ignored) {
                }
            }
            if (socket == null) return;
            ServerSocket s = socket;
            Thread t = new Thread(() -> {
                while (!s.isClosed()) {
                    try (Socket client = s.accept()) {
                        client.setSoTimeout(30_000);
                        handle(client);
                    } catch (IOException | RuntimeException e) {
                        if (!s.isClosed()) AppLog.w("wallpaper server: " + e.getMessage());
                    }
                }
            }, "wallpaper-server");
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }

        private void handle(Socket client) throws IOException {
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            String request = line(in);
            if (request == null) return;
            long length = 0;
            for (String h; (h = line(in)) != null && !h.isEmpty(); ) {
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        length = Long.parseLong(h.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String[] parts = request.split(" ");
            String method = parts[0], path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (method.equals("GET") && path.equals("/" + pin)) {
                reply(out, 200, "text/html; charset=utf-8", page().getBytes(StandardCharsets.UTF_8));
            } else if (method.equals("POST") && path.equals("/" + pin + "/photo")) {
                if (length <= 0 || length > MAX_PHOTO) {
                    reply(out, 413, "text/plain", "too big".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                File tmp = new File(app.getCacheDir(), "upload-" + System.nanoTime());
                try {
                    try (OutputStream os = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 << 10];
                        long left = length;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("cut short");
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    File photo = Photos.add(app, tmp);
                    main.post(() -> listener.onReceived(photo));
                    reply(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    reply(out, 422, "text/plain", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
                } finally {
                    tmp.delete();
                }
            } else {
                reply(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        }

        private static String line(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int c; (c = in.read()) >= 0; ) {
                if (c == '\n') return sb.toString();
                if (c != '\r') sb.append((char) c);
                if (sb.length() > 8192) throw new IOException("header too long");
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        private static void reply(OutputStream out, int code, String type, byte[] body) throws IOException {
            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                    + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        }

        /** The phone's page: pick photos, they're sent one at a time; the last one becomes the wallpaper. */
        private String page() {
            return "<!doctype html><html><head><meta charset=utf-8>"
                    + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Free My TV</title><style>"
                    + "body{margin:0;font:17px -apple-system,system-ui,sans-serif;background:#f2f2f7;color:#1c1c1e;"
                    + "display:flex;min-height:100vh;align-items:center;justify-content:center}"
                    + "@media(prefers-color-scheme:dark){body{background:#000;color:#f2f2f7}.card{background:#1c1c1e!important}}"
                    + ".card{background:#fff;border-radius:22px;padding:28px 24px;margin:16px;max-width:420px;width:100%;"
                    + "box-sizing:border-box;text-align:center}h1{font-size:24px;margin:0 0 8px}p{color:#8e8e93;margin:0 0 22px}"
                    + "label{display:block;background:#0a84ff;color:#fff;border-radius:14px;padding:16px;font-weight:600}"
                    + "input{display:none}#s{margin-top:18px;min-height:1.4em}</style></head><body><div class=card>"
                    + "<h1>Wallpaper</h1><p>Pick photos from your gallery: they go to the TV, and the last one becomes"
                    + " the home screen's wallpaper.</p><label>Choose photos<input id=f type=file accept='image/*'"
                    + " multiple></label><div id=s></div></div><script>"
                    + "const s=document.getElementById('s');"
                    + "document.getElementById('f').onchange=async e=>{const fs=[...e.target.files];let ok=0;"
                    + "for(let i=0;i<fs.length;i++){s.textContent='Sending '+(i+1)+' / '+fs.length+'…';"
                    + "try{const r=await fetch(location.pathname+'/photo',{method:'POST',body:fs[i]});if(r.ok)ok++;}catch(x){}}"
                    + "s.textContent=ok?('✓ '+ok+' sent. Look at the TV.'):'Nothing could be sent. Same Wi-Fi as the TV?';"
                    + "e.target.value='';};</script></body></html>";
        }
    }
}
