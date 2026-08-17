package com.tungee.amapbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.3.0
 * 高德无障碍只负责导航/点击/滚动；真正的数据读取优先使用屏幕截图 + 中文OCR。
 * 解决高德自绘/混合页面“肉眼看得到开业时间，但AccessibilityNodeInfo读不到”的问题。
 */
public class AmapAccessibilityServiceV23 extends AccessibilityService {
    private static volatile AmapAccessibilityServiceV23 instance;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;

    private String lastTask = "";
    private long lastEventAt = 0;
    private long lastOcrAt = 0;
    private boolean ocrBusy = false;
    private boolean searchMode = false;
    private boolean searchSubmitted = false;
    private boolean directFallback = false;
    private boolean clickedInfo = false;
    private int searchAttempts = 0;
    private int searchScrolls = 0;
    private int detailScrolls = 0;
    private String roomsHint = "";
    private String phoneHint = "";
    private String lastOcrText = "";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        BridgeService.sendDebug(TaskState.taskId, "无障碍V2.3已连接｜屏幕OCR可用=" + (Build.VERSION.SDK_INT >= 30));
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignored) {}
        try { screenshotExecutor.shutdownNow(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    public static boolean isAlive() { return instance != null; }

    public static void kick() {
        AmapAccessibilityServiceV23 s = instance;
        if (s != null) {
            s.h.removeCallbacks(s.scanRunnable);
            s.h.postDelayed(s.scanRunnable, 320);
        }
    }

    public static void markSearchMode() {
        AmapAccessibilityServiceV23 s = instance;
        if (s != null) {
            s.searchMode = true;
            s.searchSubmitted = false;
            s.directFallback = false;
            s.clickedInfo = false;
            s.searchAttempts = 0;
            s.searchScrolls = 0;
            s.detailScrolls = 0;
            s.roomsHint = "";
            s.phoneHint = "";
            s.lastOcrText = "";
            s.lastOcrAt = 0;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!TaskState.active) return;
        if (event.getPackageName() == null || !"com.autonavi.minimap".contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now - lastEventAt < 220) return;
        lastEventAt = now;
        h.removeCallbacks(scanRunnable);
        h.postDelayed(scanRunnable, 240);
    }

    private final Runnable scanRunnable = this::safeScan;

    private void safeScan() {
        try { scan(); }
        catch (Throwable e) {
            BridgeService.sendDebug(TaskState.taskId, "V2.3扫描异常已拦截：" + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()));
            if (TaskState.active) h.postDelayed(scanRunnable, 800);
        }
    }

    private void resetIfNewTask() {
        String id = TaskState.taskId == null ? "" : TaskState.taskId;
        if (!id.equals(lastTask)) {
            lastTask = id;
            searchMode = false;
            searchSubmitted = false;
            directFallback = false;
            clickedInfo = false;
            searchAttempts = 0;
            searchScrolls = 0;
            detailScrolls = 0;
            roomsHint = "";
            phoneHint = "";
            lastOcrText = "";
            lastOcrAt = 0;
            ocrBusy = false;
        }
    }

    private void scan() {
        if (!TaskState.active) return;
        resetIfNewTask();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            BridgeService.sendDebug(TaskState.taskId, "高德窗口节点为空，等待页面加载");
            h.postDelayed(scanRunnable, 600);
            return;
        }

        ArrayList<String> texts = new ArrayList<>();
        collect(root, texts, 0);
        String raw = String.join(" | ", texts);
        if (raw.length() > 26000) raw = raw.substring(0, 26000);

        if (raw.contains("版权信息")) {
            BridgeService.sendDebug(TaskState.taskId, "误入版权信息页，自动返回");
            performGlobalAction(GLOBAL_ACTION_BACK);
            h.postDelayed(scanRunnable, 900);
            return;
        }
        if (raw.contains("网络好像开小差") || raw.contains("网络开小差")) {
            if (clickExact(root, new String[]{"刷新重试", "重试", "重新加载"})) {
                BridgeService.sendDebug(TaskState.taskId, "高德网络异常，已点击重试");
                h.postDelayed(scanRunnable, 1800);
                return;
            }
        }

        BridgeService.sendDebug(TaskState.taskId,
                (searchMode ? "搜索" : "详情") + "｜节点=" + texts.size() + "｜OCR=" + (Build.VERSION.SDK_INT >= 30 ? "开" : "不支持") +
                        "｜搜索翻页=" + searchScrolls + "｜详情翻页=" + detailScrolls);

        if (searchMode) handleSearch(root, raw);
        else handleDetail(root, raw);
    }

    private void handleSearch(AccessibilityNodeInfo root, String raw) {
        if (!searchSubmitted) {
            searchAttempts++;
            if (submitSearch(root, TaskState.hotelName)) {
                searchSubmitted = true;
                BridgeService.sendDebug(TaskState.taskId, "已真正提交高德搜索：" + TaskState.hotelName);
                h.postDelayed(scanRunnable, 1400);
                return;
            }
            if (searchAttempts <= 5) {
                BridgeService.sendDebug(TaskState.taskId, "等待/激活搜索框，第" + searchAttempts + "次");
                h.postDelayed(scanRunnable, 650);
                return;
            }
            if (fallbackPoi()) return;
        }

        if (looksLikeDetail(raw, TaskState.hotelName)) {
            searchMode = false;
            detailScrolls = 0;
            BridgeService.sendDebug(TaskState.taskId, "搜索已进入酒店详情，切换屏幕OCR识别");
            h.postDelayed(scanRunnable, 500);
            return;
        }

        AccessibilityNodeInfo best = findBestTitle(root, TaskState.hotelName);
        if (best != null && clickNodeOrParent(best)) {
            searchMode = false;
            detailScrolls = 0;
            BridgeService.sendDebug(TaskState.taskId, "已点击名称匹配酒店结果，进入详情后OCR识别");
            h.postDelayed(scanRunnable, 1000);
            return;
        }

        if (searchScrolls < 2) {
            boolean moved = scrollForward(root);
            if (!moved) moved = swipeUp();
            searchScrolls++;
            BridgeService.sendDebug(TaskState.taskId, "当前屏未找到目标酒店，搜索结果翻页=" + searchScrolls);
            h.postDelayed(scanRunnable, 750);
            return;
        }
        if (fallbackPoi()) return;
        finish(new Parsed(), "search_no_match", "搜索结果未找到名称匹配酒店", raw);
    }

    private void handleDetail(AccessibilityNodeInfo root, String raw) {
        Parsed nodeParsed = parse(raw);
        if (!nodeParsed.rooms.isEmpty()) roomsHint = nodeParsed.rooms;
        if (!nodeParsed.phone.isEmpty()) phoneHint = nodeParsed.phone;
        if (!nodeParsed.open.isEmpty()) {
            mergeHints(nodeParsed);
            finish(nodeParsed, "success", "无障碍节点直接识别到开业时间", raw);
            return;
        }

        // 关键：先截取用户真正看到的高德画面，用中文OCR识别，不依赖控件树。
        long now = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 30 && !ocrBusy && now - lastOcrAt >= 1100) {
            requestScreenOcr();
            return;
        }

        if (ocrBusy) return;

        if (!clickedInfo && clickExact(root, new String[]{"详情", "酒店信息", "酒店详情", "更多酒店信息"})) {
            clickedInfo = true;
            BridgeService.sendDebug(TaskState.taskId, "本屏OCR未命中，已点击详情入口继续识别");
            h.postDelayed(scanRunnable, 900);
            return;
        }

        if (detailScrolls < 8) {
            boolean moved = scrollForward(root);
            if (!moved) moved = swipeUp();
            detailScrolls++;
            BridgeService.sendDebug(TaskState.taskId, "本屏OCR未发现开业时间，向下翻页 " + detailScrolls + "/8");
            h.postDelayed(scanRunnable, 900);
            return;
        }

        Parsed p = parse(lastOcrText + " | " + raw);
        mergeHints(p);
        finish(p, "open_time_not_found", "8屏OCR+节点均未识别到明确开业时间", lastOcrText + "\nNODE:" + raw);
    }

    private void requestScreenOcr() {
        if (Build.VERSION.SDK_INT < 30 || recognizer == null || ocrBusy || !TaskState.active) return;
        ocrBusy = true;
        lastOcrAt = System.currentTimeMillis();
        final String taskAtStart = TaskState.taskId;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bmp = null;
                    try {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw != null) bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                        try { hb.close(); } catch (Exception ignored) {}
                    } catch (Throwable e) {
                        ocrBusy = false;
                        BridgeService.sendDebug(taskAtStart, "截图转Bitmap失败：" + e.getClass().getSimpleName());
                        h.postDelayed(scanRunnable, 350);
                        return;
                    }
                    if (bmp == null) {
                        ocrBusy = false;
                        BridgeService.sendDebug(taskAtStart, "截图为空，继续节点/滚动兜底");
                        h.postDelayed(scanRunnable, 350);
                        return;
                    }
                    Bitmap finalBmp = bmp;
                    recognizer.process(InputImage.fromBitmap(finalBmp, 0))
                            .addOnSuccessListener(text -> {
                                try {
                                    if (!TaskState.active || !taskAtStart.equals(TaskState.taskId)) return;
                                    lastOcrText = text.getText() == null ? "" : text.getText();
                                    Parsed p = parse(lastOcrText);
                                    if (!p.rooms.isEmpty()) roomsHint = p.rooms;
                                    if (!p.phone.isEmpty()) phoneHint = p.phone;
                                    BridgeService.sendDebug(taskAtStart,
                                            "屏幕OCR完成｜字符=" + lastOcrText.length() + "｜开业=" + p.open + "｜房量=" + p.rooms);
                                    if (!p.open.isEmpty()) {
                                        mergeHints(p);
                                        finish(p, "success", "屏幕OCR识别到高德可见开业时间", lastOcrText);
                                        return;
                                    }
                                } finally {
                                    ocrBusy = false;
                                    try { finalBmp.recycle(); } catch (Exception ignored) {}
                                    if (TaskState.active) h.postDelayed(scanRunnable, 260);
                                }
                            })
                            .addOnFailureListener(e -> {
                                ocrBusy = false;
                                try { finalBmp.recycle(); } catch (Exception ignored) {}
                                BridgeService.sendDebug(taskAtStart, "中文OCR失败：" + e.getClass().getSimpleName());
                                if (TaskState.active) h.postDelayed(scanRunnable, 350);
                            });
                }

                @Override public void onFailure(int errorCode) {
                    ocrBusy = false;
                    BridgeService.sendDebug(taskAtStart, "系统截图失败，错误码=" + errorCode + "，继续节点/滚动兜底");
                    if (TaskState.active) h.postDelayed(scanRunnable, 350);
                }
            });
        } catch (Throwable e) {
            ocrBusy = false;
            BridgeService.sendDebug(taskAtStart, "调用系统截图异常：" + e.getClass().getSimpleName());
            h.postDelayed(scanRunnable, 350);
        }
    }

    private boolean submitSearch(AccessibilityNodeInfo root, String keyword) {
        try {
            AccessibilityNodeInfo input = findEditable(root);
            if (input == null) {
                tapText(root, keyword);
                return false;
            }
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword);
            boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (clickExact(root, new String[]{"搜索", "搜索一下", "查找"})) return true;
            if (set) {
                h.postDelayed(this::tapImeSearch, 420);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        String cls = String.valueOf(n.getClassName());
        if (n.isEditable() || cls.contains("EditText")) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findEditable(n.getChild(i));
            if (x != null) return x;
        }
        return null;
    }

    private AccessibilityNodeInfo findBestTitle(AccessibilityNodeInfo root, String expected) {
        Best b = new Best();
        findBest(root, expected, b);
        return b.score >= 66 ? b.node : null;
    }

    private static class Best { AccessibilityNodeInfo node; int score = 0; }

    private void findBest(AccessibilityNodeInfo n, String expected, Best best) {
        if (n == null) return;
        CharSequence t = n.getText();
        if (t != null && !n.isEditable()) {
            String s = t.toString().trim();
            int score = nameScore(s, expected);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            if (r.centerY() < dm.heightPixels * 0.14f) score -= 40;
            if (score > best.score) { best.score = score; best.node = n; }
        }
        for (int i = 0; i < n.getChildCount(); i++) findBest(n.getChild(i), expected, best);
    }

    private int nameScore(String a0, String b0) {
        String a = norm(a0), b = norm(b0);
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) {
            int min = Math.min(a.length(), b.length());
            if (min >= 9) return 94;
            if (min >= 6) return 82;
        }
        int c = 0, m = Math.min(a.length(), b.length());
        while (c < m && a.charAt(c) == b.charAt(c)) c++;
        if (c >= 10) return 85;
        if (c >= 7) return 72;
        if (c >= 5) return 60;
        return 0;
    }

    private boolean looksLikeDetail(String raw, String expected) {
        String a = norm(raw), b = norm(expected);
        if (b.isEmpty()) return false;
        String key = b.length() > 6 ? b.substring(0, 6) : b;
        if (!a.contains(key)) return false;
        int m = 0;
        for (String x : new String[]{"导航", "路线", "电话", "收藏", "分享", "评分", "地址"}) if (raw.contains(x)) m++;
        return m >= 3;
    }

    private boolean fallbackPoi() {
        if (directFallback || TaskState.poiId == null || TaskState.poiId.isEmpty()) return false;
        directFallback = true;
        boolean ok = AmapLauncher.openPoi(this, TaskState.poiId, TaskState.hotelName, TaskState.lat, TaskState.lon);
        if (ok) {
            searchMode = false;
            detailScrolls = 0;
            BridgeService.sendDebug(TaskState.taskId, "搜索未命中，POI_ID直达详情后使用屏幕OCR");
            h.postDelayed(scanRunnable, 1000);
            return true;
        }
        return false;
    }

    private void collect(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 45 || out.size() > 1800) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null && !t.toString().trim().isEmpty()) out.add(t.toString().trim());
        if (d != null && !d.toString().trim().isEmpty()) out.add(d.toString().trim());
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    private boolean clickExact(AccessibilityNodeInfo n, String[] keys) {
        if (n == null) return false;
        String a = n.getText() == null ? "" : n.getText().toString().trim();
        String b = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        for (String k : keys) {
            if (a.equals(k) || b.equals(k)) return clickNodeOrParent(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) if (clickExact(n.getChild(i), keys)) return true;
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 8 && c != null; i++, c = c.getParent()) {
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return !r.isEmpty() && tap(r.centerX(), r.centerY());
    }

    private boolean tapText(AccessibilityNodeInfo n, String keyword) {
        if (n == null || keyword == null || keyword.isEmpty()) return false;
        CharSequence t = n.getText();
        if (t != null && t.toString().contains(keyword)) return clickNodeOrParent(n);
        for (int i = 0; i < n.getChildCount(); i++) if (tapText(n.getChild(i), keyword)) return true;
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (scrollForward(n.getChild(i))) return true;
        return false;
    }

    private boolean swipeUp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Path p = new Path();
        p.moveTo(dm.widthPixels * .5f, dm.heightPixels * .78f);
        p.lineTo(dm.widthPixels * .5f, dm.heightPixels * .30f);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null);
    }

    private boolean tapImeSearch() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return tap(dm.widthPixels * .91f, dm.heightPixels * .945f);
    }

    private boolean tap(float x, float y) {
        Path p = new Path(); p.moveTo(x, y);
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build(), null, null);
    }

    private static class Parsed { String open="", renovate="", rooms="", phone=""; }

    private Parsed parse(String raw) {
        Parsed p = new Parsed();
        String s = raw == null ? "" : raw.replace('：', ':');
        p.open = extractOpening(s);
        p.renovate = extractDateLabel(s, "(?:装修时间|装修日期|翻新时间|装修|翻新)");
        p.rooms = first(s, new String[]{
                "(\\d{1,4})\\s*间\\s*(?:房|客房|房间)",
                "(?:客房数|客房数量|房间数)\\s*[:|]?\\s*(\\d{1,4})"
        });
        p.phone = first(s, new String[]{
                "(?:酒店电话|联系电话|前台电话|电话)\\s*[:|]?\\s*((?:0\\d{2,3}[- ]?)?\\d{7,8}|1[3-9]\\d{9})"
        });
        return p;
    }

    private String extractOpening(String s) {
        String x = extractDateLabel(s, "(?:开业时间|开业日期|开业于)");
        if (!x.isEmpty()) return x;
        Matcher m = Pattern.compile("((?:19|20)\\d{2})\\s*[年./-]\\s*(0?[1-9]|1[0-2])\\s*[月./-]\\s*(0?[1-9]|[12]\\d|3[01])\\s*(?:日|号)?\\s*(?:正式)?(?:开业|开张|开店)").matcher(s);
        if (m.find()) return fmtDate(m.group(1),m.group(2),m.group(3));
        m = Pattern.compile("((?:19|20)\\d{2})\\s*年\\s*(0?[1-9]|1[0-2])\\s*月\\s*(?:正式)?(?:开业|开张|开店)").matcher(s);
        if (m.find()) return fmtYm(m.group(1),m.group(2));
        m = Pattern.compile("((?:19|20)\\d{2})\\s*年\\s*(?:正式)?(?:开业|开张|开店)").matcher(s);
        if (m.find()) return m.group(1);
        return "";
    }

    private String extractDateLabel(String s, String label) {
        Matcher m = Pattern.compile(label + "[\\s\\n|:：-]{0,16}((?:19|20)\\d{2})\\s*[年./-]\\s*(0?[1-9]|1[0-2])\\s*[月./-]\\s*(0?[1-9]|[12]\\d|3[01])\\s*(?:日|号)?").matcher(s);
        if (m.find()) return fmtDate(m.group(1),m.group(2),m.group(3));
        m = Pattern.compile(label + "[\\s\\n|:：-]{0,16}((?:19|20)\\d{2})\\s*[年./-]\\s*(0?[1-9]|1[0-2])\\s*(?:月)?").matcher(s);
        if (m.find()) return fmtYm(m.group(1),m.group(2));
        m = Pattern.compile(label + "[\\s\\n|:：-]{0,16}((?:19|20)\\d{2})\\s*(?:年)?").matcher(s);
        if (m.find()) return m.group(1);
        return "";
    }

    private String fmtDate(String y,String mo,String d) {
        try { return String.format(Locale.US,"%s-%02d-%02d",y,Integer.parseInt(mo),Integer.parseInt(d)); }
        catch(Exception e){ return y; }
    }
    private String fmtYm(String y,String mo) {
        try { return String.format(Locale.US,"%s-%02d",y,Integer.parseInt(mo)); }
        catch(Exception e){ return y; }
    }
    private String first(String s,String[] ps) {
        for(String p:ps){ Matcher m=Pattern.compile(p,Pattern.CASE_INSENSITIVE).matcher(s); if(m.find()) return m.group(1).trim(); }
        return "";
    }
    private String norm(String s) {
        if(s==null)return "";
        return s.replaceAll("[\\s·•・\\-—_（）()【】\\[\\]<>〈〉]","").replace("大酒店","酒店").replace("宾馆","酒店");
    }

    private void mergeHints(Parsed p) {
        if (p.rooms.isEmpty() && !roomsHint.isEmpty()) p.rooms = roomsHint;
        if (p.phone.isEmpty() && !phoneHint.isEmpty()) p.phone = phoneHint;
    }

    private void finish(Parsed p,String status,String extra,String raw) {
        if (!TaskState.active) return;
        mergeHints(p);
        String evidence = "V2.3屏幕OCR｜开业="+p.open+"；装修="+p.renovate+"；客房="+p.rooms+"；电话="+p.phone+"；"+extra;
        BridgeService.sendResult(TaskState.taskId,TaskState.poiId,TaskState.hotelName,
                p.open,p.renovate,p.rooms,p.phone,"通过",status,evidence,raw);
        TaskState.clear();
        searchMode=false;searchSubmitted=false;directFallback=false;clickedInfo=false;
        searchAttempts=0;searchScrolls=0;detailScrolls=0;roomsHint="";phoneHint="";lastOcrText="";lastTask="";ocrBusy=false;
    }

    @Override public void onInterrupt() {}
}
