package com.richard.tvremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Remote for Vizio SmartCast TVs: pairing (PIN) + keys, volume, inputs. */
public class MainActivity extends Activity {
    static final String DEVICE_ID = "android-tv-remote";
    static final int BG = 0xFF0B0D12, CARD = 0xFF161A22, PAD = CARD, BTN = 0xFF232935, LINE = 0xFF2C3340,
            ACC = 0xFF4F7CFF, RED = 0xFFE5484D, GREEN = 0xFF3DD68C, FG = 0xFFEEF1F6, MUT = 0xFF8A94A6;

    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    SSLSocketFactory ssl;
    String pairReq;
    int pairChal;
    // Paired TVs: [{name, host, token}], current one at index `cur`
    JSONArray tvs;
    int cur;
    String host, token;

    TextView status;
    SeekBar vol;
    TextView volText;
    LinearLayout inputs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        lang = prefs.getString("lang", "auto");
        try { // TV uses a self-signed cert
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] x, String s) {}
                public void checkServerTrusted(X509Certificate[] x, String s) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            ssl = c.getSocketFactory();
        } catch (Exception e) { throw new RuntimeException(e); }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        try {
            tvs = new JSONArray(prefs.getString("tvs", "[]"));
            if (prefs.getString("token", null) != null) { // migrate single-TV prefs
                tvs.put(new JSONObject().put("name", t("电视", "TV")).put("host", prefs.getString("host", ""))
                        .put("token", prefs.getString("token", null)));
                prefs.edit().remove("token").remove("host").apply();
                saveTvs();
            }
        } catch (Exception e) { tvs = new JSONArray(); }
        cur = Math.min(prefs.getInt("cur", 0), Math.max(tvs.length() - 1, 0));
        if (tvs.length() == 0) showPairing(); else selectTv(cur);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        handleShare(i);
    }

    // ---------- Bilibili share ----------

    static final java.util.regex.Pattern BV = java.util.regex.Pattern.compile("BV[0-9A-Za-z]{10}"),
            LINK = java.util.regex.Pattern.compile("https?://\\S+");

    /** Shared text or link: Bilibili videos open fullscreen in the player, any other link opens as a web page. */
    void handleShare(android.content.Intent i) {
        if (i == null || !android.content.Intent.ACTION_SEND.equals(i.getAction())) return;
        String text = i.getStringExtra(android.content.Intent.EXTRA_TEXT);
        setIntent(new android.content.Intent());
        if (text == null || token == null) return;
        status.setText(t("正在投放到电视…", "Sending to TV…"));
        bg(() -> {
            java.util.regex.Matcher l = LINK.matcher(text);
            String link = l.find() ? l.group() : null, bv = bvid(text, link);
            if (bv != null) link = "https://player.bilibili.com/player.html?bvid=" + bv
                    + "&autoplay=1&high_quality=1&quality=120&danmaku=0";
            if (link == null) throw new Exception(t("分享的内容里没有网址", "No link in the shared text"));
            openUrl(link);
        }, () -> status.setText(t("已在电视上打开", "Opened on TV")));
    }

    void openUrl(String url) throws Exception {
        call("PUT", "/app/launch", new JSONObject().put("VALUE", new JSONObject()
                .put("MESSAGE", url).put("NAME_SPACE", 4).put("APP_ID", "3")));
    }

    /** Finds a BV id in the text, following b23.tv short-link redirects if needed. */
    static String bvid(String text, String url) throws Exception {
        java.util.regex.Matcher m = BV.matcher(text);
        if (m.find()) return m.group();
        if (url == null || !url.matches("https?://(www\\.)?b23\\.tv/.*")) return null;
        for (int hop = 0; hop < 5 && url != null; hop++) {
            m = BV.matcher(url);
            if (m.find()) return m.group();
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.getResponseCode();
            url = c.getHeaderField("Location");
            c.disconnect();
        }
        return null;
    }

    void saveTvs() { prefs.edit().putString("tvs", tvs.toString()).putInt("cur", cur).apply(); }

    void selectTv(int i) {
        cur = i;
        JSONObject tv = tvs.optJSONObject(i);
        host = tv.optString("host");
        token = tv.optString("token");
        saveTvs();
        showRemote();
    }

    // ---------- HTTP ----------

    JSONObject call(String method, String path, JSONObject body) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + path).openConnection();
        c.setSSLSocketFactory(ssl);
        c.setHostnameVerifier((h, s) -> true);
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestMethod(method);
        c.setRequestProperty("Content-Type", "application/json");
        if (token != null) c.setRequestProperty("AUTH", token);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        }
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        JSONObject r = new JSONObject(buf.toString("UTF-8"));
        String res = r.getJSONObject("STATUS").getString("RESULT");
        if (!res.equals("SUCCESS")) throw new Exception(res);
        return r;
    }

    JSONObject setting(String path) throws Exception {
        return call("GET", "/menu_native/dynamic/tv_settings/" + path, null).getJSONArray("ITEMS").getJSONObject(0);
    }

    void modify(String path, Object value) throws Exception {
        JSONObject it = setting(path);
        call("PUT", "/menu_native/dynamic/tv_settings/" + path, new JSONObject()
                .put("REQUEST", "MODIFY").put("HASHVAL", it.get("HASHVAL")).put("VALUE", value));
    }

    interface Job { void run() throws Exception; }

    void bg(Job job, Runnable after) {
        io.execute(() -> {
            try {
                job.run();
                if (after != null) ui.post(after);
            } catch (Exception e) {
                ui.post(() -> status.setText(t("出错：", "Error: ") + e.getMessage()));
            }
        });
    }

    void key(int codeset, int code) {
        getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        bg(() -> call("PUT", "/key_command/", new JSONObject().put("KEYLIST", new JSONArray().put(
                new JSONObject().put("CODESET", codeset).put("CODE", code).put("ACTION", "KEYPRESS")))),
                () -> ui.postDelayed(this::refresh, 400));
    }

    // ---------- Apps ----------

    // name, NAME_SPACE, APP_ID, MESSAGE (Vizio SmartCast app launch ids; namespace 4 / app 3 opens a web URL)
    static final Object[][] APPS = {
            {"YouTube", 5, "1", JSONObject.NULL},
            {"Bilibili", 4, "3", "https://www.bilibili.com/"},
    };
    int lastPower = -1;

    void launchApp(String name) {
        for (Object[] a : APPS) if (a[0].equals(name))
            bg(() -> call("PUT", "/app/launch", new JSONObject().put("VALUE", new JSONObject()
                    .put("MESSAGE", a[3]).put("NAME_SPACE", a[1]).put("APP_ID", a[2]))), null);
    }

    String startApp() { return tvs.optJSONObject(cur).optString("startApp", ""); }

    /** Power key; if it turns the TV on and a startup app is set, open it once the TV is ready. */
    void powerKey() {
        key(11, 2);
        String app = startApp();
        if (lastPower == 1 || app.isEmpty()) return;
        bg(() -> {
            for (int i = 0; i < 30; i++) {
                Thread.sleep(1000);
                try {
                    if (call("GET", "/state/device/power_mode", null).getJSONArray("ITEMS")
                            .getJSONObject(0).getInt("VALUE") == 1) break;
                } catch (Exception ignored) {}
            }
            Thread.sleep(5000); // let the home screen finish loading
        }, () -> launchApp(app));
    }

    // ---------- Pairing screen ----------

    void showPairing() {
        inputs = null;
        LinearLayout col = column();
        col.setPadding(dp(24), dp(56), dp(24), dp(28));
        View logo = new View(this);
        logo.setBackground(ripple(ACC, 22));
        logo.setForeground(new Glyph(Glyph.TV, Color.WHITE, dp(30)));
        col.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView ttl = title(t("添加电视", "Add TV"));
        ttl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ttl.setTextSize(28);
        ttl.setPadding(dp(4), dp(20), 0, dp(2));
        col.addView(ttl);
        status = label(t("输入电视 IP，点“开始配对”，电视上会出现 PIN 码。", "Enter the TV's IP and tap “Start pairing”. A PIN will appear on the TV."));
        col.addView(status);

        EditText ipField = field(t("电视 IP，例如 192.168.1.20", "TV IP, e.g. 192.168.1.20"), InputType.TYPE_CLASS_TEXT);
        col.addView(ipField);

        EditText pin = field(t("PIN 码", "PIN"), InputType.TYPE_CLASS_NUMBER);
        pin.setVisibility(View.GONE);
        Button finish = button(t("完成配对", "Finish pairing"), ACC);
        finish.setVisibility(View.GONE);

        Button start = button(t("开始配对", "Start pairing"), ACC);
        start.setOnClickListener(v -> {
            String h = ipField.getText().toString().trim();
            if (h.isEmpty()) return;
            host = h.contains(":") ? h : h + ":7345";
            token = null;
            status.setText(t("正在连接…", "Connecting…"));
            start.setText(t("重新发送 PIN", "Resend PIN"));
            tint(start, BTN);
            bg(() -> {
                JSONObject req = new JSONObject().put("DEVICE_ID", DEVICE_ID).put("DEVICE_NAME", t("Android 遥控器", "Android Remote"));
                JSONObject it;
                try {
                    it = call("PUT", "/pairing/start", req).getJSONObject("ITEM");
                } catch (Exception e) {
                    if (!"BLOCKED".equals(e.getMessage())) throw e;
                    // A previous pairing is still open on the TV: cancel it and try again
                    try {
                        call("PUT", "/pairing/cancel", new JSONObject().put("DEVICE_ID", DEVICE_ID)
                                .put("CHALLENGE_TYPE", 1).put("RESPONSE_VALUE", "1111").put("PAIRING_REQ_TOKEN", 0));
                    } catch (Exception ignored) {}
                    Thread.sleep(1000);
                    it = call("PUT", "/pairing/start", req).getJSONObject("ITEM");
                }
                pairReq = String.valueOf(it.get("PAIRING_REQ_TOKEN"));
                pairChal = it.getInt("CHALLENGE_TYPE");
            }, () -> {
                status.setText(t("请输入电视上显示的 PIN 码", "Enter the PIN shown on the TV"));
                pin.setVisibility(View.VISIBLE);
                finish.setVisibility(View.VISIBLE);
                pin.requestFocus();
            });
        });
        finish.setOnClickListener(v -> bg(() -> {
            JSONObject it = call("PUT", "/pairing/pair", new JSONObject()
                    .put("DEVICE_ID", DEVICE_ID).put("CHALLENGE_TYPE", pairChal)
                    .put("RESPONSE_VALUE", pin.getText().toString().trim())
                    .put("PAIRING_REQ_TOKEN", Long.parseLong(pairReq))).getJSONObject("ITEM");
            token = it.getString("AUTH_TOKEN");
            String name = t("电视 ", "TV ") + (tvs.length() + 1);
            try {
                name = call("GET", "/state/device/deviceinfo", null).getJSONArray("ITEMS")
                        .getJSONObject(0).getJSONObject("VALUE").optString("CAST_NAME", name);
            } catch (Exception ignored) {}
            for (int i = tvs.length() - 1; i >= 0; i--) // re-pairing the same TV replaces it
                if (tvs.getJSONObject(i).optString("host").equals(host)) tvs.remove(i);
            tvs.put(new JSONObject().put("name", name).put("host", host).put("token", token));
        }, () -> selectTv(tvs.length() - 1)));

        col.addView(start);
        col.addView(pin);
        col.addView(finish);
        if (tvs.length() > 0) {
            Button cancel = button(t("取消", "Cancel"), PAD);
            cancel.setTextColor(MUT);
            cancel.setOnClickListener(v -> selectTv(cur));
            col.addView(cancel);
        }
        col.addView(langButton());
        setContentView(scroll(col));
    }

    // ---------- Remote screen ----------

    TextView tvName;
    View statusDot;

    void showRemote() {
        LinearLayout col = column();

        // TV switcher: one pill per paired TV, plus "+" to add another
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < tvs.length(); i++) {
            int idx = i;
            TextView c = chip(tvs.optJSONObject(i).optString("name"), i == cur);
            c.setOnClickListener(v -> { if (idx != cur) selectTv(idx); });
            c.setOnLongClickListener(v -> { confirmRemove(idx); return true; });
            chips.addView(c, chipParams());
        }
        TextView add = chip("＋ " + t("添加", "Add"), false);
        add.setTextColor(MUT);
        add.setOnClickListener(v -> showPairing());
        chips.addView(add, chipParams());
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        col.addView(hs);

        // Header: TV name + status, power button
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(22), dp(4), dp(10));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        tvName = text(tvs.optJSONObject(cur).optString("name"), 26, FG);
        tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        info.addView(tvName);
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setBackground(round(MUT, 99));
        st.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        status = text(t("连接中…", "Connecting…"), 14, MUT);
        status.setPadding(dp(8), dp(2), 0, 0);
        st.addView(status);
        info.addView(st);
        head.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(iconButton(Glyph.POWER, RED, FG, this::powerKey), new LinearLayout.LayoutParams(dp(60), dp(60)));
        col.addView(head);

        // Circular D-pad
        int pad = dp(264), arrow = dp(76), ok = dp(100);
        android.widget.FrameLayout dpad = new android.widget.FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(CARD);
        ring.setStroke(dp(1), LINE);
        dpad.setBackground(ring);
        int[][] arrows = {{Glyph.UP, 3, 8, Gravity.TOP | Gravity.CENTER_HORIZONTAL},
                {Glyph.DOWN, 3, 0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL},
                {Glyph.LEFT, 3, 1, Gravity.LEFT | Gravity.CENTER_VERTICAL},
                {Glyph.RIGHT, 3, 7, Gravity.RIGHT | Gravity.CENTER_VERTICAL}};
        for (int[] a : arrows) {
            View b = iconButton(a[0], 0, FG, () -> key(a[1], a[2]));
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(arrow, arrow, a[3]);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            dpad.addView(b, lp);
        }
        TextView okBtn = text("OK", 20, Color.WHITE);
        okBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        okBtn.setGravity(Gravity.CENTER);
        okBtn.setBackground(ripple(ACC, 99));
        okBtn.setElevation(dp(6));
        okBtn.setOnClickListener(v -> key(3, 2));
        dpad.addView(okBtn, new android.widget.FrameLayout.LayoutParams(ok, ok, Gravity.CENTER));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(pad, pad);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.setMargins(0, dp(14), 0, dp(22));
        col.addView(dpad, dlp);

        // Back / Home / Menu
        LinearLayout nav = new LinearLayout(this);
        nav.addView(navKey(Glyph.BACK, t("返回", "Back"), 4, 0), new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(navKey(Glyph.HOME, t("主页", "Home"), 4, 15), new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(navKey(Glyph.MENU, t("菜单", "Menu"), 4, 8), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(nav);

        // App shortcuts + startup app
        LinearLayout ac = card();
        ac.addView(text(t("应用", "Apps"), 14, MUT));
        LinearLayout ar = new LinearLayout(this);
        ar.setPadding(0, dp(10), 0, 0);
        for (Object[] a : APPS) {
            String name = (String) a[0];
            TextView c = chip(name, false);
            if (name.equals("YouTube")) c.setBackground(ripple(0xFFE62117, 99));
            if (name.equals("Bilibili")) c.setBackground(ripple(0xFF00A1D6, 99));
            c.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                launchApp(name);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            ar.addView(c, lp);
        }
        ac.addView(ar);
        TextView sa = chip("", false);
        sa.setTextColor(MUT);
        sa.setTextSize(13);
        Runnable label = () -> sa.setText(t("开机打开：", "On power-on: ")
                + (startApp().isEmpty() ? t("不打开", "Nothing") : startApp()));
        label.run();
        sa.setOnClickListener(v -> { // cycle: none -> each app -> none
            String curApp = startApp(), next = (String) APPS[0][0];
            for (int i = 0; i < APPS.length; i++)
                if (APPS[i][0].equals(curApp)) next = i + 1 < APPS.length ? (String) APPS[i + 1][0] : "";
            try { tvs.optJSONObject(cur).put("startApp", next); } catch (Exception ignored) {}
            saveTvs();
            label.run();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(40));
        sp.setMargins(dp(4), dp(12), dp(4), 0);
        ac.addView(sa, sp);
        col.addView(ac, cardParams());

        // Volume card
        LinearLayout vc = card();
        LinearLayout vh = new LinearLayout(this);
        vh.setGravity(Gravity.CENTER_VERTICAL);
        vh.addView(text(t("音量", "Volume"), 14, MUT), new LinearLayout.LayoutParams(0, -2, 1));
        volText = text("–", 14, FG);
        volText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        vh.addView(volText);
        vc.addView(vh);
        LinearLayout vr = new LinearLayout(this);
        vr.setGravity(Gravity.CENTER_VERTICAL);
        vr.setPadding(0, dp(10), 0, 0);
        vr.addView(iconButton(Glyph.MUTE, BTN, FG, () -> key(5, 4)), new LinearLayout.LayoutParams(dp(48), dp(48)));
        vr.addView(iconButton(Glyph.MINUS, 0, FG, () -> key(5, 0)), new LinearLayout.LayoutParams(dp(44), dp(48)));
        vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgressTintList(android.content.res.ColorStateList.valueOf(ACC));
        vol.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        vol.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { volText.setText(String.valueOf(p)); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                int v = s.getProgress();
                bg(() -> modify("audio/volume", v), null);
            }
        });
        vr.addView(vol, new LinearLayout.LayoutParams(0, -2, 1));
        vr.addView(iconButton(Glyph.PLUS, 0, FG, () -> key(5, 1)), new LinearLayout.LayoutParams(dp(44), dp(48)));
        vc.addView(vr);
        col.addView(vc, cardParams());

        // Inputs card
        LinearLayout ic = card();
        ic.addView(text(t("输入源", "Inputs"), 14, MUT));
        inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL);
        inputs.setPadding(0, dp(6), 0, 0);
        ic.addView(inputs);
        col.addView(ic, cardParams());

        TextView tip = text(t("点上方名字切换电视，长按可删除。", "Tap a name above to switch TVs, long-press to remove."), 12, MUT);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, dp(18), 0, dp(4));
        col.addView(tip);
        col.addView(langButton());

        setContentView(scroll(col));
        refresh();
    }

    void confirmRemove(int idx) {
        new android.app.AlertDialog.Builder(this)
                .setMessage(String.format(t("删除“%s”？", "Remove “%s”?"), tvs.optJSONObject(idx).optString("name")))
                .setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("删除", "Remove"), (d, w) -> {
                    tvs.remove(idx);
                    if (tvs.length() == 0) { cur = 0; saveTvs(); host = token = null; showPairing(); }
                    else selectTv(cur >= tvs.length() || cur == idx ? 0 : (cur > idx ? cur - 1 : cur));
                }).show();
    }

    void refresh() {
        if (inputs == null) return;
        final LinearLayout target = inputs;
        final Object[] s = new Object[4];
        bg(() -> {
            s[0] = call("GET", "/state/device/power_mode", null).getJSONArray("ITEMS").getJSONObject(0).getInt("VALUE");
            s[1] = setting("audio/volume").getInt("VALUE");
            s[2] = setting("devices/current_input").getString("VALUE");
            s[3] = call("GET", "/menu_native/dynamic/tv_settings/devices/name_input", null).getJSONArray("ITEMS");
        }, () -> {
            if (target != inputs) return; // screen changed meanwhile
            boolean on = (int) s[0] == 1;
            lastPower = on ? 1 : 0;
            status.setText((on ? t("已开机", "On") : t("待机", "Standby")) + " · " + s[2]);
            statusDot.setBackground(round(on ? GREEN : MUT, 99));
            vol.setProgress((int) s[1]);
            inputs.removeAllViews();
            JSONArray arr = (JSONArray) s[3];
            LinearLayout r = null;
            for (int i = 0; i < arr.length(); i++) {
                if (i % 3 == 0) { r = row(); inputs.addView(r); }
                String name = arr.optJSONObject(i).optString("NAME");
                String label = arr.optJSONObject(i).optJSONObject("VALUE") != null
                        ? arr.optJSONObject(i).optJSONObject("VALUE").optString("NAME", name) : name;
                TextView b = chip(name, label.equals(s[2]) || name.equals(s[2]));
                b.setGravity(Gravity.CENTER);
                b.setOnClickListener(v -> bg(() -> modify("devices/current_input", name),
                        () -> ui.postDelayed(this::refresh, 800)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
                lp.setMargins(dp(4), 0, dp(4), 0);
                r.addView(b, lp);
            }
            if (r != null) for (int k = arr.length() % 3; k > 0 && k < 3; k++) // keep last row aligned
                r.addView(new View(this), new LinearLayout.LayoutParams(0, dp(44), 1));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (inputs != null) refresh();
    }

    // ---------- UI helpers ----------

    /** App language: "auto" follows the system, otherwise "zh" or "en". */
    static String lang = "auto";

    static String t(String zh, String en) {
        String l = lang.equals("auto") ? java.util.Locale.getDefault().getLanguage() : lang;
        return l.equals("zh") ? zh : en;
    }

    Button langButton() {
        String[] codes = {"auto", "zh", "en"};
        String[] names = {t("语言：跟随系统", "Language: System"), "语言：中文", "Language: English"};
        int i = java.util.Arrays.asList(codes).indexOf(lang);
        Button b = button("🌐  " + names[Math.max(i, 0)], 0);
        b.setTextColor(MUT);
        b.setTextSize(13);
        b.setOnClickListener(v -> {
            lang = codes[(Math.max(i, 0) + 1) % codes.length];
            prefs.edit().putString("lang", lang).apply();
            if (tvs.length() == 0) showPairing(); else showRemote();
        });
        return b;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(28), dp(20), dp(28));
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(5), 0, dp(5));
        return l;
    }

    LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1);
        p.setMargins(dp(5), 0, dp(5), 0);
        return p;
    }

    LinearLayout keyRow(String[] labels, int[][] codes) {
        LinearLayout r = row();
        for (int i = 0; i < labels.length; i++) {
            if (codes[i] == null) { r.addView(new View(this), cell()); continue; }
            int[] c = codes[i];
            Button b = button(labels[i], BTN);
            b.setOnClickListener(v -> key(c[0], c[1]));
            r.addView(b, cell());
        }
        return r;
    }

    Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(FG);
        b.setTextSize(16);
        b.setStateListAnimator(null);
        tint(b, color);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(dp(4), dp(8), dp(4), dp(4));
        b.setLayoutParams(p);
        return b;
    }

    void tint(View v, int color) { v.setBackground(ripple(color, 16)); }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** Rounded background with a touch ripple; color 0 = transparent until pressed. */
    android.graphics.drawable.Drawable ripple(int color, int radiusDp) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), round(color, radiusDp), round(Color.WHITE, radiusDp));
    }

    View iconButton(int glyph, int bgColor, int fg, Runnable onClick) {
        View v = new View(this);
        v.setBackground(ripple(bgColor, 99));
        v.setForeground(new Glyph(glyph, fg, dp(24)));
        v.setClickable(true);
        v.setOnClickListener(x -> onClick.run());
        return v;
    }

    View navKey(int glyph, String caption, int codeset, int code) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.addView(iconButton(glyph, BTN, FG, () -> key(codeset, code)), new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView c = text(caption, 12, MUT);
        c.setPadding(0, dp(6), 0, 0);
        c.setGravity(Gravity.CENTER);
        l.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    TextView chip(String label, boolean selected) {
        TextView c = text(label, 14, selected ? Color.WHITE : FG);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(18), 0, dp(18), 0);
        c.setMaxLines(1);
        c.setBackground(ripple(selected ? ACC : BTN, 99));
        return c;
    }

    LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40));
        p.setMargins(0, 0, dp(8), 0);
        return p;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(14), dp(16));
        GradientDrawable d = round(CARD, 24);
        d.setStroke(dp(1), LINE);
        c.setBackground(d);
        return c;
    }

    LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(18), 0, 0);
        return p;
    }

    TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    /** Small line icons drawn with paths, so they look the same on every phone font. */
    static class Glyph extends android.graphics.drawable.Drawable {
        static final int POWER = 0, HOME = 1, BACK = 2, MENU = 3, UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7,
                PLUS = 8, MINUS = 9, MUTE = 10, TV = 11;
        final int type, size;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Glyph(int type, int color, int size) {
            this.type = type;
            this.size = size;
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(size / 11f);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY(), s = size / 2f;
            android.graphics.Path path = new android.graphics.Path();
            switch (type) {
                case POWER:
                    c.drawArc(cx - s * .8f, cy - s * .7f, cx + s * .8f, cy + s * .9f, -50, 280, false, p);
                    c.drawLine(cx, cy - s * .95f, cx, cy - s * .05f, p);
                    return;
                case HOME:
                    path.moveTo(cx - s * .9f, cy - s * .05f);
                    path.lineTo(cx, cy - s * .85f);
                    path.lineTo(cx + s * .9f, cy - s * .05f);
                    path.moveTo(cx - s * .65f, cy - s * .25f);
                    path.lineTo(cx - s * .65f, cy + s * .8f);
                    path.lineTo(cx + s * .65f, cy + s * .8f);
                    path.lineTo(cx + s * .65f, cy - s * .25f);
                    break;
                case BACK:
                    path.moveTo(cx - s * .35f, cy - s * .65f);
                    path.lineTo(cx - s * .85f, cy - s * .15f);
                    path.lineTo(cx - s * .35f, cy + s * .35f);
                    path.moveTo(cx - s * .85f, cy - s * .15f);
                    path.lineTo(cx + s * .3f, cy - s * .15f);
                    path.quadTo(cx + s * .85f, cy - s * .15f, cx + s * .85f, cy + s * .35f);
                    path.quadTo(cx + s * .85f, cy + s * .8f, cx + s * .3f, cy + s * .8f);
                    break;
                case MENU:
                    for (float y : new float[]{-.6f, 0, .6f}) {
                        path.moveTo(cx - s * .8f, cy + s * y);
                        path.lineTo(cx + s * .8f, cy + s * y);
                    }
                    break;
                case UP: case DOWN: case LEFT: case RIGHT:
                    c.save();
                    c.rotate(type == UP ? 0 : type == RIGHT ? 90 : type == DOWN ? 180 : 270, cx, cy);
                    path.moveTo(cx - s * .6f, cy + s * .3f);
                    path.lineTo(cx, cy - s * .3f);
                    path.lineTo(cx + s * .6f, cy + s * .3f);
                    c.drawPath(path, p);
                    c.restore();
                    return;
                case PLUS:
                    path.moveTo(cx, cy - s * .7f);
                    path.lineTo(cx, cy + s * .7f);
                case MINUS:
                    path.moveTo(cx - s * .7f, cy);
                    path.lineTo(cx + s * .7f, cy);
                    break;
                case MUTE:
                    path.moveTo(cx - s * .9f, cy - s * .3f);
                    path.lineTo(cx - s * .5f, cy - s * .3f);
                    path.lineTo(cx - s * .05f, cy - s * .75f);
                    path.lineTo(cx - s * .05f, cy + s * .75f);
                    path.lineTo(cx - s * .5f, cy + s * .3f);
                    path.lineTo(cx - s * .9f, cy + s * .3f);
                    path.close();
                    path.moveTo(cx + s * .3f, cy - s * .3f);
                    path.lineTo(cx + s * .9f, cy + s * .3f);
                    path.moveTo(cx + s * .9f, cy - s * .3f);
                    path.lineTo(cx + s * .3f, cy + s * .3f);
                    break;
                case TV:
                    c.drawRoundRect(cx - s * .95f, cy - s * .7f, cx + s * .95f, cy + s * .45f, s * .15f, s * .15f, p);
                    path.moveTo(cx - s * .4f, cy + s * .85f);
                    path.lineTo(cx + s * .4f, cy + s * .85f);
                    break;
            }
            c.drawPath(path, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    TextView title(String t) {
        TextView v = label(t);
        v.setTextSize(24);
        v.setTextColor(FG);
        return v;
    }

    TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(MUT);
        v.setTextSize(14);
        v.setPadding(dp(5), dp(8), dp(5), dp(8));
        return v;
    }

    EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextColor(FG);
        e.setHintTextColor(MUT);
        e.setSingleLine();
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        tint(e, BTN);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(5), dp(8), dp(5), dp(8));
        e.setLayoutParams(p);
        return e;
    }

    ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(BG);
        s.setFillViewport(true);
        // Android 15+ draws edge-to-edge: keep content clear of the status and navigation bars
        s.setClipToPadding(false);
        s.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(0, in.getSystemWindowInsetTop(), 0, in.getSystemWindowInsetBottom());
            return in;
        });
        s.addView(content);
        return s;
    }
}
