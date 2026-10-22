package com.shoytani.shokti;

import android.Manifest;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * SHOYTANI SHOKTI - যতগুলো ইচ্ছা মেসেঞ্জার আইডি (প্রতিটার আলাদা লগইন সেশন)।
 * যেকোনো একটা আইডি কলে ঢুকলে বাকি সব আইডি অটো কলে জয়েন করার চেষ্টা করে।
 */
public class MainActivity extends AppCompatActivity {

    private static final String HOME = "www.facebook.com/messages/";
    private static final int MAX_ACCOUNTS = 8;

    // কলে আছে কিনা বোঝার স্ক্রিপ্ট। রিটার্ন: "<url>|1" বা "<url>|0"
    private static final String STATE_JS =
            "(function(){"
                    + "var u=location.href;"
                    + "var c=/groupcall|\\/call\\//i.test(location.pathname);"
                    + "if(!c){"
                    + "var els=document.querySelectorAll('[aria-label],button,[role=button]');"
                    + "for(var i=0;i<els.length;i++){"
                    + "var t=((els[i].getAttribute('aria-label')||'')+'').toLowerCase();"
                    + "if(/^(leave call|end call|hang up|leave group call)/.test(t)){c=true;break;}"
                    + "}}"
                    + "return u+'|'+(c?'1':'0');"
                    + "})()";

    // Join বাটন খুঁজে চাপার স্ক্রিপ্ট
    private static final String JOIN_JS =
            "(function(){"
                    + "var els=document.querySelectorAll('[aria-label],button,[role=button],a');"
                    + "for(var i=0;i<els.length;i++){"
                    + "var e=els[i];"
                    + "var t=((e.getAttribute('aria-label')||'')+' '+(e.innerText||'')).trim().toLowerCase();"
                    + "if(/^(join|join call|join now|join with audio|join with video|join audio|join video|জয়েন)/.test(t)){"
                    + "e.click();return 'clicked';}"
                    + "}"
                    + "return 'no';"
                    + "})()";

    private final List<WebView> webs = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Boolean> inCall = new ArrayList<>();
    private final List<String> currentUrl = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean autoJoin = true;
    private boolean multiProfile = true;
    private int selected = 0;

