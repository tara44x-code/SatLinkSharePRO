package com.satlink.sharepro;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * SatLink Share PRO — واجهة بسيطة: الرئيسية فيها زر إرسال وزر استلام فقط.
 * المطور: مهند سات لنك
 */
public class MainActivity extends Activity implements TransferLog.Listener, ReceiveService.UiListener, SendService.UiListener {
    public static final String EXTRA_SCREEN = "com.satlink.sharepro.SCREEN";
    private static final String DEVELOPER = "مهند سات لنك";
    private static final int REQ_PICK_FILES = 3001;
    private static final int REQ_PICK_FOLDER = 3002;
    private static final int REQ_PERMISSIONS = 4401;

    private static final int BG = Color.rgb(4, 5, 10);
    private static final int CARD_FILL = Color.argb(215, 10, 12, 22);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(165, 172, 200);
    private static final int CYAN = Color.rgb(0, 220, 255);
    private static final int PINK = Color.rgb(255, 80, 200);
    private static final int GREEN = Color.rgb(80, 255, 140);
    private static final int RED = Color.rgb(255, 90, 110);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String screen = "home";
    private LinearLayout content;

    // الإرسال
    private final ArrayList<FileItem> selectedFiles = new ArrayList<>();
    private final ArrayList<DeviceInfo> lanDevices = new ArrayList<>();
    private final ArrayList<VirtualNetwork.Target> p2pTargets = new ArrayList<>();
    private SatLinkNsdHelper nsdHelper;
    private UdpDiscoveryHelper udpHelper;
    private VirtualNetwork vnet;
    private boolean searching;
    private TextView selectionText;
    private LinearLayout devicesBox;
    private TextView scanText;
    private TextView sendStatusText;
    private View cancelSendButton;

    // الاستلام
    private TextView receiveStatusText;
    private TextView addressesText;
    private TextView vnetText;
    private Switch vnetSwitch;
    private boolean updatingSwitch;

    // قائمة الملفات
    private LinearLayout historyBox;
    private String historyDirection;
    private final HashMap<String, RowViews> rows = new HashMap<>();

    private static final class RowViews {
        TextView title; TextView sub; ProgressBar bar;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nsdHelper = new SatLinkNsdHelper(this);
        udpHelper = new UdpDiscoveryHelper(this);
        vnet = new VirtualNetwork(this);
        configureWindow();
        requestNeededPermissions();
        if (!handleIntent(getIntent())) showHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        TransferLog.setListener(this);
        ReceiveService.attach(this);
        SendService.attach(this);
        if (historyDirection != null) renderHistory();
        if ("receive".equals(screen)) refreshAddresses();
    }

