package com.webtoapk.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.webkit.JavascriptInterface;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    WebView web;
    SwipeRefreshLayout swipe;
    View splash;
    JSONObject cfg;
    String startUrl, baseHost;
    ValueCallback<Uri[]> fileCb;
    PermissionRequest pendingPerm;
    ObjectAnimator pulse;
    volatile String curHost = "";
    boolean pageReady = false, minPassed = false, splashDone = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { cfg = new JSONObject(readAsset("config.json")); } catch (Exception e) { cfg = new JSONObject(); }
        startUrl = cfg.optString("url", "https://example.com");
        String h = Uri.parse(startUrl).getHost();
        baseHost = h == null ? "" : h.replaceFirst("^www\\.", "");
        int color = Color.parseColor(cfg.optString("color", "#7c5cff"));

        String ori = cfg.optString("ori", "auto");
        if (ori.equals("portrait")) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else if (ori.equals("landscape")) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        if (cfg.optBoolean("ss", false))
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(color);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.addView(web, new ViewGroup.LayoutParams(-1, -1));
        swipe.setEnabled(cfg.optBoolean("ptr", true));
        swipe.setColorSchemeColors(color);
        swipe.setOnRefreshListener(() -> {
            String u = web.getUrl();
            if (u == null || u.startsWith("data:")) web.loadUrl(startUrl); else web.reload();
        });
        root.addView(swipe, new FrameLayout.LayoutParams(-1, -1));
        splash = buildSplash(color);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        setupWebView();
        if (cfg.optBoolean("push", false)) {
            if (Build.VERSION.SDK_INT >= 33)
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 13);
            scheduleFeed();
            web.addJavascriptInterface(new Bridge(), "AndroidApp");
        }

        splash.postDelayed(() -> { minPassed = true; maybeHide(); }, 1600);
        splash.postDelayed(() -> { pageReady = true; minPassed = true; maybeHide(); }, 9000);
        web.loadUrl(pickUrl(getIntent()));
    }

    void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(cfg.optBoolean("js", true));
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        if (cfg.optString("ua", "default").equals("desktop"))
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        s.setCacheMode(cfg.optBoolean("off", true) && !online() ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (!cfg.optBoolean("dl", true)) return;
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) r.addRequestHeader("Cookie", ck);
                if (ua != null) r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return handle(r.getUrl()); }
            @Override public void onPageFinished(WebView v, String u) { try { String hh = Uri.parse(u).getHost(); curHost = hh == null ? "" : hh.replaceFirst("^www\\.", ""); } catch (Exception e) { } swipe.setRefreshing(false); pageReady = true; maybeHide(); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadData("<html><body style='font-family:sans-serif;text-align:center;padding-top:30vh'><h3>No connection</h3><p>Pull down to retry</p></body></html>", "text/html", "UTF-8");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    if (!cfg.optBoolean("cam", false)) { req.deny(); return; }
                    ArrayList<String> need = new ArrayList<>();
                    for (String r : req.getResources()) {
                        if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
                        if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (need.isEmpty()) req.grant(req.getResources());
                    else { pendingPerm = req; requestPermissions(need.toArray(new String[0]), 11); }
                });
            }
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), 12); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
    }


    public class Bridge {
        @JavascriptInterface
        public void showNotification(String title, String body) {
            if (curHost.equals(baseHost) || curHost.endsWith("." + baseHost)) FeedJob.show(MainActivity.this, title, body, "");
        }
    }

    void scheduleFeed() {
        if (cfg.optString("feed", "").isEmpty()) return;
        try {
            JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == 101) return;
            JobInfo ji = new JobInfo.Builder(101, new ComponentName(this, FeedJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
            js.schedule(ji);
        } catch (Exception ignored) { }
    }

    String pickUrl(Intent i) {
        String u = i == null ? null : i.getStringExtra("url");
        if (u != null && u.startsWith("http")) {
            String h = Uri.parse(u).getHost();
            if (h != null) {
                h = h.replaceFirst("^www\\.", "");
                if (h.equals(baseHost) || h.endsWith("." + baseHost)) return u;
            }
        }
        return startUrl;
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String u = pickUrl(i);
        if (!u.equals(startUrl)) web.loadUrl(u);
    }

    boolean has(String p) { return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return true; }
    }

    boolean handle(Uri u) {
        String sch = u.getScheme();
        if (sch == null) return false;
        if (sch.equals("http") || sch.equals("https")) {
            String h = u.getHost();
            if (cfg.optBoolean("ext", true) && h != null && !baseHost.isEmpty()) {
                String x = h.replaceFirst("^www\\.", "");
                if (!x.equals(baseHost) && !x.endsWith("." + baseHost)) { open(u); return true; }
            }
            return false;
        }
        open(u);
        return true;
    }

    void open(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
    }

    View buildSplash(int color) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(color);
        ImageView iv = new ImageView(this);
        iv.setImageResource(getApplicationInfo().icon);
        int sz = (int) (110 * getResources().getDisplayMetrics().density);
        l.addView(iv, new LinearLayout.LayoutParams(sz, sz));
        TextView t = new TextView(this);
        t.setText(getApplicationInfo().loadLabel(getPackageManager()));
        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        t.setTextColor(lum > 0.7 ? Color.BLACK : Color.WHITE);
        t.setTextSize(22);
        t.setPadding(0, 40, 0, 0);
        l.addView(t);
        String type = cfg.optString("splash", "fade");
        animateIn(iv, type, true);
        animateIn(t, type, false);
        return l;
    }

    void animateIn(View v, String type, boolean main) {
        v.setAlpha(0f);
        switch (type) {
            case "zoom":
                v.setScaleX(.5f); v.setScaleY(.5f);
                v.animate().alpha(1).scaleX(1).scaleY(1).setDuration(700).start();
                break;
            case "slide-up":
                v.setTranslationY(200);
                v.animate().alpha(1).translationY(0).setDuration(700).start();
                break;
            case "pulse":
                v.animate().alpha(1).setDuration(300).start();
                if (main) {
                    pulse = ObjectAnimator.ofPropertyValuesHolder(v,
                        PropertyValuesHolder.ofFloat("scaleX", 1f, 1.12f),
                        PropertyValuesHolder.ofFloat("scaleY", 1f, 1.12f));
                    pulse.setDuration(600);
                    pulse.setRepeatCount(ValueAnimator.INFINITE);
                    pulse.setRepeatMode(ValueAnimator.REVERSE);
                    pulse.start();
                }
                break;
            default:
                v.animate().alpha(1).setDuration(700).start();
        }
    }

    void maybeHide() {
        if (!pageReady || !minPassed || splashDone) return;
        splashDone = true;
        if (pulse != null) pulse.cancel();
        splash.animate().alpha(0f).setDuration(450).withEndAction(() -> splash.setVisibility(View.GONE)).start();
    }

    String readAsset(String n) throws Exception {
        InputStream in = getAssets().open(n);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int r;
        while ((r = in.read(b)) > 0) o.write(b, 0, r);
        in.close();
        return o.toString("UTF-8");
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 11 && pendingPerm != null) {
            boolean ok = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 12 && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCb = null;
        }
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && cfg != null && cfg.optBoolean("fs", false)) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
}
