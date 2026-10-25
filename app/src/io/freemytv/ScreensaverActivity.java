package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The screensaver button: the screensaver right now, with your photos and the wallpaper bank's (DreamView in its
 * wallpapers mode), whatever the system's screensaver is. On Fire OS, Amazon's screensaver replaces any other one
 * (AmbientManagerService), so this is the way to see it there. The screen stays on while it shows (Amazon's
 * screensaver doesn't come over it); like the real one, the arrows, OK or Back close it.
 */
public class ScreensaverActivity extends Activity {
    /** Started by the home screen after a while idle (HomeActivity), not by the button. */
    static final String EXTRA_AUTO = "auto";
    /**
     * The screen is kept on this long at most (started by itself: 1 hour; by the button: 4 hours); then the TV's
     * own timers apply again (Amazon's screensaver, then sleep), so a forgotten TV still goes to sleep.
     */
    private static final long AUTO_KEEP_MS = 60 * 60_000L, BUTTON_KEEP_MS = 4 * 60 * 60_000L;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable release = () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    private DreamView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DreamView(this, true);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(release);
        handler.postDelayed(release, getIntent().getBooleanExtra(EXTRA_AUTO, false) ? AUTO_KEEP_MS : BUTTON_KEEP_MS);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(release);
        view.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