    private FrameLayout holder;
    private LinearLayout tabBar;
    private TextView status;
    private Button btnAuto;
    private Button btnAdd;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("shoytani", MODE_PRIVATE);

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA}, 1);

        multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);

        buildUi();

        if (!multiProfile) {
            Toast.makeText(this,
                    "আপনার ফোনের WebView পুরনো। Play Store থেকে Android System WebView আপডেট করুন, "
                            + "তাহলে একাধিক আইডি কাজ করবে।", Toast.LENGTH_LONG).show();
        }

        int count = multiProfile ? prefs.getInt("count", 2) : 1;
        if (count < 1) {
            count = 1;
        }
        for (int i = 0; i < count; i++) {
            addAccount();
        }
        select(0);
        handler.postDelayed(poller, 3000);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // উপরের সারি: অটো জয়েন, এখনই জয়েন, নতুন আইডি যোগ
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        btnAuto = makeButton("অটো জয়েন: ON");
        Button btnSync = makeButton("সবাইকে এখনই জয়েন");
        btnAdd = makeButton("+ নতুন আইডি");

        btnAuto.setOnClickListener(v -> {
            autoJoin = !autoJoin;
            btnAuto.setText(autoJoin ? "অটো জয়েন: ON" : "অটো জয়েন: OFF");
        });
        btnSync.setOnClickListener(v -> joinAllExcept(selected, currentUrl.get(selected)));
        btnAdd.setOnClickListener(v -> {
            if (webs.size() >= MAX_ACCOUNTS) {
                Toast.makeText(this, "সর্বোচ্চ " + MAX_ACCOUNTS + "টা আইডি", Toast.LENGTH_SHORT).show();
                return;
            }
            addAccount();
            prefs.edit().putInt("count", webs.size()).apply();
            select(webs.size() - 1);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(btnAuto, lp);
        actions.addView(btnSync, lp);
        actions.addView(btnAdd, lp);

        // আইডি ট্যাবের সারি (স্ক্রল করা যায়)
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tabBar);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#9CFF9C"));
        status.setTextSize(12f);
        status.setPadding(16, 4, 16, 4);

        holder = new FrameLayout(this);

        root.addView(actions);
        root.addView(scroll);
        root.addView(status);
        root.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setPadding(8, 4, 8, 4);
        return b;
    }

    /** নতুন আইডি: আলাদা প্রোফাইল (আলাদা কুকি/লগইন) সহ নতুন WebView + ট্যাব বাটন */
    private void addAccount() {
        final int index = webs.size();
        WebView w = createWebView(index);
        webs.add(w);
        inCall.add(false);
        currentUrl.add("");
        holder.addView(w, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        w.loadUrl(HOME);

        Button b = makeButton("আইডি " + (index + 1));
        b.setOnClickListener(v -> select(index));
        tabButtons.add(b);
        tabBar.addView(b);
    }

    private WebView createWebView(int index) {
        WebView w = new WebView(this);

        if (multiProfile) {
            String name = "acc" + (index + 1);
            ProfileStore.getInstance().getOrCreateProfile(name);
            WebViewCompat.setProfile(w, name);
        }

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        String ua = s.getUserAgentString()
                .replace("; wv", "")
                .replace("Version/4.0 ", "");
        s.setUserAgentString(ua);

        w.setBackgroundColor(Color.BLACK);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
        return w;
    }

    private void select(int index) {
        if (index < 0 || index >= webs.size()) {
            return;
        }
        selected = index;
        // সবগুলো WebView VISIBLE থাকে (যাতে পেছনেরগুলোও চলে), শুধু একটা সামনে আনা হয়
        webs.get(index).bringToFront();
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).setAlpha(i == index ? 1f : 0.5f);
        }
    }

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < webs.size(); i++) {
                pollOne(i);
            }
            updateStatus();
            handler.postDelayed(this, 2500);
        }
    };

    private void pollOne(final int i) {
        webs.get(i).evaluateJavascript(STATE_JS, value -> {
            if (value == null) {
                return;
            }
            String v = value.replace("\"", "");
            int bar = v.lastIndexOf('|');
            if (bar < 0) {
                return;
            }
            currentUrl.set(i, v.substring(0, bar));
            boolean now = v.substring(bar + 1).equals("1");
            boolean was = inCall.get(i);
            inCall.set(i, now);

            // এই আইডি এইমাত্র কলে ঢুকেছে -> বাকি সবাই জয়েন করুক
            if (now && !was && autoJoin) {
                joinAllExcept(i, currentUrl.get(i));
            }
        });
    }

    /** i ছাড়া বাকি সব আইডিতে একই থ্রেড/কল লিংক খুলে Join চাপার চেষ্টা। */
    private void joinAllExcept(int i, String url) {
        if (url == null || !url.startsWith("http")) {
            return;
        }
        for (int j = 0; j < webs.size(); j++) {
            if (j != i && !inCall.get(j)) {
                joinOne(j, url);
            }
        }
    }

    private void joinOne(final int j, String url) {
        Toast.makeText(this, "আইডি " + (j + 1) + " জয়েন করার চেষ্টা করছে...",
                Toast.LENGTH_SHORT).show();
        if (!url.equals(currentUrl.get(j))) {
            webs.get(j).loadUrl(url);
        }
        final int[] tries = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                tries[0]++;
                webs.get(j).evaluateJavascript(JOIN_JS, value -> {
                    if (value != null && value.contains("clicked")) {
                        tries[0] = 999;
                    }
                });
                if (tries[0] < 25) {
                    handler.postDelayed(this, 2000);
                }
            }
        }, 4000);
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < webs.size(); i++) {
            if (i > 0) {
                sb.append("  |  ");
            }
            sb.append("আইডি ").append(i + 1).append(": ")
                    .append(inCall.get(i) ? "কলে" : "নেই");
        }
        status.setText(sb.toString());
    }

    @Override
    public void onBackPressed() {
        WebView w = webs.isEmpty() ? null : webs.get(selected);
        if (w != null && w.canGoBack()) {
            w.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        for (WebView w : webs) {
            w.destroy();
        }
        super.onDestroy();
    }
}