    @Override protected void onPause() {
        TransferLog.clearListener(this);
        ReceiveService.detach(this);
        SendService.detach(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopSearch();
        try { nsdHelper.shutdown(); } catch (Exception ignored) {}
        try { udpHelper.shutdown(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!"home".equals(screen)) showHome(); else super.onBackPressed();
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String target = intent.getStringExtra(EXTRA_SCREEN);
        if ("send".equals(target)) { showSend(); return true; }
        if ("receive".equals(target)) { showReceive(); return true; }
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return false;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) uris.addAll(streams);
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        }
        if (uris.isEmpty() && intent.getClipData() != null)
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) uris.add(intent.getClipData().getItemAt(i).getUri());
        selectedFiles.clear();
        for (Uri uri : uris) if (uri != null) addUri(uri);
        showSend();
        if (!selectedFiles.isEmpty()) toast("اختر الجهاز المستلم");
        return true;
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.getDecorView().setBackgroundColor(BG);
    }

    private void requestNeededPermissions() {
        ArrayList<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // بعد منح صلاحية الأجهزة القريبة نعيد تشغيل البحث/الشبكة حتى تشتغل فوراً.
        if ("send".equals(screen)) startSearch();
        if ("receive".equals(screen)) ReceiveService.start(this, null);
    }

    // =================================================================== الشاشات

    /** هيكل كل شاشة: خلفية نيون + محتوى قابل للتمرير. */
    private void frame(boolean scroll) {
        rows.clear();
        historyBox = null;
        historyDirection = null;
        devicesBox = null; scanText = null; selectionText = null; sendStatusText = null; cancelSendButton = null;
        receiveStatusText = null; addressesText = null; vnetText = null; vnetSwitch = null;

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        rootFrame.setBackgroundColor(BG);
        ImageView bg = new ImageView(this);
        bg.setImageResource(R.drawable.bg_neon);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rootFrame.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(24));
        if (scroll) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            sv.addView(content, new ScrollView.LayoutParams(-1, -2));
            rootFrame.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            rootFrame.addView(content, new FrameLayout.LayoutParams(-1, -1));
        }
        setContentView(rootFrame);
    }

    private void showHome() {
        screen = "home";
        stopSearch();
        frame(false);

        content.addView(space(28));
        TextView title = text("SatLink Share PRO", 26, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(10), 0, 0, Color.rgb(120, 80, 255));
        content.addView(title, lp(-1, -2));
        TextView dev = text("المطور: " + DEVELOPER, 14, CYAN, true);
        dev.setGravity(Gravity.CENTER);
        dev.setShadowLayer(dp(6), 0, 0, CYAN);
        content.addView(dev, lp(-1, -2));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        content.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));

        middle.addView(cardButton(R.drawable.card_send, "إرسال", v -> showSend()), lp(-1, -2));
        middle.addView(space(18));
        middle.addView(cardButton(R.drawable.card_receive, "استلام", v -> showReceive()), lp(-1, -2));

        String bgState = (ReceiveService.isActive() ? "الاستلام شغال بالخلفية" : "")
                + (SendService.isBusy() ? (ReceiveService.isActive() ? "  ·  " : "") + "الإرسال شغال بالخلفية" : "");
        TextView foot = text(bgState.isEmpty() ? "بالراوتر أو بدون راوتر · سريع · بدون إنترنت" : bgState, 13, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        content.addView(foot, lp(-1, -2));
    }

    private View cardButton(int res, String description, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(description);
        iv.setOnClickListener(click);
        pressable(iv);
        return iv;
    }

    private void header(String title, int iconRes) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(bar, lp(-1, dp(64)));

        TextView back = text("›", 38, TEXT, true); // في الاتجاه العربي السهم لليمين يعني رجوع
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> showHome());
        bar.addView(back, lp(dp(44), -1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bar.addView(icon, lp(dp(64), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -1, 1));
        TextView t = text(title, 24, TEXT, true);
        t.setShadowLayer(dp(8), 0, 0, Color.rgb(140, 90, 255));
        titles.addView(t, lp(-1, -2));
        titles.addView(text("المطور: " + DEVELOPER, 12, CYAN, false), lp(-1, -2));
        content.addView(space(10));
    }

    // ------------------------------------------------------------------- الإرسال

    private void showSend() {
        screen = "send";
        frame(true);
        header("إرسال", R.drawable.ic_send_neon);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.addView(neonButton("اختيار ملفات", NeonDrawable.COOL, v -> openFilePicker()), weight());
        pickRow.addView(neonButton("اختيار مجلد", NeonDrawable.WARM, v -> openFolderPicker()), weight());
        content.addView(pickRow, lp(-1, dp(58)));
        content.addView(space(8));

        selectionText = text("", 14, MUTED, false);
        selectionText.setPadding(dp(6), 0, dp(6), 0);
        selectionText.setOnClickListener(v -> {
            if (!selectedFiles.isEmpty()) { selectedFiles.clear(); updateSelection(); toast("تم مسح الاختيار"); }
        });
        content.addView(selectionText, lp(-1, -2));
        updateSelection();
        content.addView(space(14));

        LinearLayout devHead = sectionHeader("الأجهزة القريبة");
        devHead.addView(smallButton("IP يدوي", v -> showManualIpDialog()), lp(-2, dp(36)));
        devHead.addView(smallButton("↻ بحث", v -> startSearch()), lp(-2, dp(36)));
        content.addView(devHead, lp(-1, -2));
        scanText = text("", 13, MUTED, false);
        content.addView(scanText, lp(-1, -2));
        content.addView(space(6));
        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(devicesBox, lp(-1, -2));
        renderDevices();
        content.addView(space(14));

        LinearLayout histHead = sectionHeader("الملفات المرسلة");
        TextView cancel = smallButton("إلغاء", v -> { SendService.cancel(); toast("تم طلب الإلغاء"); });
        cancel.setTextColor(RED);
        cancelSendButton = cancel;
        cancel.setVisibility(SendService.isBusy() ? View.VISIBLE : View.GONE);
        histHead.addView(cancel, lp(-2, dp(36)));
        histHead.addView(smallButton("مسح", v -> { TransferLog.clear(this, TransferLog.SEND); renderHistory(); }), lp(-2, dp(36)));
        content.addView(histHead, lp(-1, -2));
        sendStatusText = text("", 13, MUTED, false);
        content.addView(sendStatusText, lp(-1, -2));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyBox, lp(-1, -2));
        historyDirection = TransferLog.SEND;
        renderHistory();
        SendService.attach(this);
        startSearch();
    }

    private void updateSelection() {
        if (selectionText == null) return;
        if (selectedFiles.isEmpty()) {
            selectionText.setText("اختر الملفات، ثم اضغط على الجهاز المستلم وسيبدأ الإرسال مباشرة");
            selectionText.setTextColor(MUTED);
            return;
        }
        long total = 0;
        for (FileItem f : selectedFiles) total += Math.max(0, f.size);
        String first = selectedFiles.get(0).name;
        String text = selectedFiles.size() == 1 ? first : selectedFiles.size() + " ملف";
        selectionText.setText("✓ " + text + " · " + NetworkUtils.formatBytes(total) + "   (اضغط هنا للمسح)");
        selectionText.setTextColor(GREEN);
    }

    private void startSearch() {
        if (!"send".equals(screen)) return;
        stopSearch();
        searching = true;
        lanDevices.clear();
        p2pTargets.clear();
        renderDevices();
        setScan("جاري البحث على الراوتر وعلى شبكة SatLink...");
        nsdHelper.startDiscovery(new SatLinkNsdHelper.DiscoveryCallback() {
            @Override public void onDeviceFound(DeviceInfo d) { addLan(d); }
            @Override public void onDiscoveryLog(String m) { }
        });
        udpHelper.startScan(new UdpDiscoveryHelper.UdpCallback() {
            @Override public void onDeviceFound(DeviceInfo d) { addLan(d); }
            @Override public void onDiscoveryLog(String m) { }
        });
        vnet.startSearching(new VirtualNetwork.Callback() {
            @Override public void onLog(String m) { }
            @Override public void onTargetFound(VirtualNetwork.Target t) { addTarget(t); }
        });
        ui.postDelayed(() -> {
            if (searching && lanDevices.isEmpty() && p2pTargets.isEmpty())
                setScan("لم يظهر جهاز بعد. افتح «استلام» في الجهاز الثاني، أو استخدم IP يدوي.");
        }, 12000);
    }

    private void stopSearch() {
        searching = false;
        try { nsdHelper.stopDiscovery(); } catch (Exception ignored) {}
        try { udpHelper.stopScan(); } catch (Exception ignored) {}
        try { vnet.stopSearching(); } catch (Exception ignored) {}
    }

    private void setScan(String s) { if (scanText != null) scanText.setText(s); }

    private static String cleanName(String name) {
        if (name == null) return "SatLink";
        return name.replace(" · LAN", "").trim();
    }

    private void addLan(DeviceInfo d) {
        ui.post(() -> {
            String name = cleanName(d.name);
            for (DeviceInfo e : lanDevices) if (cleanName(e.name).equals(name) || e.key().equals(d.key())) return;
            lanDevices.add(new DeviceInfo(name, d.hostIp, d.port));
            renderDevices();
        });
    }

    private void addTarget(VirtualNetwork.Target t) {
        ui.post(() -> {
            for (VirtualNetwork.Target e : p2pTargets) if (e.key().equals(t.key())) return;
            p2pTargets.add(t);
            renderDevices();
        });
    }

    private void renderDevices() {
        if (devicesBox == null) return;
        devicesBox.removeAllViews();
        int shown = 0;
        for (DeviceInfo d : lanDevices) {
            devicesBox.addView(deviceRow(d.name, "عبر الراوتر · " + d.hostIp, NeonDrawable.COOL, v -> sendTo(d)), lp(-1, dp(72)));
            devicesBox.addView(space(8));
            shown++;
        }
        for (VirtualNetwork.Target t : p2pTargets) {
            boolean dup = false;
            for (DeviceInfo d : lanDevices) if (cleanName(d.name).equals(cleanName(t.name))) { dup = true; break; }
            if (dup) continue;
            devicesBox.addView(deviceRow(t.name, "بدون راوتر · شبكة SatLink", NeonDrawable.WARM, v -> sendViaVirtualNetwork(t)), lp(-1, dp(72)));
            devicesBox.addView(space(8));
            shown++;
        }
        if (shown == 0) {
            TextView empty = text("لا توجد أجهزة بعد…\nافتح «استلام» في الهاتف أو الحاسبة الثانية.", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            empty.setBackground(new NeonDrawable(NeonDrawable.COOL, density(), CARD_FILL));
            devicesBox.addView(empty, lp(-1, -2));
        } else {
            setScan("اضغط على الجهاز ليبدأ الإرسال");
        }
    }

    private View deviceRow(String name, String sub, int[] colors, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(6));
        NeonDrawable bg = new NeonDrawable(colors, density(), CARD_FILL);
        box.setBackground(bg);
        TextView icon = text(name.toLowerCase(Locale.US).contains("pc") ? "🖥" : "📱", 24, TEXT, false);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon, lp(dp(42), -1));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(texts, new LinearLayout.LayoutParams(0, -1, 1));
        TextView n = text(name, 16, TEXT, true);
        n.setSingleLine(true); n.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(n, lp(-1, -2));
        texts.addView(text(sub, 12, MUTED, false), lp(-1, -2));
        TextView go = text("إرسال ‹", 14, CYAN, true);
        go.setGravity(Gravity.CENTER);
        box.addView(go, lp(-2, -1));
        box.setOnClickListener(click);
        pressable(box);
        return box;
    }

    private boolean ensureFiles() {
        if (selectedFiles.isEmpty()) { toast("اختر ملفات أولاً"); openFilePicker(); return false; }
        return true;
    }

    private void sendTo(DeviceInfo d) {
        if (!ensureFiles()) return;
        SendService.send(this, d, selectedFiles);
        selectedFiles.clear();
        updateSelection();
        toast("بدأ الإرسال إلى " + d.name);
    }

    private void sendViaVirtualNetwork(VirtualNetwork.Target t) {
        if (!ensureFiles()) return;
        final ArrayList<FileItem> files = new ArrayList<>(selectedFiles);
        selectedFiles.clear();
        updateSelection();
        ReceiveService.pauseVirtualNetwork(); // الهاتف لا يقدر يكون مستلماً ومرسلاً على شبكتين مباشرتين بنفس الوقت
        stopSearch();
        setScan("جاري الاتصال بشبكة " + t.name + " ...");
        final boolean[] done = {false};
        vnet.connect(t, new VirtualNetwork.Callback() {
            @Override public void onLog(String m) { setScan(m); }
            @Override public void onConnected(String ownerIp, boolean iAmOwner) {
                if (done[0]) return;
                done[0] = true;
                if (ownerIp == null || iAmOwner) {
                    setScan("تم الاتصال. اضغط بحث واختر الجهاز من القائمة");
                    selectedFiles.addAll(files); updateSelection();
                    startSearch();
                    return;
                }
                setScan("متصل بدون راوتر · بدأ الإرسال");
                SendService.send(MainActivity.this, new DeviceInfo(t.name, ownerIp, t.port), files);
            }
            @Override public void onDisconnected() {
                if (done[0]) return;
                done[0] = true;
                setScan("تعذر الاتصال بشبكة SatLink. قرّب الجهازين وحاول مجدداً");
                selectedFiles.addAll(files); updateSelection();
            }
        });
        ui.postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                setScan("انتهت مهلة الاتصال. تأكد أن الاستلام مفتوح بالجهاز الثاني وحاول مجدداً");
                selectedFiles.addAll(files); updateSelection();
            }
        }, 30000);
    }

    private void showManualIpDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setHint("مثال: 192.168.1.20");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("عنوان IP للجهاز المستلم")
                .setMessage("العنوان ظاهر في شاشة الاستلام بالجهاز الثاني.")
                .setView(input)
                .setPositiveButton("إضافة", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = input.getText().toString().trim();
            if (!isIpv4(ip)) { toast("اكتب عنوان IPv4 صحيح"); return; }
            addLan(new DeviceInfo("SatLink · " + ip, ip, NetworkUtils.PORT));
            dialog.dismiss();
        }));
        dialog.show();
    }

    private static boolean isIpv4(String ip) {
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    @Override public void onSendStatus(String status, boolean busy) {
        if (sendStatusText != null) sendStatusText.setText(status == null ? "" : status);
        if (cancelSendButton != null) cancelSendButton.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------- الاستلام

    private void showReceive() {
        screen = "receive";
        stopSearch();
        frame(true);
        header("استلام", R.drawable.ic_receive_neon);
        ReceiveService.start(this, null);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(18), dp(14), dp(18), dp(14));
        status.setBackground(new NeonDrawable(NeonDrawable.RAINBOW, density(), CARD_FILL));
        TextView on = text("● الاستلام شغال", 20, GREEN, true);
        on.setShadowLayer(dp(6), 0, 0, GREEN);
        status.addView(on, lp(-1, -2));
        status.addView(text(NetworkUtils.getDeviceName(), 15, TEXT, true), lp(-1, -2));
        addressesText = text("", 13, MUTED, false);
        status.addView(addressesText, lp(-1, -2));
        receiveStatusText = text("", 13, CYAN, false);
        status.addView(receiveStatusText, lp(-1, -2));
        content.addView(status, lp(-1, -2));
        refreshAddresses();
        content.addView(space(12));

        LinearLayout vbox = new LinearLayout(this);
        vbox.setOrientation(LinearLayout.VERTICAL);
        vbox.setPadding(dp(18), dp(10), dp(18), dp(12));
        vbox.setBackground(new NeonDrawable(NeonDrawable.WARM, density(), CARD_FILL));
        vnetSwitch = new Switch(this);
        vnetSwitch.setText("بدون راوتر · شبكة SatLink");
        vnetSwitch.setTextColor(TEXT);
        vnetSwitch.setTextSize(16);
        vnetSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        vnetSwitch.setChecked(ReceiveService.virtualNetworkWanted(this));
        vnetSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (updatingSwitch) return;
            ReceiveService.start(this, checked ? ReceiveService.ACTION_VNET_ON : ReceiveService.ACTION_VNET_OFF);
        });
        vbox.addView(vnetSwitch, lp(-1, dp(44)));
        vnetText = text("", 13, MUTED, false);
        vnetText.setOnClickListener(v -> copyPassword());
        vbox.addView(vnetText, lp(-1, -2));
        content.addView(vbox, lp(-1, -2));
        content.addView(space(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(neonButton("فتح المجلد", NeonDrawable.COOL, v -> openReceivedFolder()), weight());
        View stop = neonButton("إيقاف الاستلام", NeonDrawable.DANGER, v -> {
            ReceiveService.start(this, ReceiveService.ACTION_STOP);
            toast("تم إيقاف الاستلام");
            showHome();
        });
        actions.addView(stop, weight());
        content.addView(actions, lp(-1, dp(54)));
        content.addView(space(14));

        LinearLayout histHead = sectionHeader("الملفات المستلمة");
        TextView cancel = smallButton("إلغاء الجاري", v -> { ReceiveService.cancelCurrent(); toast("تم إلغاء النقل الجاري"); });
        cancel.setTextColor(RED);
        histHead.addView(cancel, lp(-2, dp(36)));
        histHead.addView(smallButton("مسح", v -> { TransferLog.clear(this, TransferLog.RECEIVE); renderHistory(); }), lp(-2, dp(36)));
        content.addView(histHead, lp(-1, -2));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyBox, lp(-1, -2));
        historyDirection = TransferLog.RECEIVE;
        renderHistory();
        ReceiveService.attach(this);
    }

    private void refreshAddresses() {
        if (addressesText == null) return;
        StringBuilder sb = new StringBuilder();
        for (String[] a : NetworkUtils.listAddresses()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(a[0]).append(": ").append(a[1]);
        }
        addressesText.setText(sb.length() == 0 ? "غير متصل بشبكة · شغّل «بدون راوتر» بالأسفل" : sb.toString());
    }

    private String vnetSsid = "", vnetPass = "";

    @Override public void onReceiveStatus(String status) {
        if (receiveStatusText != null) receiveStatusText.setText(status);
    }

    @Override public void onVirtualNetwork(boolean on, String ssid, String passphrase, String ip, String message) {
        vnetSsid = ssid == null ? "" : ssid;
        vnetPass = passphrase == null ? "" : passphrase;
        if (vnetSwitch != null) {
            updatingSwitch = true;
            vnetSwitch.setChecked(on || ReceiveService.virtualNetworkWanted(this) && (message != null && message.startsWith("جاري")));
            updatingSwitch = false;
        }
        if (vnetText == null) return;
        if (on && !vnetSsid.isEmpty()) {
            vnetText.setText("الهواتف تكتشفك تلقائياً بدون راوتر.\nللحاسبة: اتصل من الواي فاي بالشبكة\nالاسم: " + vnetSsid
                    + "\nكلمة المرور: " + vnetPass + "   (اضغط للنسخ)");
            vnetText.setTextColor(TEXT);
        } else if (on) {
            vnetText.setText(message == null ? "جاري التشغيل..." : message);
            vnetText.setTextColor(MUTED);
        } else {
            vnetText.setText(message == null || message.isEmpty() || "متوقفة".equals(message)
                    ? "متوقفة · الاستلام يعمل عبر الراوتر فقط" : message);
            vnetText.setTextColor(MUTED);
        }
        refreshAddresses();
    }

    private void copyPassword() {
        if (vnetPass.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SatLink", vnetPass));
        toast("تم نسخ كلمة المرور");
    }

    private void openReceivedFolder() {
        try {
            Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/SatLink Share PRO/Received");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return;
        } catch (Exception ignored) {}
        try {
            Intent intent = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
            startActivity(intent);
            toast("افتح مجلد SatLink Share PRO ثم Received");
        } catch (Exception e) {
            toast("مكان الحفظ: Downloads/SatLink Share PRO/Received");
        }
    }

    // ------------------------------------------------------------------- قائمة الملفات

    private void renderHistory() {
        if (historyBox == null || historyDirection == null) return;
        historyBox.removeAllViews();
        rows.clear();
        List<TransferLog.Entry> list = TransferLog.list(this, historyDirection);
        if (list.isEmpty()) {
            TextView empty = text(TransferLog.SEND.equals(historyDirection) ? "لم ترسل ملفات بعد" : "لم تستلم ملفات بعد", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(18), dp(12), dp(18));
            historyBox.addView(empty, lp(-1, -2));
            return;
        }
        int count = 0;
        for (TransferLog.Entry e : list) {
            if (++count > 120) break;
            historyBox.addView(historyRow(e), lp(-1, -2));
            historyBox.addView(space(8));
        }
    }

    private View historyRow(TransferLog.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(12));
        box.setBackground(new NeonDrawable(TransferLog.SEND.equals(e.direction) ? NeonDrawable.COOL : NeonDrawable.WARM, density(), CARD_FILL, 10));
        RowViews r = new RowViews();
        r.title = text("", 15, TEXT, true);
        r.title.setSingleLine(true);
        r.title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        box.addView(r.title, lp(-1, -2));
        r.sub = text("", 12, MUTED, false);
        box.addView(r.sub, lp(-1, -2));
        r.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        r.bar.setMax(100);
        r.bar.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams bp = lp(-1, dp(6));
        bp.topMargin = dp(8);
        box.addView(r.bar, bp);
        rows.put(e.id, r);
        bindRow(r, e);
        box.setOnClickListener(v -> openEntry(e));
        return box;
    }

    private void bindRow(RowViews r, TransferLog.Entry e) {
        String icon;
        int color;
        String state;
        switch (e.state) {
            case TransferLog.STATE_DONE: icon = "✓ "; color = GREEN; state = "مكتمل"; break;
            case TransferLog.STATE_CANCELLED: icon = "✕ "; color = RED; state = "ملغي"; break;
            case TransferLog.STATE_FAILED: icon = "! "; color = RED; state = "متوقف · أعد الإرسال ليكمل"; break;
            case TransferLog.STATE_PAUSED: icon = "Ⅱ "; color = Color.rgb(255, 200, 0); state = "متوقف · يكمل عند إعادة الإرسال"; break;
            case TransferLog.STATE_CONNECTING: icon = "… "; color = CYAN; state = "جاري الاتصال"; break;
            default: icon = "↻ "; color = CYAN; state = "جاري"; break;
        }
        r.title.setText(icon + e.name);
        StringBuilder sub = new StringBuilder();
        if (e.peer != null && !e.peer.isEmpty()) sub.append(TransferLog.SEND.equals(e.direction) ? "إلى " : "من ").append(e.peer).append(" · ");
        if (e.isActive()) {
            sub.append(e.percent()).append("% · ").append(NetworkUtils.formatBytes(e.done)).append(" / ").append(NetworkUtils.formatBytes(e.size));
            if (e.speed > 0) {
                sub.append(" · ").append(NetworkUtils.formatSpeed(e.speed));
                sub.append(" · متبقي ").append(eta((e.size - e.done) / Math.max(1, e.speed)));
            }
        } else {
            sub.append(state).append(" · ").append(NetworkUtils.formatBytes(e.size));
            if (!TransferLog.STATE_DONE.equals(e.state) && e.size > 0) sub.append(" · ").append(e.percent()).append("%");
        }
        r.sub.setText(sub.toString());
        r.sub.setTextColor(e.isActive() ? MUTED : color);
        r.bar.setProgress(e.percent());
    }

    private static String eta(long seconds) {
        if (seconds >= 3600) return (seconds / 3600) + "س " + ((seconds % 3600) / 60) + "د";
        if (seconds >= 60) return (seconds / 60) + "د " + (seconds % 60) + "ث";
        return seconds + "ث";
    }

    private void openEntry(TransferLog.Entry e) {
        if (!TransferLog.RECEIVE.equals(e.direction) || !TransferLog.STATE_DONE.equals(e.state) || e.uri == null) return;
        Uri uri = Uri.parse(e.uri);
        if (!"content".equals(uri.getScheme())) { openReceivedFolder(); return; }
        try {
            String type = getContentResolver().getType(uri);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, type == null ? FileStoreUtils.guessMimeType(e.name) : type);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "فتح " + e.name));
        } catch (Exception ex) {
            toast("لا يوجد تطبيق يفتح هذا الملف");
        }
    }

    @Override public void onTransferChanged(TransferLog.Entry entry, boolean isNew) {
        if (historyBox == null || !entry.direction.equals(historyDirection)) return;
        RowViews r = rows.get(entry.id);
        if (isNew || r == null) renderHistory();
        else bindRow(r, entry);
    }

    // ------------------------------------------------------------------- اختيار الملفات

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, REQ_PICK_FILES); }
        catch (Exception e) { toast("لا يوجد مدير ملفات على الجهاز"); }
    }

    private void openFolderPicker() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER); }
        catch (Exception e) { toast("اختيار المجلد غير مدعوم على هذا الجهاز"); }
    }

    private static class FolderNode {
        final String id; final String relative;
        FolderNode(String id, String relative) { this.id = id; this.relative = relative; }
    }

    private ArrayList<FileItem> listFolderFiles(Uri tree) throws Exception {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        String rootName = "Folder";
        try (Cursor c = getContentResolver().query(rootDoc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) rootName = c.getString(0);
        }
        rootName = FileStoreUtils.sanitizeRelativePath(FileStoreUtils.sanitizeFileName(rootName));
        ArrayList<FileItem> result = new ArrayList<>();
        ArrayDeque<FolderNode> pending = new ArrayDeque<>();
        pending.add(new FolderNode(rootId, rootName));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        while (!pending.isEmpty()) {
            FolderNode folder = pending.removeFirst();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.id);
            try (Cursor c = getContentResolver().query(children, columns, null, null, null)) {
                if (c == null) throw new java.io.IOException("تعذر قراءة محتويات المجلد");
                while (c.moveToNext()) {
                    String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (id == null || name == null) continue;
                    String relative = FileStoreUtils.sanitizeRelativePath(folder.relative + "/" + FileStoreUtils.sanitizeFileName(name));
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        pending.add(new FolderNode(id, relative));
                    } else {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        long size = c.isNull(3) ? -1 : c.getLong(3);
                        if (size < 0) size = FileStoreUtils.fromUri(this, fileUri).size;
                        if (size < 0) throw new java.io.IOException("حجم غير معروف: " + relative);
                        result.add(new FileItem(fileUri, relative, size));
                        if (result.size() > 10000) throw new java.io.IOException("المجلد يحتوي أكثر من 10000 ملف");
                    }
                }
            }
        }
        return result;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            toast("جاري قراءة المجلد...");
            new Thread(() -> {
                try {
                    ArrayList<FileItem> entries = listFolderFiles(tree);
                    ui.post(() -> {
                        if (entries.isEmpty()) { toast("المجلد ما يحتوي ملفات"); return; }
                        selectedFiles.clear();
                        selectedFiles.addAll(entries);
                        updateSelection();
                    });
                } catch (Exception e) { ui.post(() -> toast("تعذر قراءة المجلد: " + e.getMessage())); }
            }, "satlink-folder-scan").start();
            return;
        }
        if (requestCode == REQ_PICK_FILES) {
            selectedFiles.clear();
            if (data.getClipData() != null) {
                ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) addUri(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                addUri(data.getData());
            }
            updateSelection();
            if (!selectedFiles.isEmpty()) toast("اضغط على الجهاز المستلم");
        }
    }

    private void addUri(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        FileItem item = FileStoreUtils.fromUri(this, uri);
        if (item.size < 0) { toast("تعذر معرفة حجم: " + item.name); return; }
        selectedFiles.add(item);
    }

    // =================================================================== أدوات الواجهة

    private LinearLayout sectionHeader(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 18, TEXT, true);
        t.setShadowLayer(dp(6), 0, 0, Color.rgb(120, 80, 255));
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View neonButton(String label, int[] colors, View.OnClickListener click) {
        TextView b = text(label, 15, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new NeonDrawable(colors, density(), CARD_FILL, 10));
        b.setOnClickListener(click);
        pressable(b);
        return b;
    }

    private TextView smallButton(String label, View.OnClickListener click) {
        TextView b = text(label, 13, CYAN, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setOnClickListener(click);
        return b;
    }

    private Drawable progressDrawable() {
        GradientDrawable track = new GradientDrawable();
        track.setColor(Color.argb(50, 255, 255, 255));
        track.setCornerRadius(dp(3));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{Color.rgb(150, 70, 255), Color.rgb(40, 140, 255), Color.rgb(0, 220, 255), Color.rgb(80, 255, 120)});
        fill.setCornerRadius(dp(3));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private void pressable(View v) {
        v.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                if (view.getBackground() instanceof NeonDrawable) ((NeonDrawable) view.getBackground()).setPressed(true);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                if (view.getBackground() instanceof NeonDrawable) ((NeonDrawable) view.getBackground()).setPressed(false);
            }
            return false;
        });
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, dp(h)));
        return v;
    }

    private float density() { return getResources().getDisplayMetrics().density; }
    private int dp(int v) { return (int) (v * density() + 0.5f); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
