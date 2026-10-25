package io.freemytv;

import android.app.Activity;
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
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallpaper button's screen: the picks' posters or one of your photos behind the home screen (OK picks one; a long
 * press on a photo deletes it), photos sent from a phone on the same Wi-Fi (Wallpaper.Server, up only while this
 * screen is open), and the wallpaper bank (Picsum). Picking one goes back to the home screen with it.
 */
public class WallpaperActivity extends Activity implements Wallpaper.Listener {
    private static final int COLUMNS = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private Wallpaper.Server server;
    private float k;
    private boolean dark, busy;
    private int ink, dim, card;
    private LinearLayout mine, bank;
    private View moreTile;
    /** Picsum's list starts with a run of laptop photos: a random page in the first two dozen is more varied. */
    private int bankPage = 2 + new java.util.Random().nextInt(23);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        dark = isDark();
        ink = dark ? 0xFFF2F2F7 : 0xFF1C1C1E;
        dim = dark ? 0xFF98989D : 0xFF6E6E73;
        card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(80), px(56), px(80), px(80));
        col.addView(text(Ui.tr("Wallpaper & screensaver"), Ui.semibold, 56, ink));

        col.addView(label(Ui.tr("Current")));
        mine = row();
        col.addView(scroller(mine));
        fillMine();

        col.addView(label(Ui.tr("From your phone")));
        col.addView(phoneCard());

        col.addView(label(Ui.tr("Options")));
        LinearLayout options = row();
        options.addView(toggle(Ui.tr("Screensaver after 3 min on the home screen"), Prefs::autoSaver, Prefs::setAutoSaver));
        options.addView(toggle(Ui.tr("Seasonal effects (Halloween, snow…)"), Prefs::seasonal, Prefs::setSeasonal));
        col.addView(scroller(options));

        col.addView(label(Ui.tr("Wallpaper bank")));
        bank = new LinearLayout(this);
        bank.setOrientation(LinearLayout.VERTICAL);
        col.addView(bank);
        loadBank();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(col);
        scroll.setBackgroundColor(dark ? 0xFF000000 : 0xFFF2F2F7);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (server == null) server = new Wallpaper.Server(this, this);
        server.start();
        updatePhoneUrl();
    }

    @Override protected void onPause() {
        super.onPause();
        if (server != null) server.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
        downloads.shutdownNow();
    }

    /** Light or dark like the home screen: the theme setting, or by the clock when it's automatic. */
    private static boolean isDark() {
        String t = Prefs.theme();
        if (!t.equals("auto")) return t.equals("dark");
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 19 || hour < 7;
    }

    // MARK: - Current: the posters and your photos

    private void fillMine() {
        mine.removeAllViews();
        String chosen = Prefs.wallpaper();
        FrameLayout posters = tile(null, null, chosen.isEmpty(), v -> choose(""));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(Gravity.CENTER);
        for (TextView line : new TextView[] {text(Ui.tr("Posters"), Ui.semibold, 32, ink),
                text(Ui.tr("The picks' posters, as before"), Ui.medium, 22, dim)}) {
            line.setGravity(Gravity.CENTER);
            words.addView(line);
        }
        posters.addView(words, 1, new FrameLayout.LayoutParams(-1, -1));
        mine.addView(posters);
        View focus = posters;
        List<File> photos = Photos.files(this);
        for (int i = photos.size() - 1; i >= 0; i--) {   // newest first
            File f = photos.get(i);
            FrameLayout t = tile(null, null, f.getName().equals(chosen), v -> choose(f.getName()));
            t.setOnLongClickListener(v -> {
                remove(f);
                return true;
            });
            mine.addView(t);
            loadInto(t, () -> decodeSmall(f));
            if (f.getName().equals(chosen)) focus = t;
        }
        focus.requestFocus();   // on the current wallpaper
    }

    private void choose(String name) {
        Prefs.setWallpaper(name);
        finish();
    }

    private void remove(File f) {
        if (f.getName().equals(Prefs.wallpaper())) Prefs.setWallpaper("");
        Photos.remove(f);
        Toast.makeText(this, Ui.tr("Photo deleted."), Toast.LENGTH_SHORT).show();
        fillMine();
        if (mine.getChildCount() > 0) mine.getChildAt(0).requestFocus();
    }

    private Bitmap decodeSmall(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 4;     // 2112 × 1188 → 528 × 297
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    // MARK: - From your phone

    private TextView phoneUrl;

    private View phoneCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(px(36), px(30), px(36), px(30));
        box.setBackground(round(card, 28, 0, 0));
        ImageView icon = new ImageView(this);
        Bitmap phone = Symbols.get(this, "phone");
        if (phone != null) icon.setImageBitmap(phone);
        icon.setColorFilter(0xFF0A84FF);
        box.addView(icon, new LinearLayout.LayoutParams(px(64), px(64)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(px(30), 0, 0, 0);
        lines.addView(text(Ui.tr("On your phone, on the same Wi-Fi, scan the code or open this address, then choose your photos:"),
                Ui.medium, 26, dim));
        phoneUrl = text("…", Ui.semibold, 44, ink);
        lines.addView(phoneUrl);
        lines.addView(text(Ui.tr("The last photo you send becomes the wallpaper. The address works only while this screen is open."),
                Ui.regular, 22, dim));
        box.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        phoneQr = new ImageView(this);
        box.addView(phoneQr, new LinearLayout.LayoutParams(px(200), px(200)));
        return box;
    }

    private ImageView phoneQr;

    private void updatePhoneUrl() {
        String url = server == null ? null : server.url();
        phoneUrl.setText(url != null ? url : Ui.tr("The TV isn't on a network."));
        phoneQr.setImageBitmap(url == null ? null : QrCode.bitmap(url, px(200)));
    }

    @Override public void onReceived(File photo) {
        Prefs.setWallpaper(photo.getName());
        Toast.makeText(this, Ui.tr("Photo received: it's the wallpaper now."), Toast.LENGTH_SHORT).show();
        fillMine();
    }

    // MARK: - The wallpaper bank

    private void loadBank() {
        if (moreTile != null) {
            ((ViewGroup) moreTile.getParent()).removeView(moreTile);
            moreTile = null;
        }
        int page = bankPage++;
        loader.execute(() -> {
            List<Wallpaper.Photo> list;
            try {
                list = Wallpaper.bank(page);
            } catch (IOException e) {
                main.post(() -> {
                    if (isFinishing()) return;
                    TextView err = text(Ui.tr("The wallpaper bank isn't reachable right now."), Ui.medium, 24, dim);
                    bank.addView(err);
                });
                return;
            }
            main.post(() -> {
                if (isFinishing()) return;
                for (Wallpaper.Photo p : list) addBankTile(tile(null, null, false, v -> pick(p, v)), p);
                moreTile = tile(null, Ui.tr("More"), false, v -> loadBank());
                addToGrid(moreTile);
            });
        });
    }

    private void addBankTile(FrameLayout t, Wallpaper.Photo p) {
        addToGrid(t);
        loadInto(t, () -> {
            try {
                return Wallpaper.thumbnail(p, 384, 216);
            } catch (IOException e) {
                return null;
            }
        });
    }

    private void addToGrid(View t) {
        LinearLayout last = bank.getChildCount() == 0 ? null : (LinearLayout) bank.getChildAt(bank.getChildCount() - 1);
        if (last == null || last.getChildCount() >= COLUMNS) {
            last = row();
            last.setPadding(0, 0, 0, px(8));
            bank.addView(last);
        }
        last.addView(t);
    }

    private void pick(Wallpaper.Photo p, View tile) {
        if (busy) return;
        busy = true;
        TextView status = text(Ui.tr("Downloading…"), Ui.semibold, 26, Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0x99000000);
        ((FrameLayout) tile).addView(status, new FrameLayout.LayoutParams(-1, -1));
        downloads.execute(() -> {
            try {
                File f = Wallpaper.download(this, p);
                main.post(() -> choose(f.getName()));
            } catch (IOException e) {
                main.post(() -> {
                    busy = false;
                    ((FrameLayout) tile).removeView(status);
                    Toast.makeText(this, Ui.tr("Couldn't download this wallpaper."), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // MARK: - Views

    interface Getter { boolean get(); }
    interface Setter { void set(boolean on); }

    /** A pill with a setting's name and On/Off; OK switches it. */
    private View toggle(String name, Getter get, Setter set) {
        TextView t = text("", Ui.semibold, 26, ink);
        t.setPadding(px(34), px(22), px(34), px(22));
        t.setFocusable(true);
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, px(14), px(28), px(14));
        t.setLayoutParams(lp);
        Runnable show = () -> {
            boolean on = get.get();
            t.setText(name + "   " + (on ? Ui.tr("On") : Ui.tr("Off")));
            t.setBackground(round(t.hasFocus() ? (dark ? 0xFFF2F2F7 : 0xFF1C1C1E) : card, 40, 0, 0));
            t.setTextColor(t.hasFocus() ? (dark ? 0xFF1C1C1E : 0xFFF2F2F7) : on ? ink : dim);
        };
        t.setOnClickListener(v -> {
            set.set(!get.get());
            show.run();
        });
        t.setOnFocusChangeListener((v, has) -> show.run());
        show.run();
        return t;
    }

    interface Source { Bitmap load(); }

    /** Loads a picture in the background into the tile's image. */
    private void loadInto(FrameLayout t, Source s) {
        ImageView img = (ImageView) t.getChildAt(0);
        loader.execute(() -> {
            Bitmap b = s.load();
            if (b != null) main.post(() -> img.setImageBitmap(b));
        });
    }

    /** A 16:9 rounded tile: its picture, an optional label, a check when chosen; focus zooms it and outlines it. */
    private FrameLayout tile(Bitmap image, String label, boolean selected, View.OnClickListener click) {
        FrameLayout t = new FrameLayout(this);
        int w = px(384), h = px(216);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(0, px(14), px(28), px(14));
        t.setLayoutParams(lp);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setBackground(round(dark ? 0xFF2C2C2E : 0xFFDADADF, 22, 0, 0));
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(22));
            }
        });
        t.setClipToOutline(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (image != null) img.setImageBitmap(image);
        t.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (label != null) {
            TextView l = text(label, Ui.semibold, 30, ink);
            l.setGravity(Gravity.CENTER);
            l.setPadding(0, 0, 0, px(48));
            t.addView(l, new FrameLayout.LayoutParams(-1, -1));
        }
        if (selected) {
            TextView check = text("✓", Ui.semibold, 30, Color.WHITE);
            check.setGravity(Gravity.CENTER);
            check.setBackground(round(0xFF0A84FF, 24, 0, 0));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(48), px(48), Gravity.TOP | Gravity.END);
            cp.setMargins(0, px(14), px(14), 0);
            t.addView(check, cp);
        }
        View ring = new View(this);
        ring.setBackground(round(0, 22, 5, dark ? Color.WHITE : 0xFF1C1C1E));
        ring.setVisibility(View.INVISIBLE);
        t.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        t.setOnFocusChangeListener((v, has) -> {
            ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            v.animate().scaleX(has ? 1.07f : 1).scaleY(has ? 1.07f : 1).translationZ(has ? px(12) : 0)
                    .setDuration(150).start();
        });
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(px(6), 0, px(40), 0);
        return r;
    }

    private HorizontalScrollView scroller(LinearLayout content) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.addView(content);
        return h;
    }

    private TextView label(String s) {
        TextView t = text(s, Ui.semibold, 30, dim);
        t.setPadding(0, px(40), 0, px(4));
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
