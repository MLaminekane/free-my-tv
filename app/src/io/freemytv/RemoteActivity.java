package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The remote button's screen: the phone remote's QR code and address (Home Redirect's Remote page: Home, Back, the
 * TV's apps, the screensaver, the sleep timer, from a phone on the same Wi-Fi). Any key closes it.
 */
public class RemoteActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        boolean dark = !"light".equals(Prefs.theme());
        int ink = dark ? 0xFFF2F2F7 : 0xFF1C1C1E, dim = dark ? 0xFF98989D : 0xFF6E6E73;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(dark ? 0xFF000000 : 0xFFF2F2F7);

        String url = Redirect.remoteUrl(this);
        col.addView(text(Ui.tr("Phone remote"), Ui.semibold, 56 * k, ink));
        if (url == null) {
            col.addView(text(Ui.tr("Not available: Home Redirect isn't on, or the TV isn't on a network."), Ui.medium, 28 * k, dim));
        } else {
            TextView how = text(Ui.tr("Scan the code with your phone's camera (same Wi-Fi as the TV)."), Ui.medium, 28 * k, dim);
            how.setPadding(0, Math.round(16 * k), 0, Math.round(36 * k));
            col.addView(how);
            ImageView qr = new ImageView(this);
            Bitmap b = QrCode.bitmap(url, Math.round(420 * k));
            qr.setImageBitmap(b);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFFFFFF);
            bg.setCornerRadius(28 * k);
            qr.setBackground(bg);
            qr.setPadding(Math.round(16 * k), Math.round(16 * k), Math.round(16 * k), Math.round(16 * k));
            col.addView(qr, new LinearLayout.LayoutParams(Math.round(452 * k), Math.round(452 * k)));
            TextView link = text(url, Ui.semibold, 36 * k, ink);
            link.setPadding(0, Math.round(30 * k), 0, Math.round(8 * k));
            col.addView(link);
            col.addView(text(Ui.tr("Home, Back, your apps, the screensaver and the sleep timer, from the couch. Keep it on your phone's home screen."),
                    Ui.regular, 24 * k, dim));
        }
        setContentView(col);
    }

    private TextView text(String s, android.graphics.Typeface face, float px, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        return t;
    }

    /** The arrows, OK or Back close it (volume and the other keys go on to the system). */
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
