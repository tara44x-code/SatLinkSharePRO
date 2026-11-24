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
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * SatLink Share PRO — الرئيسية فيها إرسال واستلام فقط، والإعدادات من زر ⚙.
 * المطور: مهند سات لنك
 */
public class MainActivity extends Activity implements TransferLog.Listener, ReceiveService.UiListener, SendService.UiListener {
    public static final String EXTRA_SCREEN = "com.satlink.sharepro.SCREEN";
    private static final String DEVELOPER_AR = "مهند سات لنك";
    private static final String DEVELOPER_EN = "Muhannad SatLink";
    private static final int REQ_PICK_FILES = 3001;
    private static final int REQ_PICK_FOLDER = 3002;
    private static final int REQ_PICK_MEDIA = 3003;
    private static final int REQ_PERMISSIONS = 4401;

    private static final int CYAN = Color.rgb(0, 200, 240);
    private static final int GREEN = Color.rgb(40, 210, 110);
    private static final int RED = Color.rgb(255, 80, 100);
    private static final int AMBER = Color.rgb(245, 180, 0);

    // ألوان حسب الثيم
    private int BG, CARD_FILL, TEXT, MUTED;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String screen = "home";
    private LinearLayout content;

    // الإرسال
    private final ArrayList<FileItem> selectedFiles = new ArrayList<>();
    private final ArrayList<DeviceInfo> lanDevices = new ArrayList<>();
    private final ArrayList<VirtualNetwork.Target> p2pTargets = new ArrayList<>();
    private final HashSet<String> multiSelected = new HashSet<>();
    private SatLinkNsdHelper nsdHelper;
    private UdpDiscoveryHelper udpHelper;
    private VirtualNetwork vnet;
    private boolean searching;
    private TextView selectionText;
    private LinearLayout devicesBox;
    private TextView scanText;
    private TextView sendStatusText;
    private View cancelSendButton;
    private TextView multiSendButton;

    // الاستلام
    private TextView receiveStatusText;
    private TextView addressesText;
    private TextView vnetText;
    private Switch vnetSwitch;
    private boolean updatingSwitch;

    // قائمة الملفات
    private LinearLayout historyBox;
    private String historyDirection;
    private String historyFilter = "";
    private final HashMap<String, RowViews> rows = new HashMap<>();

    private static final class RowViews {
        TextView title, sub, meta, action; PartsBar bar;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        applyThemeColors();
        nsdHelper = new SatLinkNsdHelper(this);
        udpHelper = new UdpDiscoveryHelper(this);
        vnet = new VirtualNetwork(this);
        configureWindow();
        requestNeededPermissions();
        if (!handleIntent(getIntent())) showHome();
    }

    private void applyThemeColors() {
        if (L.light()) {
            BG = Color.rgb(238, 241, 248);
            CARD_FILL = Color.argb(235, 255, 255, 255);
            TEXT = Color.rgb(20, 22, 35);
            MUTED = Color.rgb(95, 102, 130);
        } else {
            BG = Color.rgb(4, 5, 10);
            CARD_FILL = Color.argb(215, 10, 12, 22);
            TEXT = Color.WHITE;
            MUTED = Color.rgb(165, 172, 200);
        }
    }

