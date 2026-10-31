package com.aether.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private WebView web;
    private static final int PICK = 7;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void js(String s) { web.evaluateJavascript(s, null); }

    @Override protected void onResume() { super.onResume(); hideBars(); web.requestFocus(); js("window.refreshApps&&refreshApps()"); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); js("window.goHome&&goHome()"); }
    @Override public void onBackPressed() { js("window.onBack&&onBack()"); }

    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (r != PICK) return;
        boolean ok = c == RESULT_OK && d != null && d.getData() != null && saveWp(d.getData());
        js("window.onWp&&onWp(" + ok + ")");
    }

    private boolean saveWp(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, o); in.close();
            int ss = 1;
            while (Math.max(o.outWidth, o.outHeight) / ss > 2400) ss *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = ss;
            in = getContentResolver().openInputStream(u);
            Bitmap b = BitmapFactory.decodeStream(in, null, o); in.close();
            if (b == null) return false;
            int rot = 0;
            if (Build.VERSION.SDK_INT >= 24) {
                in = getContentResolver().openInputStream(u);
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                in.close();
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            }
            Matrix m = new Matrix();
            if (rot != 0) m.postRotate(rot);
            float sc = Math.min(1f, 1920f / Math.max(b.getWidth(), b.getHeight()));
            m.postScale(sc, sc);
            Bitmap r2 = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            FileOutputStream f = new FileOutputStream(new File(getFilesDir(), "wp.jpg"));
            r2.compress(Bitmap.CompressFormat.JPEG, 82, f); f.close();
            return true;
        } catch (Throwable t) { return false; }
    }

    class Bridge {
        @JavascriptInterface public String getApps() {
            PackageManager pm = getPackageManager();
            Map<String, String> out = new LinkedHashMap<>();
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String c : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(c);
                for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
                    out.put(pkg, String.valueOf(r.loadLabel(pm)));
                }
            }
            List<Map.Entry<String, String>> l = new ArrayList<>(out.entrySet());
            Collections.sort(l, new Comparator<Map.Entry<String, String>>() {
                public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) { return a.getValue().compareToIgnoreCase(b.getValue()); }
            });
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"p\":\"").append(l.get(i).getKey()).append("\",\"n\":\"")
                  .append(l.get(i).getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}");
            }
            return sb.append(']').toString();
        }

        @JavascriptInterface public String getIcon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                Bitmap bm = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm);
                d.setBounds(0, 0, 128, 128);
                d.draw(c);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 100, o);
                bm.recycle();
                return "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public void launch(String pkg) {
            PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        }

        @JavascriptInterface public void openSettings() {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }

        @JavascriptInterface public void pickWallpaper() {
            runOnUiThread(new Runnable() { public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, PICK);
                } catch (Exception e) { js("window.onWp&&onWp(false)"); }
            }});
        }

        @JavascriptInterface public String getWallpaperData() {
            try {
                File f = new File(getFilesDir(), "wp.jpg");
                if (!f.exists()) return "";
                byte[] buf = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                int n = 0;
                while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
                in.close();
                return "data:image/jpeg;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
    }
}
