package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/**
 * Fire TV's Home Redirect (io.firehome.redirect: the accessibility service that opens this home screen over
 * Amazon's) also runs the sleep timer and the phone remote, since it may act on the TV from the background. This reads
 * its state (StateProvider) and sets its timer (the SLEEP broadcast). Without it, both say nothing.
 */
final class Redirect {
    private Redirect() {}

    static final String PKG = "io.firehome.redirect";
    private static final Uri STATE = Uri.parse("content://io.firehome.redirect.state");

    /** The phone remote's address, or null (no Home Redirect, or no network). */
    static String remoteUrl(Context c) {
        try (Cursor q = c.getContentResolver().query(STATE, null, null, null, null)) {
            return q != null && q.moveToFirst() ? q.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Minutes until the TV sleeps (rounded up), 0 when no timer, -1 without Home Redirect. */
    static int sleepMinutes(Context c) {
        try (Cursor q = c.getContentResolver().query(STATE, null, null, null, null)) {
            if (q == null || !q.moveToFirst()) return -1;
            long at = q.getLong(1);
            return at <= 0 ? 0 : (int) ((at - System.currentTimeMillis() + 59_999) / 60_000);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static void setSleep(Context c, int minutes) {
        c.sendBroadcast(new Intent("io.firehome.redirect.SLEEP").setPackage(PKG).putExtra("minutes", minutes));
    }
}