    private String developer() { return L.english() ? DEVELOPER_EN : DEVELOPER_AR; }

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
        if ("apps".equals(screen)) showSend();
        else if (!"home".equals(screen)) showHome();
        else super.onBackPressed();
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String target = intent.getStringExtra(EXTRA_SCREEN);
        if ("send".equals(target)) { showSend(); return true; }
        if ("receive".equals(target)) {
            showReceive();
            ReceiverServer.Request pendingRequest = ReceiveService.pendingRequest(intent.getStringExtra(ReceiveService.EXTRA_REQUEST));
            if (pendingRequest != null) onIncomingRequest(pendingRequest);
            return true;
        }
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
        if (!selectedFiles.isEmpty()) toast(L.t("اختر الجهاز المستلم", "Choose the receiving device"));
        return true;
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.getDecorView().setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 23)
            w.getDecorView().setSystemUiVisibility(L.light() ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
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
        if ("send".equals(screen)) startSearch();
        if ("receive".equals(screen)) ReceiveService.start(this, null);
    }

    // =================================================================== الهيكل

    private void frame(boolean scroll) {
        rows.clear();
        historyBox = null; historyDirection = null;
        devicesBox = null; scanText = null; selectionText = null; sendStatusText = null; cancelSendButton = null; multiSendButton = null;
        receiveStatusText = null; addressesText = null; vnetText = null; vnetSwitch = null;

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setLayoutDirection(L.english() ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
        rootFrame.setBackgroundColor(BG);
        if (!L.light()) {
            ImageView bg = new ImageView(this);
            bg.setImageResource(R.drawable.bg_neon);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            rootFrame.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        }
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

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(top, lp(-1, dp(48)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView settings = text("⚙", 26, TEXT, false);
        settings.setGravity(Gravity.CENTER);
        settings.setContentDescription(L.t("الإعدادات", "Settings"));
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings, lp(dp(48), dp(48)));

        TextView title = text("SatLink Share PRO", 26, TEXT, true);
        title.setGravity(Gravity.CENTER);
        if (!L.light()) title.setShadowLayer(dp(10), 0, 0, Color.rgb(120, 80, 255));
        content.addView(title, lp(-1, -2));
        TextView dev = text(L.t("المطور: ", "Developer: ") + developer(), 14, CYAN, true);
        dev.setGravity(Gravity.CENTER);
        content.addView(dev, lp(-1, -2));

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        content.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));
        middle.addView(cardButton(L.english() ? R.drawable.card_send_en : R.drawable.card_send, L.t("إرسال", "Send"), v -> showSend()), lp(-1, -2));
        middle.addView(space(18));
        middle.addView(cardButton(L.english() ? R.drawable.card_receive_en : R.drawable.card_receive, L.t("استلام", "Receive"), v -> showReceive()), lp(-1, -2));

        String state = (ReceiveService.isActive() ? L.t("الاستلام شغال بالخلفية", "Receiving in background") : "")
                + (SendService.isBusy() ? (ReceiveService.isActive() ? "  ·  " : "") + L.t("الإرسال شغال بالخلفية", "Sending in background") : "");
        TextView foot = text(state.isEmpty() ? L.t("بالراوتر أو بدون راوتر · مشفر · بدون إنترنت", "With or without a router · encrypted · offline") : state, 13, MUTED, false);
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
        TextView back = text(L.english() ? "‹" : "›", 38, TEXT, true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(L.t("رجوع", "Back"));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, lp(dp(44), -1));
        if (iconRes != 0) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            bar.addView(icon, lp(dp(64), dp(56)));
        }
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -1, 1));
        TextView t = text(title, 24, TEXT, true);
        if (!L.light()) t.setShadowLayer(dp(8), 0, 0, Color.rgb(140, 90, 255));
        titles.addView(t, lp(-1, -2));
        titles.addView(text(L.t("المطور: ", "Developer: ") + developer(), 12, CYAN, false), lp(-1, -2));
        content.addView(space(10));
    }

    // =================================================================== الإرسال

    private void showSend() {
        screen = "send";
        frame(true);
        header(L.t("إرسال", "Send"), R.drawable.ic_send_neon);

        // تبويبات: ملفات، صور، فيديو، تطبيقات، ألعاب، مجلد
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tabs);
        tabs.addView(tab("📄 " + L.t("ملفات", "Files"), NeonDrawable.COOL, v -> openFilePicker()), tabLp());
        tabs.addView(tab("🖼 " + L.t("صور", "Photos"), NeonDrawable.WARM, v -> openMediaPicker("image/*")), tabLp());
        tabs.addView(tab("🎬 " + L.t("فيديو", "Videos"), NeonDrawable.RAINBOW, v -> openMediaPicker("video/*")), tabLp());
        tabs.addView(tab("📦 " + L.t("تطبيقات", "Apps"), NeonDrawable.COOL, v -> showApps(false)), tabLp());
        tabs.addView(tab("🎮 " + L.t("ألعاب", "Games"), NeonDrawable.WARM, v -> showApps(true)), tabLp());
        tabs.addView(tab("📁 " + L.t("مجلد", "Folder"), NeonDrawable.RAINBOW, v -> openFolderPicker()), tabLp());
        content.addView(hs, lp(-1, dp(52)));
        content.addView(space(8));

        selectionText = text("", 14, MUTED, false);
        selectionText.setPadding(dp(6), 0, dp(6), 0);
        selectionText.setOnClickListener(v -> {
            if (!selectedFiles.isEmpty()) { selectedFiles.clear(); updateSelection(); toast(L.t("تم مسح الاختيار", "Selection cleared")); }
        });
        content.addView(selectionText, lp(-1, -2));
        updateSelection();
        content.addView(space(14));

        LinearLayout devHead = sectionHeader(L.t("الأجهزة القريبة", "Nearby devices"));
        devHead.addView(smallButton(L.t("IP يدوي", "Manual IP"), v -> showManualIpDialog()), lp(-2, dp(36)));
        devHead.addView(smallButton("↻ " + L.t("بحث", "Scan"), v -> startSearch()), lp(-2, dp(36)));
        content.addView(devHead, lp(-1, -2));
        scanText = text("", 13, MUTED, false);
        content.addView(scanText, lp(-1, -2));
        content.addView(space(6));
        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(devicesBox, lp(-1, -2));
        multiSendButton = (TextView) neonButton("", NeonDrawable.RAINBOW, v -> sendToSelected());
        multiSendButton.setVisibility(View.GONE);
        content.addView(multiSendButton, lp(-1, dp(54)));
        renderDevices();
        content.addView(space(14));

        LinearLayout histHead = sectionHeader(L.t("الملفات المرسلة", "Sent files"));
        TextView cancel = smallButton(L.t("إلغاء", "Cancel"), v -> { SendService.cancel(); toast(L.t("تم طلب الإلغاء", "Cancelling")); });
        cancel.setTextColor(RED);
        cancelSendButton = cancel;
        cancel.setVisibility(SendService.isBusy() ? View.VISIBLE : View.GONE);
        histHead.addView(cancel, lp(-2, dp(36)));
        histHead.addView(smallButton(L.t("أكمل الكل", "Resume all"), v -> resumeAll()), lp(-2, dp(36)));
        histHead.addView(smallButton(L.t("مسح", "Clear"), v -> { TransferLog.clear(this, TransferLog.SEND); renderHistory(); }), lp(-2, dp(36)));
        content.addView(histHead, lp(-1, -2));
        sendStatusText = text("", 13, MUTED, false);
        content.addView(sendStatusText, lp(-1, -2));
        addSearchBox();
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(historyBox, lp(-1, -2));
        historyDirection = TransferLog.SEND;
        renderHistory();
        SendService.attach(this);
        startSearch();
    }

    private View tab(String label, int[] colors, View.OnClickListener click) {
        TextView b = text(label, 14, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setBackground(new NeonDrawable(colors, density(), CARD_FILL, 8));
        b.setOnClickListener(click);
        pressable(b);
        return b;
    }

    private LinearLayout.LayoutParams tabLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -1);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private void updateSelection() {
        if (selectionText == null) return;
        if (selectedFiles.isEmpty()) {
            selectionText.setText(L.t("اختر ملفات أو صور أو تطبيقات، ثم اضغط على الجهاز المستلم وسيبدأ الإرسال",
                    "Pick files, photos or apps, then tap the receiving device to start"));
            selectionText.setTextColor(MUTED);
            return;
        }
        long total = 0;
        for (FileItem f : selectedFiles) total += Math.max(0, f.size);
        String first = selectedFiles.get(0).name;
        String what = selectedFiles.size() == 1 ? first : selectedFiles.size() + L.t(" ملف", " files");
        selectionText.setText("✓ " + what + " · " + NetworkUtils.formatBytes(total) + L.t("   (اضغط هنا للمسح)", "   (tap to clear)"));
        selectionText.setTextColor(GREEN);
    }

    private void startSearch() {
        if (!"send".equals(screen)) return;
        stopSearch();
        searching = true;
        lanDevices.clear();
        p2pTargets.clear();
        multiSelected.clear();
        renderDevices();
        setScan(L.t("جاري البحث على الراوتر وعلى شبكة SatLink...", "Searching the router and the SatLink network..."));
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
                setScan(L.t("لم يظهر جهاز بعد. افتح «استلام» في الجهاز الثاني، أو استخدم IP يدوي.",
                        "No device yet. Open “Receive” on the other device, or use Manual IP."));
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
            boolean selected = multiSelected.contains(d.key());
            View row = deviceRow(d.name, (selected ? "✓ " : "") + L.t("عبر الراوتر · ", "via router · ") + d.hostIp,
                    selected ? NeonDrawable.RAINBOW : NeonDrawable.COOL, v -> {
                        if (!multiSelected.isEmpty()) toggleMulti(d); else sendTo(d);
                    });
            row.setOnLongClickListener(v -> { toggleMulti(d); return true; });
            devicesBox.addView(row, lp(-1, dp(72)));
            devicesBox.addView(space(8));
            shown++;
        }
        for (VirtualNetwork.Target t : p2pTargets) {
            boolean dup = false;
            for (DeviceInfo d : lanDevices) if (cleanName(d.name).equals(cleanName(t.name))) { dup = true; break; }
            if (dup) continue;
            devicesBox.addView(deviceRow(t.name, L.t("بدون راوتر · شبكة SatLink", "no router · SatLink network"), NeonDrawable.WARM, v -> sendViaVirtualNetwork(t)), lp(-1, dp(72)));
            devicesBox.addView(space(8));
            shown++;
        }
        if (shown == 0) {
            TextView empty = text(L.t("لا توجد أجهزة بعد…\nافتح «استلام» في الهاتف أو الحاسبة الثانية.", "No devices yet…\nOpen “Receive” on the other phone or PC."), 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            empty.setBackground(new NeonDrawable(NeonDrawable.COOL, density(), CARD_FILL));
            devicesBox.addView(empty, lp(-1, -2));
        } else {
            setScan(lanDevices.size() > 1
                    ? L.t("اضغط على الجهاز ليبدأ الإرسال · ضغطة مطولة لاختيار أكثر من جهاز", "Tap a device to send · long-press to pick several")
                    : L.t("اضغط على الجهاز ليبدأ الإرسال", "Tap a device to send"));
        }
        if (multiSendButton != null) {
            multiSendButton.setVisibility(multiSelected.isEmpty() ? View.GONE : View.VISIBLE);
            multiSendButton.setText(L.t("إرسال للأجهزة المحددة (", "Send to selected (") + multiSelected.size() + ")");
        }
    }

    private void toggleMulti(DeviceInfo d) {
        if (!multiSelected.remove(d.key())) multiSelected.add(d.key());
        renderDevices();
    }

    private View deviceRow(String name, String sub, int[] colors, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(6), dp(16), dp(6));
        box.setBackground(new NeonDrawable(colors, density(), CARD_FILL));
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
        TextView go = text(L.t("إرسال ‹", "Send ›"), 14, CYAN, true);
        go.setGravity(Gravity.CENTER);
        box.addView(go, lp(-2, -1));
        box.setOnClickListener(click);
        pressable(box);
        return box;
    }

    private boolean ensureFiles() {
        if (selectedFiles.isEmpty()) { toast(L.t("اختر ملفات أولاً", "Pick files first")); openFilePicker(); return false; }
        return true;
    }

    private void sendTo(DeviceInfo d) {
        if (!ensureFiles()) return;
        SendService.send(this, d, selectedFiles);
        selectedFiles.clear();
        updateSelection();
        toast(L.t("بدأ الإرسال إلى ", "Sending to ") + d.name);
    }

    private void sendToSelected() {
        if (!ensureFiles()) return;
        int n = 0;
        for (DeviceInfo d : lanDevices) if (multiSelected.contains(d.key())) { SendService.send(this, d, selectedFiles); n++; }
        selectedFiles.clear();
        multiSelected.clear();
        updateSelection();
        renderDevices();
        toast(L.t("بدأ الإرسال إلى ", "Sending to ") + n + L.t(" أجهزة", " devices"));
    }

    private void sendViaVirtualNetwork(VirtualNetwork.Target t) {
        if (!ensureFiles()) return;
        final ArrayList<FileItem> files = new ArrayList<>(selectedFiles);
        selectedFiles.clear();
        updateSelection();
        ReceiveService.pauseVirtualNetwork();
        stopSearch();
        setScan(L.t("جاري الاتصال بشبكة ", "Connecting to ") + t.name + " ...");
        final boolean[] done = {false};
        vnet.connect(t, new VirtualNetwork.Callback() {
            @Override public void onLog(String m) { setScan(m); }
            @Override public void onConnected(String ownerIp, boolean iAmOwner) {
                if (done[0]) return;
                done[0] = true;
                if (ownerIp == null || iAmOwner) {
                    setScan(L.t("تم الاتصال. اضغط بحث واختر الجهاز من القائمة", "Connected. Tap Scan and pick the device"));
                    selectedFiles.addAll(files); updateSelection();
                    startSearch();
                    return;
                }
                setScan(L.t("متصل بدون راوتر · بدأ الإرسال", "Connected without router · sending"));
                SendService.send(MainActivity.this, new DeviceInfo(t.name, ownerIp, t.port), files);
            }
            @Override public void onDisconnected() {
                if (done[0]) return;
                done[0] = true;
                setScan(L.t("تعذر الاتصال بشبكة SatLink. قرّب الجهازين وحاول مجدداً", "Could not join the SatLink network. Move closer and retry"));
                selectedFiles.addAll(files); updateSelection();
            }
        });
        ui.postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                setScan(L.t("انتهت مهلة الاتصال. تأكد أن الاستلام مفتوح بالجهاز الثاني", "Timed out. Make sure Receive is open on the other device"));
                selectedFiles.addAll(files); updateSelection();
            }
        }, 30000);
    }

    private void showManualIpDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        input.setHint("192.168.1.20");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L.t("عنوان IP للجهاز المستلم", "Receiver IP address"))
                .setMessage(L.t("العنوان ظاهر في شاشة الاستلام بالجهاز الثاني.", "It is shown on the Receive screen of the other device."))
                .setView(input)
                .setPositiveButton(L.t("إضافة", "Add"), null)
                .setNegativeButton(L.t("إلغاء", "Cancel"), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = input.getText().toString().trim();
            if (!isIpv4(ip)) { toast(L.t("اكتب عنوان IPv4 صحيح", "Enter a valid IPv4 address")); return; }
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

    // ---------------------------------------------------------------- الإكمال بضغطة

    private boolean resumable(TransferLog.Entry e) {
        return TransferLog.SEND.equals(e.direction) && !e.isActive() && !TransferLog.STATE_DONE.equals(e.state)
                && e.source != null && e.targetIp != null && e.targetPort > 0;
    }

    private void resumeEntries(List<TransferLog.Entry> list) {
        LinkedHashMap<String, ArrayList<FileItem>> byTarget = new LinkedHashMap<>();
        LinkedHashMap<String, DeviceInfo> targets = new LinkedHashMap<>();
        for (TransferLog.Entry e : list) {
            if (!resumable(e)) continue;
            String key = e.targetIp + ":" + e.targetPort;
            targets.put(key, new DeviceInfo(e.peer, e.targetIp, e.targetPort));
            ArrayList<FileItem> files = byTarget.get(key);
            if (files == null) { files = new ArrayList<>(); byTarget.put(key, files); }
            files.add(new FileItem(Uri.parse(e.source), e.name, e.size));
            TransferLog.remove(this, e.id);
        }
        if (byTarget.isEmpty()) { toast(L.t("لا توجد ملفات متوقفة", "Nothing to resume")); return; }
        for (String key : byTarget.keySet()) SendService.send(this, targets.get(key), byTarget.get(key));
        toast(L.t("جاري الإكمال من آخر نقطة", "Resuming from where it stopped"));
        renderHistory();
    }

    private void resumeAll() {
        resumeEntries(TransferLog.list(this, TransferLog.SEND));
    }

    @Override public void onSendStatus(String status, boolean busy) {
        if (codeDialog != null && codeDialog.isShowing() && status != null && !status.contains(L.t("رمز التحقق", "Verification code"))) {
            try { codeDialog.dismiss(); } catch (Exception ignored) {}
        }
        if (sendStatusText != null) sendStatusText.setText(status == null ? "" : status);
        if (cancelSendButton != null) cancelSendButton.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- التطبيقات والألعاب

    private void showApps(boolean games) {
        screen = "apps";
        stopSearch();
        frame(true);
        header(games ? L.t("ألعاب", "Games") : L.t("تطبيقات", "Apps"), 0);
        TextView hint = text(L.t("جاري تحميل القائمة…", "Loading…"), 14, MUTED, false);
        content.addView(hint, lp(-1, -2));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, lp(-1, -2));
        final ArrayList<AppsCatalog.App> chosen = new ArrayList<>();
        final TextView done = (TextView) neonButton(L.t("إضافة للإرسال", "Add to send"), NeonDrawable.RAINBOW, v -> {
            for (AppsCatalog.App a : chosen) selectedFiles.addAll(AppsCatalog.toFiles(a));
            showSend();
            if (!chosen.isEmpty()) toast(L.t("اضغط على الجهاز المستلم", "Tap the receiving device"));
        });
        new Thread(() -> {
            List<AppsCatalog.App> apps = AppsCatalog.load(this);
            ui.post(() -> {
                if (!"apps".equals(screen)) return;
                int count = 0;
                for (AppsCatalog.App a : apps) {
                    if (games && !a.game) continue;
                    count++;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(6), dp(12), dp(6));
                    row.setBackground(new NeonDrawable(NeonDrawable.COOL, density(), CARD_FILL, 8));
                    ImageView icon = new ImageView(this);
                    icon.setImageDrawable(a.icon);
                    row.addView(icon, lp(dp(40), dp(40)));
                    LinearLayout texts = new LinearLayout(this);
                    texts.setOrientation(LinearLayout.VERTICAL);
                    texts.setPadding(dp(10), 0, dp(10), 0);
                    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView name = text(a.label, 15, TEXT, true);
                    name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
                    texts.addView(name, lp(-1, -2));
                    texts.addView(text(NetworkUtils.formatBytes(a.size) + (a.splits != null && a.splits.length > 0 ? L.t(" · مقسّم", " · split APK") : ""), 12, MUTED, false), lp(-1, -2));
                    CheckBox cb = new CheckBox(this);
                    row.addView(cb, lp(-2, -2));
                    cb.setOnCheckedChangeListener((b, c) -> {
                        if (c) chosen.add(a); else chosen.remove(a);
                        done.setText(L.t("إضافة للإرسال (", "Add to send (") + chosen.size() + ")");
                    });
                    row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
                    list.addView(row, lp(-1, dp(62)));
                    list.addView(space(6));
                }
                hint.setText(count == 0 ? L.t("لا توجد عناصر", "Nothing found")
                        : L.t("اختر ثم اضغط «إضافة للإرسال». التطبيقات المقسّمة تُرسل كمجلد للتثبيت ببرنامج مثل SAI.",
                        "Select, then tap “Add to send”. Split apps are sent as a folder (install with an app like SAI)."));
                content.addView(space(8));
                content.addView(done, lp(-1, dp(56)));
            });
        }, "satlink-apps").start();
    }

    // =================================================================== الاستلام

    private void showReceive() {
        screen = "receive";
        stopSearch();
        frame(true);
        header(L.t("استلام", "Receive"), R.drawable.ic_receive_neon);
        ReceiveService.start(this, null);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(dp(18), dp(14), dp(18), dp(14));
        status.setBackground(new NeonDrawable(NeonDrawable.RAINBOW, density(), CARD_FILL));
        TextView on = text("● " + L.t("الاستلام شغال", "Receiving is on"), 20, GREEN, true);
        status.addView(on, lp(-1, -2));
        status.addView(text(NetworkUtils.getDeviceName(), 15, TEXT, true), lp(-1, -2));
        addressesText = text("", 13, MUTED, false);
        status.addView(addressesText, lp(-1, -2));
        receiveStatusText = text("", 13, CYAN, false);
        status.addView(receiveStatusText, lp(-1, -2));
        TextView ask = text(ReceiveService.askBeforeReceiving(this)
                ? L.t("🔒 مشفر · يسألك قبل الاستلام من جهاز جديد", "🔒 Encrypted · asks before accepting a new device")
                : L.t("🔒 مشفر · يقبل كل الأجهزة (غيّرها من الإعدادات)", "🔒 Encrypted · accepts every device (change in Settings)"), 12, MUTED, false);
        status.addView(ask, lp(-1, -2));
        content.addView(status, lp(-1, -2));
        refreshAddresses();
        content.addView(space(12));

        LinearLayout vbox = new LinearLayout(this);
        vbox.setOrientation(LinearLayout.VERTICAL);
        vbox.setPadding(dp(18), dp(10), dp(18), dp(12));
        vbox.setBackground(new NeonDrawable(NeonDrawable.WARM, density(), CARD_FILL));
        vnetSwitch = new Switch(this);
        vnetSwitch.setText(L.t("بدون راوتر · شبكة SatLink", "No router · SatLink network"));
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
        actions.addView(neonButton(L.t("فتح المجلد", "Open folder"), NeonDrawable.COOL, v -> openReceivedFolder()), weight());
        actions.addView(neonButton(L.t("إيقاف الاستلام", "Stop receiving"), NeonDrawable.DANGER, v -> {
            ReceiveService.start(this, ReceiveService.ACTION_STOP);
            toast(L.t("تم إيقاف الاستلام", "Receiving stopped"));
            showHome();
        }), weight());
        content.addView(actions, lp(-1, dp(54)));
        content.addView(space(14));

        LinearLayout histHead = sectionHeader(L.t("الملفات المستلمة", "Received files"));
        TextView cancel = smallButton(L.t("إلغاء الجاري", "Cancel current"), v -> { ReceiveService.cancelCurrent(); toast(L.t("تم إلغاء النقل الجاري", "Cancelled")); });
        cancel.setTextColor(RED);
        histHead.addView(cancel, lp(-2, dp(36)));
        histHead.addView(smallButton(L.t("مسح", "Clear"), v -> { TransferLog.clear(this, TransferLog.RECEIVE); renderHistory(); }), lp(-2, dp(36)));
        content.addView(histHead, lp(-1, -2));
        addSearchBox();
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
            String type = L.english() ? (a[0].equals("الراوتر") ? "Router" : a[0].equals("شبكة SatLink") ? "SatLink network" : a[0]) : a[0];
            sb.append(type).append(": ").append(a[1]);
        }
        addressesText.setText(sb.length() == 0 ? L.t("غير متصل بشبكة · شغّل «بدون راوتر» بالأسفل", "Not connected · turn on “No router” below") : sb.toString());
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
            vnetText.setText(L.t("الهواتف تكتشفك تلقائياً بدون راوتر.\nللحاسبة: اتصل من الواي فاي بالشبكة\nالاسم: ", "Phones find you automatically without a router.\nFor a PC: join this Wi‑Fi network\nName: ")
                    + vnetSsid + L.t("\nكلمة المرور: ", "\nPassword: ") + vnetPass + L.t("   (اضغط للنسخ)", "   (tap to copy)"));
            vnetText.setTextColor(TEXT);
        } else if (on) {
            vnetText.setText(message == null ? L.t("جاري التشغيل...", "Starting...") : message);
            vnetText.setTextColor(MUTED);
        } else {
            vnetText.setText(message == null || message.isEmpty() || "متوقفة".equals(message)
                    ? L.t("متوقفة · الاستلام يعمل عبر الراوتر فقط", "Off · receiving over the router only") : message);
            vnetText.setTextColor(MUTED);
        }
        refreshAddresses();
    }

    private void copyPassword() {
        if (vnetPass.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("SatLink", vnetPass));
        toast(L.t("تم نسخ كلمة المرور", "Password copied"));
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
            startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
            toast(L.t("افتح مجلد SatLink Share PRO ثم Received", "Open SatLink Share PRO, then Received"));
        } catch (Exception e) {
            toast("Downloads/SatLink Share PRO/Received");
        }
    }

    // ---------------------------------------------------------------- الموافقة ورمز التحقق

    private AlertDialog requestDialog;
    private String requestDialogId;

    @Override public void onIncomingRequest(ReceiverServer.Request r) {
        if (r == null || r.id.equals(requestDialogId)) return;
        if (requestDialog != null) try { requestDialog.dismiss(); } catch (Exception ignored) {}
        requestDialogId = r.id;
        StringBuilder msg = new StringBuilder();
        msg.append(r.count).append(L.t(" ملف · ", " files · ")).append(NetworkUtils.formatBytes(r.total));
        for (String f : r.files) msg.append("\n• ").append(f);
        if (r.count > r.files.size()) msg.append("\n…");
        if (r.code != null) msg.append("\n\n").append(L.t("رمز التحقق: ", "Verification code: ")).append(r.code)
                .append(L.t("\nتأكد أنه نفس الرمز الظاهر على الجهاز المرسل.", "\nMake sure the sender shows the same code."));
        else msg.append("\n\n").append(L.t("⚠ الجهاز المرسل بنسخة قديمة (بدون تشفير).", "⚠ The sender uses an old version (not encrypted)."));
        requestDialog = new AlertDialog.Builder(this)
                .setTitle(L.t("طلب استلام من ", "Files from ") + r.sender)
                .setMessage(msg.toString())
                .setPositiveButton(L.t("قبول", "Accept"), (d, w) -> ReceiveService.decide(r.id, ReceiverServer.DECISION_ACCEPT))
                .setNeutralButton(L.t("قبول دائماً", "Always accept"), (d, w) -> ReceiveService.decide(r.id, r.legacy ? ReceiverServer.DECISION_ACCEPT : ReceiverServer.DECISION_TRUST))
                .setNegativeButton(L.t("رفض", "Decline"), (d, w) -> ReceiveService.decide(r.id, ReceiverServer.DECISION_REJECT))
                .setCancelable(false)
                .create();
        requestDialog.setOnDismissListener(d -> requestDialogId = null);
        requestDialog.show();
    }

    private AlertDialog codeDialog;

    @Override public void onVerifyCode(String peer, String code) {
        if (codeDialog != null) try { codeDialog.dismiss(); } catch (Exception ignored) {}
        TextView big = text(code, 44, CYAN, true);
        big.setGravity(Gravity.CENTER);
        big.setLetterSpacing(0.3f);
        big.setPadding(0, dp(16), 0, dp(8));
        codeDialog = new AlertDialog.Builder(this)
                .setTitle(L.t("بانتظار موافقة ", "Waiting for ") + peer)
                .setMessage(L.t("تأكد أن نفس الرمز ظاهر على جهاز الاستلام، ثم وافق هناك.", "Check that the receiving device shows the same code, then accept there."))
                .setView(big)
                .setPositiveButton(L.t("تمام", "OK"), null)
                .create();
        codeDialog.show();
    }

    // =================================================================== الإعدادات

    private void showSettings() {
        screen = "settings";
        stopSearch();
        frame(true);
        header(L.t("الإعدادات", "Settings"), 0);

        content.addView(sectionHeader(L.t("المظهر", "Appearance")), lp(-1, -2));
        LinearLayout lang = new LinearLayout(this);
        lang.setOrientation(LinearLayout.HORIZONTAL);
        lang.addView(choice("العربية", !L.english(), v -> { L.setEnglish(this, false); restartUi(); }), weight());
        lang.addView(choice("English", L.english(), v -> { L.setEnglish(this, true); restartUi(); }), weight());
        content.addView(lang, lp(-1, dp(50)));
        content.addView(space(8));
        LinearLayout theme = new LinearLayout(this);
        theme.setOrientation(LinearLayout.HORIZONTAL);
        theme.addView(choice("🌙 " + L.t("داكن", "Dark"), !L.light(), v -> { L.setLight(this, false); restartUi(); }), weight());
        theme.addView(choice("☀ " + L.t("فاتح", "Light"), L.light(), v -> { L.setLight(this, true); restartUi(); }), weight());
        content.addView(theme, lp(-1, dp(50)));
        content.addView(space(16));

        content.addView(sectionHeader(L.t("الأمان والنقل", "Security & transfer")), lp(-1, -2));
        content.addView(settingSwitch(L.t("اسأل قبل الاستلام من جهاز جديد", "Ask before receiving from a new device"),
                ReceiveService.askBeforeReceiving(this), c -> ReceiveService.setAskBeforeReceiving(this, c)), lp(-1, -2));
        android.content.SharedPreferences prefs = getSharedPreferences("satlink_share_pro", MODE_PRIVATE);
        content.addView(settingSwitch(L.t("تسريع الملفات الكبيرة (عدة اتصالات)", "Speed up large files (several connections)"),
                prefs.getBoolean("parallel", true), c -> prefs.edit().putBoolean("parallel", c).apply()), lp(-1, -2));
        content.addView(space(16));

        content.addView(sectionHeader(L.t("الأجهزة الموثوقة", "Trusted devices")), lp(-1, -2));
        List<String[]> trusted = TrustStore.trustedSenders(this);
        if (trusted.isEmpty()) {
            content.addView(text(L.t("لا يوجد. اضغط «قبول دائماً» عند الاستلام حتى يُضاف الجهاز هنا.",
                    "None yet. Tap “Always accept” when receiving to add a device."), 13, MUTED, false), lp(-1, -2));
        }
        for (String[] t : trusted) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(10), 0);
            row.setBackground(new NeonDrawable(NeonDrawable.COOL, density(), CARD_FILL, 8));
            TextView n = text("✓ " + t[1], 15, TEXT, true);
            row.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            TextView rm = smallButton(L.t("حذف", "Remove"), v -> { TrustStore.forgetSender(this, t[0]); showSettings(); });
            rm.setTextColor(RED);
            row.addView(rm, lp(-2, dp(40)));
            content.addView(row, lp(-1, dp(54)));
            content.addView(space(6));
        }
        content.addView(space(16));

        content.addView(sectionHeader(L.t("حول التطبيق", "About")), lp(-1, -2));
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        content.addView(text("SatLink Share PRO " + version + "\n" + L.t("المطور: ", "Developer: ") + developer()
                + "\n" + L.t("نقل مشفر بين الهاتف والحاسبة، بالراوتر أو بدونه.", "Encrypted transfers between phones and PCs, with or without a router."), 14, MUTED, false), lp(-1, -2));
    }

    private interface BoolSetter { void set(boolean value); }

    private View settingSwitch(String label, boolean value, BoolSetter setter) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(TEXT);
        sw.setTextSize(15);
        sw.setChecked(value);
        sw.setPadding(dp(14), dp(10), dp(14), dp(10));
        sw.setBackground(new NeonDrawable(NeonDrawable.COOL, density(), CARD_FILL, 8));
        sw.setOnCheckedChangeListener((b, c) -> setter.set(c));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(sw, lp(-1, -2));
        wrap.addView(space(6));
        return wrap;
    }

    private View choice(String label, boolean selected, View.OnClickListener click) {
        TextView b = text(label, 15, selected ? TEXT : MUTED, selected);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new NeonDrawable(selected ? NeonDrawable.RAINBOW : new int[]{Color.rgb(90, 95, 120)}, density(), CARD_FILL, 8));
        b.setOnClickListener(click);
        return b;
    }

    private void restartUi() {
        applyThemeColors();
        configureWindow();
        showSettings();
    }

    // =================================================================== قائمة الملفات

    private void addSearchBox() {
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("🔍 " + L.t("بحث بالاسم أو الجهاز", "Search by name or device"));
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(14);
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        search.setBackground(new NeonDrawable(new int[]{Color.rgb(90, 95, 140)}, density(), CARD_FILL, 6));
        search.setText(historyFilter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { historyFilter = s.toString().trim(); renderHistory(); }
        });
        content.addView(space(6));
        content.addView(search, lp(-1, dp(46)));
        content.addView(space(8));
    }

    private void renderHistory() {
        if (historyBox == null || historyDirection == null) return;
        historyBox.removeAllViews();
        rows.clear();
        List<TransferLog.Entry> list = TransferLog.list(this, historyDirection);
        String filter = historyFilter.toLowerCase(Locale.ROOT);
        int count = 0;
        for (TransferLog.Entry e : list) {
            if (!filter.isEmpty() && !e.name.toLowerCase(Locale.ROOT).contains(filter)
                    && (e.peer == null || !e.peer.toLowerCase(Locale.ROOT).contains(filter))) continue;
            if (++count > 150) break;
            historyBox.addView(historyRow(e), lp(-1, -2));
            historyBox.addView(space(8));
        }
        if (count == 0) {
            TextView empty = text(!filter.isEmpty() ? L.t("لا توجد نتائج", "No results")
                    : TransferLog.SEND.equals(historyDirection) ? L.t("لم ترسل ملفات بعد", "Nothing sent yet") : L.t("لم تستلم ملفات بعد", "Nothing received yet"), 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(18), dp(12), dp(18));
            historyBox.addView(empty, lp(-1, -2));
        }
    }

    private View historyRow(TransferLog.Entry e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(12));
        box.setBackground(new NeonDrawable(TransferLog.SEND.equals(e.direction) ? NeonDrawable.COOL : NeonDrawable.WARM, density(), CARD_FILL, 10));
        RowViews r = new RowViews();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        r.title = text("", 15, TEXT, true);
        r.title.setSingleLine(true);
        r.title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        top.addView(r.title, new LinearLayout.LayoutParams(0, -2, 1));
        r.action = smallButton(L.t("أكمل ↻", "Resume ↻"), v -> {
            TransferLog.Entry current = TransferLog.get(e.id);
            ArrayList<TransferLog.Entry> one = new ArrayList<>();
            one.add(current == null ? e : current);
            resumeEntries(one);
        });
        top.addView(r.action, lp(-2, dp(34)));
        box.addView(top, lp(-1, -2));
        r.sub = text("", 12, MUTED, false);
        box.addView(r.sub, lp(-1, -2));
        r.meta = text("", 11, MUTED, false);
        box.addView(r.meta, lp(-1, -2));
        r.bar = new PartsBar(this, L.light());
        LinearLayout.LayoutParams bp = lp(-1, dp(7));
        bp.topMargin = dp(8);
        box.addView(r.bar, bp);
        rows.put(e.id, r);
        bindRow(r, e);
        box.setOnClickListener(v -> openEntry(e));
        return box;
    }

    private void bindRow(RowViews r, TransferLog.Entry e) {
        String icon, state;
        int color;
        switch (e.state) {
            case TransferLog.STATE_DONE: icon = "✓ "; color = GREEN; state = L.t("مكتمل", "Done"); break;
            case TransferLog.STATE_CANCELLED: icon = "✕ "; color = RED; state = L.t("ملغي", "Cancelled"); break;
            case TransferLog.STATE_REJECTED: icon = "⛔ "; color = RED; state = L.t("رفضه المستلم", "Declined by receiver"); break;
            case TransferLog.STATE_FAILED: icon = "! "; color = RED; state = L.t("متوقف", "Stopped"); break;
            case TransferLog.STATE_PAUSED: icon = "Ⅱ "; color = AMBER; state = L.t("متوقف · يكمل عند إعادة الإرسال", "Paused · resumes when sent again"); break;
            case TransferLog.STATE_CONNECTING: icon = "… "; color = CYAN; state = L.t("بانتظار الموافقة", "Waiting for approval"); break;
            default: icon = "↻ "; color = CYAN; state = L.t("جاري", "In progress"); break;
        }
        r.title.setText(icon + e.name);
        StringBuilder sub = new StringBuilder();
        if (e.peer != null && !e.peer.isEmpty())
            sub.append(TransferLog.SEND.equals(e.direction) ? L.t("إلى ", "to ") : L.t("من ", "from ")).append(e.peer).append(" · ");
        if (e.isActive() && e.parts != null && e.parts.length > 1)
            sub.append("⚡ ").append(e.parts.length).append(L.t(" أجزاء · ", " parts · "));
        if (e.isActive()) {
            sub.append(e.percent()).append("% · ").append(NetworkUtils.formatBytes(e.done)).append(" / ").append(NetworkUtils.formatBytes(e.size));
            if (e.speed > 0) {
                sub.append(" · ").append(NetworkUtils.formatSpeed(e.speed));
                sub.append(" · ").append(L.t("متبقي ", "left ")).append(eta((e.size - e.done) / Math.max(1, e.speed)));
            }
        } else {
            sub.append(state).append(" · ").append(NetworkUtils.formatBytes(e.size));
            if (!TransferLog.STATE_DONE.equals(e.state) && e.size > 0) sub.append(" · ").append(e.percent()).append("%");
        }
        r.sub.setText(sub.toString());
        r.sub.setTextColor(e.isActive() ? MUTED : color);
        String when = new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(new Date(e.time));
        String meta = when;
        if (TransferLog.STATE_DONE.equals(e.state) && e.endTime > e.time && e.size > 0) {
            long ms = e.endTime - e.time;
            meta += " · " + L.t("المدة ", "took ") + eta(Math.max(1, ms / 1000)) + " · " + L.t("السرعة ", "avg ") + NetworkUtils.formatSpeed(e.size * 1000 / Math.max(1, ms));
        }
        r.meta.setText(meta);
        r.bar.set(e.percent(), e.isActive() ? e.parts : null);
        r.action.setVisibility(resumable(e) ? View.VISIBLE : View.GONE);
    }

    private static String eta(long seconds) {
        if (L.english()) {
            if (seconds >= 3600) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
            return seconds + "s";
        }
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
            startActivity(Intent.createChooser(view, e.name));
        } catch (Exception ex) {
            toast(L.t("لا يوجد تطبيق يفتح هذا الملف", "No app can open this file"));
        }
    }

    @Override public void onTransferChanged(TransferLog.Entry entry, boolean isNew) {
        if (historyBox == null || !entry.direction.equals(historyDirection)) return;
        RowViews r = rows.get(entry.id);
        if (isNew || r == null) renderHistory();
        else bindRow(r, entry);
    }

    // =================================================================== اختيار الملفات

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, REQ_PICK_FILES); }
        catch (Exception e) { toast(L.t("لا يوجد مدير ملفات على الجهاز", "No file manager found")); }
    }

    /** الصور والفيديو: منتقي الصور الحديث (بدون صلاحيات)، أو منتقي الملفات في الأجهزة الأقدم. */
    private void openMediaPicker(String type) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType(type);
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType(type);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try { startActivityForResult(intent, REQ_PICK_MEDIA); }
        catch (Exception e) { openFilePicker(); }
    }

    private void openFolderPicker() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER); }
        catch (Exception e) { toast(L.t("اختيار المجلد غير مدعوم على هذا الجهاز", "Folder picking is not supported here")); }
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
                if (c == null) throw new java.io.IOException(L.t("تعذر قراءة محتويات المجلد", "Could not read the folder"));
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
                        if (size < 0) throw new java.io.IOException("? " + relative);
                        result.add(new FileItem(fileUri, relative, size));
                        if (result.size() > 50000) throw new java.io.IOException(L.t("المجلد كبير جداً", "Folder too large"));
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
            toast(L.t("جاري قراءة المجلد...", "Reading folder..."));
            new Thread(() -> {
                try {
                    ArrayList<FileItem> entries = listFolderFiles(tree);
                    ui.post(() -> {
                        if (entries.isEmpty()) { toast(L.t("المجلد ما يحتوي ملفات", "The folder is empty")); return; }
                        selectedFiles.addAll(entries);
                        updateSelection();
                    });
                } catch (Exception e) { ui.post(() -> toast(e.getMessage())); }
            }, "satlink-folder-scan").start();
            return;
        }
        if (requestCode == REQ_PICK_FILES || requestCode == REQ_PICK_MEDIA) {
            int before = selectedFiles.size();
            if (data.getClipData() != null) {
                ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) addUri(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                addUri(data.getData());
            }
            updateSelection();
            if (selectedFiles.size() > before) toast(L.t("اضغط على الجهاز المستلم", "Tap the receiving device"));
        }
    }

    private void addUri(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        FileItem item = FileStoreUtils.fromUri(this, uri);
        if (item.size < 0) { toast("? " + item.name); return; }
        for (FileItem f : selectedFiles) if (f.uri.equals(uri)) return;
        selectedFiles.add(item);
    }

    // =================================================================== أدوات الواجهة

    private LinearLayout sectionHeader(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 18, TEXT, true);
        if (!L.light()) t.setShadowLayer(dp(6), 0, 0, Color.rgb(120, 80, 255));
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
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(click);
        return b;
    }

    private Drawable progressDrawable() {
        GradientDrawable track = new GradientDrawable();
        track.setColor(L.light() ? Color.argb(40, 0, 0, 0) : Color.argb(50, 255, 255, 255));
        track.setCornerRadius(dp(3));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
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
        tv.setTextDirection(L.english() ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
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
