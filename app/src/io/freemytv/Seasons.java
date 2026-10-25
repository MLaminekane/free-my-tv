package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.View;

import java.util.Calendar;
import java.util.Random;

/**
 * Seasonal effects over the home screen (Prefs.seasonal, the wallpaper screen's options): falling leaves and now and
 * then a bat around Halloween (October 20 to November 1), snow in winter (December 1 to January 6). Out of season it
 * draws nothing and runs nothing. Light on purpose: at most 40 particles, 30 frames a second, only while the home
 * screen shows; it never takes the focus or a key.
 */
final class Seasons extends View implements Choreographer.FrameCallback {
    private static final int NONE = 0, HALLOWEEN = 1, WINTER = 2;

    private static final class Particle {
        float x, y, vx, vy, size, spin, angle, phase;
        Bitmap image;
        float alpha;
    }

    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Particle[] particles = new Particle[0];
    private Particle bat;
    private Bitmap[] leaves;
    private Bitmap batImage;
    private int season = NONE;
    private boolean running, skip;
    private long lastNanos;

    Seasons(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static int season() {
        Calendar now = Calendar.getInstance();
        int month = now.get(Calendar.MONTH), day = now.get(Calendar.DAY_OF_MONTH);
        if ((month == Calendar.OCTOBER && day >= 20) || (month == Calendar.NOVEMBER && day <= 1)) return HALLOWEEN;
        if (month == Calendar.DECEMBER || (month == Calendar.JANUARY && day <= 6)) return WINTER;
        return NONE;
    }

    void start() {
        season = Prefs.seasonal() ? season() : NONE;
        setVisibility(season == NONE ? GONE : VISIBLE);
        if (season == NONE || running) return;
        running = true;
        lastNanos = 0;
        if (getWidth() > 0) reset();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (season != NONE) reset();
    }

    private void reset() {
        float k = getWidth() / 1920f;
        if (season == HALLOWEEN && leaves == null) {
            leaves = new Bitmap[] {emoji("🍂", 44 * k), emoji("🍁", 40 * k), emoji("🍂", 34 * k)};
            batImage = emoji("🦇", 64 * k);
        }
        int n = season == WINTER ? 40 : 18;
        particles = new Particle[n];
        for (int i = 0; i < n; i++) {
            particles[i] = spawn(k);
            particles[i].y = random.nextFloat() * getHeight();   // spread over the screen at first
        }
        bat = null;
    }

    private Particle spawn(float k) {
        Particle p = new Particle();
        p.x = random.nextFloat() * getWidth();
        p.y = -60 * k;
        p.phase = random.nextFloat() * 6.28f;
        if (season == WINTER) {
            p.size = (3 + random.nextFloat() * 5) * k;
            p.vy = (40 + random.nextFloat() * 60) * k;
            p.vx = (random.nextFloat() - 0.5f) * 20 * k;
            p.alpha = 0.45f + random.nextFloat() * 0.45f;
        } else {
            p.image = leaves[random.nextInt(leaves.length)];
            p.vy = (45 + random.nextFloat() * 50) * k;
            p.vx = (random.nextFloat() - 0.3f) * 40 * k;
            p.spin = (random.nextFloat() - 0.5f) * 120;
            p.angle = random.nextFloat() * 360;
            p.alpha = 0.7f + random.nextFloat() * 0.25f;
        }
        return p;
    }

    @Override public void doFrame(long nanos) {
        if (!running) return;
        Choreographer.getInstance().postFrameCallback(this);
        skip = !skip;
        if (skip) return;   // 30 frames a second is plenty for drifting leaves and snow
        float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        float k = getWidth() / 1920f, t = nanos / 1e9f;
        for (int i = 0; i < particles.length; i++) {
            Particle p = particles[i];
            p.y += p.vy * dt;
            p.x += (p.vx + (float) Math.sin(t * 0.8f + p.phase) * 25 * k) * dt;
            p.angle += p.spin * dt;
            if (p.y > getHeight() + 60 * k || p.x < -80 * k || p.x > getWidth() + 80 * k) particles[i] = spawn(k);
        }
        if (season == HALLOWEEN) {
            if (bat == null && random.nextFloat() < dt / 25f) {   // about one bat every 25 seconds
                bat = new Particle();
                boolean left = random.nextBoolean();
                bat.x = left ? -80 * k : getWidth() + 80 * k;
                bat.y = (120 + random.nextFloat() * 300) * k;
                bat.vx = (left ? 1 : -1) * (260 + random.nextFloat() * 120) * k;
                bat.phase = random.nextFloat() * 6.28f;
                bat.alpha = 0.9f;
            }
            if (bat != null) {
                bat.x += bat.vx * dt;
                bat.y += (float) Math.sin(t * 6 + bat.phase) * 60 * k * dt;
                if (bat.x < -100 * k || bat.x > getWidth() + 100 * k) bat = null;
            }
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        for (Particle p : particles) {
            paint.setAlpha(Math.round(255 * p.alpha));
            if (p.image == null) {
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha(Math.round(255 * p.alpha));
                c.drawCircle(p.x, p.y, p.size, paint);
            } else {
                c.save();
                c.rotate(p.angle, p.x, p.y);
                c.drawBitmap(p.image, p.x - p.image.getWidth() / 2f, p.y - p.image.getHeight() / 2f, paint);
                c.restore();
            }
        }
        if (bat != null && batImage != null) {
            paint.setAlpha(Math.round(255 * bat.alpha));
            c.save();
            if (bat.vx < 0) c.scale(-1, 1, bat.x, bat.y);   // facing the way it flies
            c.drawBitmap(batImage, bat.x - batImage.getWidth() / 2f, bat.y - batImage.getHeight() / 2f, paint);
            c.restore();
        }
    }

    /** An emoji drawn once into a bitmap (the TV's color emoji font). */
    private static Bitmap emoji(String s, float px) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(px);
        int size = Math.round(px * 1.3f);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(s, (size - p.measureText(s)) / 2, (size - fm.ascent - fm.descent) / 2, p);
        return b;
    }
}
