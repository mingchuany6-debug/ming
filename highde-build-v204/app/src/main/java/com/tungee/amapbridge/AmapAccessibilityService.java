package com.tungee.amapbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.regex.*;

/**
 * V2.2.0 - 高德酒店搜索结果卡片优先读取。
 *
 * 核心策略：
 * 1) 主动搜索酒店名并真正提交搜索；
 * 2) 先定位“匹配酒店名”的搜索结果卡片；
 * 3) 只在同一张卡片内读取“2023年开业 / 101间房”等字段；
 * 4) 卡片已有开业/房量时直接回传，不再进入详情页盲滑；
 * 5) 卡片没有关键字段时才点击详情做兜底。
 */
public class AmapAccessibilityService extends AccessibilityService {
    private static volatile AmapAccessibilityService instance;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastRun = 0;
    private int scrollCount = 0, searchScrollCount = 0, searchSubmitAttempts = 0;
    private boolean clickedInfo = false, retriedNetwork = false, searchMode = false;
    private boolean searchSubmitted = false, directFallbackUsed = false, cardHit = false;
    private String lastTask = "";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        BridgeService.sendDebug(TaskState.taskId, "无障碍服务已连接｜V2.2结果卡片直读");
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isAlive() { return instance != null; }

    public static void kick() {
        AmapAccessibilityService s = instance;
        if (s != null) {
            s.h.removeCallbacks(s.scanRunnable);
            s.h.postDelayed(s.scanRunnable, 320);
        }
    }

    public static void markSearchMode() {
        AmapAccessibilityService s = instance;
        if (s != null) {
            s.searchMode = true;
            s.scrollCount = 0;
            s.searchScrollCount = 0;
            s.clickedInfo = false;
            s.searchSubmitted = false;
            s.searchSubmitAttempts = 0;
            s.directFallbackUsed = false;
            s.cardHit = false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!TaskState.active) return;
        if (event.getPackageName() == null || !"com.autonavi.minimap".contentEquals(event.getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now - lastRun < 240) return;
        lastRun = now;
        h.removeCallbacks(scanRunnable);
        h.postDelayed(scanRunnable, 220);
    }

    private final Runnable scanRunnable = this::scan;

    private void resetForTask() {
        String id = TaskState.taskId == null ? "" : TaskState.taskId;
        if (!id.equals(lastTask)) {
            lastTask = id;
            scrollCount = 0;
            searchScrollCount = 0;
            searchSubmitAttempts = 0;
            clickedInfo = false;
            retriedNetwork = false;
            searchMode = false;
            searchSubmitted = false;
            directFallbackUsed = false;
            cardHit = false;
        }
    }

    private void scan() {
        if (!TaskState.active) return;
        resetForTask();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            BridgeService.sendDebug(TaskState.taskId, "高德窗口节点为空，继续等待");
            h.postDelayed(scanRunnable, 600);
            return;
        }

        ArrayList<String> texts = new ArrayList<>();
        collect(root, texts, 0);
        String raw = String.join(" | ", texts);
        if (raw.length() > 28000) raw = raw.substring(0, 28000);
        BridgeService.sendDebug(TaskState.taskId,
                (searchMode ? "搜索阶段" : "详情阶段") + "读取文字 " + texts.size() +
                        " 项｜搜索滑动=" + searchScrollCount + "｜详情滑动=" + scrollCount);

        if (handleNetworkError(root, raw)) return;
        if (searchMode) handleSearchPage(root, raw);
        else handleDetailPage(root, raw);
    }

    private boolean handleNetworkError(AccessibilityNodeInfo root, String raw) {
        if (raw.contains("网络好像开小差") || raw.contains("网络开小差") || raw.contains("加载失败")) {
            if (!retriedNetwork && clickAny(root, new String[]{"刷新重试", "重新加载", "重试"})) {
                retriedNetwork = true;
                BridgeService.sendDebug(TaskState.taskId, "检测到高德网络异常，已点击刷新重试");
                h.postDelayed(scanRunnable, 2100);
                return true;
            }
            if (System.currentTimeMillis() - TaskState.startedAt > 6500) {
                Result r = parse(raw, TaskState.hotelName);
                finish(r, "amap_network_error", "高德页面提示网络异常", raw);
                return true;
            }
        }
        return false;
    }

    private void handleSearchPage(AccessibilityNodeInfo root, String raw) {
        // 1. 先确保高德真正提交了搜索，而不是只把酒店名放在搜索框里。
        if (!searchSubmitted) {
            boolean ok = submitSearch(root, TaskState.hotelName);
            searchSubmitAttempts++;
            if (ok) {
                searchSubmitted = true;
                BridgeService.sendDebug(TaskState.taskId, "已主动提交高德搜索：" + TaskState.hotelName);
                h.postDelayed(scanRunnable, 1350);
                return;
            }
            if (searchSubmitAttempts <= 5) {
                BridgeService.sendDebug(TaskState.taskId, "正在触发搜索，第" + searchSubmitAttempts + "次");
                h.postDelayed(scanRunnable, 600);
                return;
            }
            BridgeService.sendDebug(TaskState.taskId, "搜索提交未成功，尝试POI_ID直达兜底");
            if (fallbackPoi()) return;
        }

        // 2. 搜索完成后优先读取与目标酒店名对应的“同一张搜索结果卡片”。
        CardMatch card = findBestHotelCard(root, TaskState.hotelName);
        if (card != null && card.score >= 65) {
            BridgeService.sendDebug(TaskState.taskId,
                    "找到目标酒店结果卡｜匹配分=" + card.score + "｜卡片文字=" + compact(card.text, 220));
            Result cr = parse(card.text, TaskState.hotelName);
            cr.nameCheck = card.score >= 80 ? "通过" : "待核实";

            // 高德酒店卡片常见：2023年开业 | 101间房。
            if (!cr.open.isEmpty() || !cr.rooms.isEmpty()) {
                cardHit = true;
                String extra = "高德搜索结果卡片直读";
                if (cr.open.isEmpty()) extra += "；卡片未显示开业年份";
                if (cr.rooms.isEmpty()) extra += "；卡片未显示房量";
                finish(cr, "success", extra, card.text);
                return;
            }

            // 找到正确卡片但关键字段没暴露，才点进去继续详情兜底。
            if (clickCard(card)) {
                searchMode = false;
                scrollCount = 0;
                clickedInfo = false;
                BridgeService.sendDebug(TaskState.taskId, "目标卡片未显示开业/房量，已点击酒店详情兜底");
                h.postDelayed(scanRunnable, 900);
                return;
            }
        }

        // 某些高德版本搜索后会直接进入详情。
        if (looksLikeHotelDetail(raw, TaskState.hotelName)) {
            searchMode = false;
            scrollCount = 0;
            clickedInfo = false;
            BridgeService.sendDebug(TaskState.taskId, "搜索已直接进入目标酒店详情");
            h.postDelayed(scanRunnable, 320);
            return;
        }

        // 最多在搜索结果里翻 3 屏找同名酒店，避免无限滑动。
        if (searchScrollCount < 3) {
            boolean moved = scrollForward(root);
            if (!moved) moved = swipeUp();
            searchScrollCount++;
            BridgeService.sendDebug(TaskState.taskId,
                    moved ? "本屏未找到目标酒店卡片，搜索结果向下翻一屏" : "搜索结果页无法滚动，继续等待");
            h.postDelayed(scanRunnable, 650);
            return;
        }

        if (fallbackPoi()) return;
        Result r = parse(raw, TaskState.hotelName);
        finish(r, "search_no_match", "关键词搜索未找到匹配酒店卡片", raw);
    }

    /** 找到搜索框，写入酒店名，并主动点击“搜索/键盘搜索键”。 */
    private boolean submitSearch(AccessibilityNodeInfo root, String keyword) {
        try {
            AccessibilityNodeInfo input = findSearchInput(root, keyword);
            if (input == null) {
                // URI 有时先把关键词展示成普通文本，先点一下顶部关键词区域使输入框激活。
                boolean tapped = tapNodeContaining(root, keyword);
                if (tapped) BridgeService.sendDebug(TaskState.taskId, "已点击顶部酒店名搜索框，等待输入控件");
                return false;
            }

            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword);
            boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (set) BridgeService.sendDebug(TaskState.taskId, "已把酒店名写入高德搜索框");

            if (clickExact(root, new String[]{"搜索", "搜索一下", "查找"})) {
                BridgeService.sendDebug(TaskState.taskId, "已点击高德页面搜索按钮");
                return true;
            }

            if (set) {
                // 输入法的“搜索”键通常不在高德无障碍树里，用系统手势点击右下角。
                h.postDelayed(() -> {
                    boolean tapped = tapImeSearch();
                    BridgeService.sendDebug(TaskState.taskId,
                            tapped ? "已点击软键盘右下角搜索键" : "软键盘搜索键点击失败");
                }, 380);
                return true;
            }
        } catch (Exception e) {
            BridgeService.sendDebug(TaskState.taskId, "主动搜索异常：" + e.getClass().getSimpleName());
        }
        return false;
    }

    private AccessibilityNodeInfo findSearchInput(AccessibilityNodeInfo n, String keyword) {
        if (n == null) return null;
        String cls = String.valueOf(n.getClassName());
        CharSequence t = n.getText();
        String s = t == null ? "" : t.toString().trim();
        if (n.isEditable() || cls.contains("EditText")) return n;
        if (!s.isEmpty() && keyword != null && s.equals(keyword)) {
            AccessibilityNodeInfo c = n;
            for (int i = 0; i < 5 && c != null; i++, c = c.getParent()) {
                String cc = String.valueOf(c.getClassName());
                if (c.isEditable() || cc.contains("EditText")) return c;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findSearchInput(n.getChild(i), keyword);
            if (x != null) return x;
        }
        return null;
    }

    /**
     * 搜索结果卡片匹配：先匹配酒店标题，再向上寻找最小的卡片容器，
     * 只从该容器的子节点读取“开业/房量”，防止串到其他搜索结果。
     */
    private CardMatch findBestHotelCard(AccessibilityNodeInfo root, String expected) {
        CardMatch best = new CardMatch();
        findCardCandidates(root, expected, best);
        return best.node == null ? null : best;
    }

    private void findCardCandidates(AccessibilityNodeInfo n, String expected, CardMatch best) {
        if (n == null) return;
        CharSequence t = n.getText();
        String cls = String.valueOf(n.getClassName());
        if (t != null && !cls.contains("EditText") && !n.isEditable()) {
            String title = t.toString().trim();
            int ns = nameScore(title, expected);
            if (ns >= 60) {
                Rect tb = new Rect();
                n.getBoundsInScreen(tb);
                DisplayMetrics dm = getResources().getDisplayMetrics();
                // 顶部搜索框会重复出现关键词，排除屏幕顶部输入区域。
                if (tb.centerY() > dm.heightPixels * 0.14f) {
                    evaluateCardAncestors(n, expected, title, ns, best, dm);
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) findCardCandidates(n.getChild(i), expected, best);
    }

    private void evaluateCardAncestors(AccessibilityNodeInfo titleNode, String expected, String title,
                                       int nameScore, CardMatch best, DisplayMetrics dm) {
        AccessibilityNodeInfo c = titleNode;
        for (int level = 0; level < 8 && c != null; level++, c = c.getParent()) {
            Rect b = new Rect();
            c.getBoundsInScreen(b);
            int bh = b.height();
            if (bh <= 0) continue;
            // 结果卡一般只占一部分屏幕；超过 72% 高度基本已经是整页根节点，拒绝。
            if (bh > dm.heightPixels * 0.72f) continue;
            if (b.bottom < dm.heightPixels * 0.16f) continue;

            ArrayList<String> cardTexts = new ArrayList<>();
            collect(c, cardTexts, 0);
            if (cardTexts.isEmpty() || cardTexts.size() > 120) continue;
            String cardText = String.join(" | ", cardTexts);
            String nr = norm(cardText), ne = norm(expected);
            String key = ne.length() > 6 ? ne.substring(0, 6) : ne;
            if (!key.isEmpty() && !nr.contains(key)) continue;

            Result p = parse(cardText, expected);
            boolean hasHotelMeta = !p.open.isEmpty() || !p.rooms.isEmpty() ||
                    cardText.contains("订") || cardText.contains("电话") || cardText.contains("评分");
            if (!hasHotelMeta) continue;

            int score = nameScore * 10;
            if (!p.open.isEmpty()) score += 150;
            if (!p.rooms.isEmpty()) score += 100;
            // 更小、更局部的容器优先，降低串卡风险。
            score -= Math.min(160, cardTexts.size() * 2);
            score -= Math.min(120, bh / 20);

            if (best.node == null || score > best.rank) {
                best.node = c;
                best.titleNode = titleNode;
                best.title = title;
                best.text = cardText;
                best.score = nameScore;
                best.rank = score;
            }
        }
    }

    private static class CardMatch {
        AccessibilityNodeInfo node;
        AccessibilityNodeInfo titleNode;
        String title = "";
        String text = "";
        int score = 0;
        int rank = Integer.MIN_VALUE;
    }

    private boolean clickCard(CardMatch card) {
        if (card == null) return false;
        AccessibilityNodeInfo c = card.titleNode != null ? card.titleNode : card.node;
        for (int i = 0; i < 8 && c != null; i++, c = c.getParent()) {
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect b = new Rect();
        card.node.getBoundsInScreen(b);
        return !b.isEmpty() && tap(b.centerX(), b.centerY());
    }

    private boolean tapNodeContaining(AccessibilityNodeInfo n, String keyword) {
        if (n == null || keyword == null || keyword.isEmpty()) return false;
        CharSequence t = n.getText();
        if (t != null && t.toString().contains(keyword)) {
            AccessibilityNodeInfo c = n;
            for (int i = 0; i < 6 && c != null; i++, c = c.getParent()) {
                if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (!b.isEmpty()) return tap(b.centerX(), b.centerY());
        }
        for (int i = 0; i < n.getChildCount(); i++) if (tapNodeContaining(n.getChild(i), keyword)) return true;
        return false;
    }

    private boolean clickExact(AccessibilityNodeInfo n, String[] keys) {
        if (n == null) return false;
        CharSequence t = n.getText(), cd = n.getContentDescription();
        String a = t == null ? "" : t.toString().trim();
        String b = cd == null ? "" : cd.toString().trim();
        for (String k : keys) {
            if (a.equals(k) || b.equals(k)) {
                AccessibilityNodeInfo c = n;
                for (int i = 0; i < 6 && c != null; i++, c = c.getParent()) {
                    if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) if (clickExact(n.getChild(i), keys)) return true;
        return false;
    }

    private boolean fallbackPoi() {
        if (directFallbackUsed || TaskState.poiId == null || TaskState.poiId.isEmpty()) return false;
        directFallbackUsed = true;
        boolean ok = AmapLauncher.openPoi(this, TaskState.poiId, TaskState.hotelName, TaskState.lat, TaskState.lon);
        if (ok) {
            searchMode = false;
            scrollCount = 0;
            clickedInfo = false;
            BridgeService.sendDebug(TaskState.taskId, "搜索卡片未命中，已切换POI_ID详情兜底");
            h.postDelayed(scanRunnable, 850);
            return true;
        }
        return false;
    }

    private boolean looksLikeHotelDetail(String raw, String expected) {
        String n = norm(expected), r = norm(raw);
        if (n.isEmpty()) return false;
        String key = n.length() > 7 ? n.substring(0, 7) : n;
        if (!r.contains(key)) return false;
        int markers = 0;
        for (String m : new String[]{"评分", "导航", "电话", "收藏", "分享", "路线", "地址"})
            if (raw.contains(m)) markers++;
        return markers >= 3;
    }

    /** 详情页只做兜底，正常有“年开业|间房”的酒店搜索卡不会走到这里。 */
    private void handleDetailPage(AccessibilityNodeInfo root, String raw) {
        Result r = parse(raw, TaskState.hotelName);
        long elapsed = System.currentTimeMillis() - TaskState.startedAt;
        if (!r.open.isEmpty()) {
            finish(r, "success", "详情页兜底识别", raw);
            return;
        }
        if (!clickedInfo && clickAny(root,
                new String[]{"酒店信息", "酒店详情", "设施服务", "酒店设施", "更多酒店信息", "关于酒店"})) {
            clickedInfo = true;
            BridgeService.sendDebug(TaskState.taskId, "已点击酒店信息/酒店详情入口");
            h.postDelayed(scanRunnable, 700);
            return;
        }
        int maxScroll = 7;
        if (scrollCount < maxScroll) {
            boolean moved = scrollForward(root);
            if (!moved) moved = swipeUp();
            scrollCount++;
            BridgeService.sendDebug(TaskState.taskId,
                    moved ? "详情兜底上滑，继续找开业/房量" : "详情本轮无法滚动");
            h.postDelayed(scanRunnable, moved ? 600 : 520);
            return;
        }
        if (elapsed > 22000 || scrollCount >= maxScroll)
            finish(r, "open_time_not_found", "搜索卡片和详情均未识别到开业时间", raw);
        else h.postDelayed(scanRunnable, 520);
    }

    private void finish(Result r, String status, String extra, String raw) {
        String path;
        if (cardHit) path = "酒店名搜索→匹配结果卡片直读";
        else if (directFallbackUsed) path = "酒店名搜索→POI详情兜底";
        else path = "酒店名搜索→详情兜底";

        String evidence = "路径=" + path + "；开业=" + r.open + "；装修=" + r.renovate +
                "；客房=" + r.rooms + "；电话=" + r.phone;
        if (extra != null && !extra.isEmpty()) evidence += "；" + extra;
        BridgeService.sendResult(TaskState.taskId, TaskState.poiId, TaskState.hotelName,
                r.open, r.renovate, r.rooms, r.phone, r.nameCheck, status, evidence, raw);
        TaskState.clear();
        scrollCount = 0;
        searchScrollCount = 0;
        searchSubmitAttempts = 0;
        clickedInfo = false;
        retriedNetwork = false;
        searchMode = false;
        searchSubmitted = false;
        directFallbackUsed = false;
        cardHit = false;
        lastTask = "";
    }

    private void collect(AccessibilityNodeInfo n, List<String> out, int d) {
        if (n == null || d > 45 || out.size() > 1800) return;
        CharSequence t = n.getText(), cd = n.getContentDescription();
        if (t != null) {
            String s = t.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (cd != null) {
            String s = cd.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, d + 1);
    }

    private int nameScore(String actual, String expected) {
        String a = norm(actual), e = norm(expected);
        if (a.isEmpty() || e.isEmpty()) return 0;
        if (a.equals(e)) return 100;
        if (a.contains(e) || e.contains(a)) {
            int min = Math.min(a.length(), e.length());
            if (min >= 9) return 95;
            if (min >= 6) return 84;
        }
        // 括号门店名很重要：长前缀一致也认为高相关，但不直接给满分。
        int common = 0, max = Math.min(a.length(), e.length());
        while (common < max && a.charAt(common) == e.charAt(common)) common++;
        if (common >= 12) return 88;
        if (common >= 9) return 78;
        if (common >= 6) return 66;
        return 0;
    }

    private boolean clickAny(AccessibilityNodeInfo n, String[] keys) {
        if (n == null) return false;
        CharSequence t = n.getText(), cd = n.getContentDescription();
        String s = t != null ? t.toString() : (cd != null ? cd.toString() : "");
        if (!s.isEmpty()) {
            for (String k : keys) {
                if (s.equals(k) || s.contains(k)) {
                    AccessibilityNodeInfo c = n;
                    for (int i = 0; i < 6 && c != null; i++, c = c.getParent()) {
                        if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    }
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) if (clickAny(n.getChild(i), keys)) return true;
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < n.getChildCount(); i++) if (scrollForward(n.getChild(i))) return true;
        return false;
    }

    private boolean swipeUp() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float x = dm.widthPixels * 0.50f;
            float y1 = dm.heightPixels * 0.78f, y2 = dm.heightPixels * 0.31f;
            Path p = new Path();
            p.moveTo(x, y1); p.lineTo(x, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 360)).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            BridgeService.sendDebug(TaskState.taskId, "手势上滑异常：" + e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean tapImeSearch() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // 适配常见中文输入法：右下角蓝色“搜索/完成”键。
        return tap(dm.widthPixels * 0.91f, dm.heightPixels * 0.945f);
    }

    private boolean tap(float x, float y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 85)).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private static class Result {
        String open = "", renovate = "", rooms = "", phone = "", nameCheck = "";
    }

    private Result parse(String raw, String expected) {
        Result r = new Result();
        String s = raw == null ? "" : raw.replace('：', ':');

        // 搜索结果卡最关键格式：2023年开业 / 2026年3月开业。
        r.open = first(s, new String[]{
                "((?:19|20)\\d{2})\\s*年\\s*(?:开业|开张|开店)",
                "((?:19|20)\\d{2})\\s*年\\s*(?:0?[1-9]|1[0-2])\\s*月\\s*(?:开业|开张|开店)",
                "(?:开业时间|开业|开张|开店)\\s*[:：]?\\s*((?:19|20)\\d{2})(?:年)?"
        });
        // 若第一条只拿到年，保留年；如果明确有年月，再优先覆盖成 YYYY-MM。
        String ym = first(s, new String[]{
                "((?:19|20)\\d{2})\\s*年\\s*(0?[1-9]|1[0-2])\\s*月\\s*(?:开业|开张|开店)",
                "(?:开业时间|开业)\\s*[:：]?\\s*((?:19|20)\\d{2})[年./-]\\s*(0?[1-9]|1[0-2])"
        }, true);
        if (!ym.isEmpty()) r.open = ym;

        r.renovate = first(s, new String[]{
                "(?:装修时间|装修|翻新)\\s*[:：]?\\s*((?:19|20)\\d{2})(?:年)?",
                "((?:19|20)\\d{2})\\s*年\\s*(?:装修|翻新)"
        });
        r.rooms = first(s, new String[]{
                "(\\d{1,4})\\s*间\\s*房",
                "(\\d{1,4})\\s*间\\s*(?:客房|房间)",
                "(?:客房数|客房数量|房间数|房型数量)\\s*[:：]?\\s*(\\d{1,4})"
        });
        r.phone = first(s, new String[]{
                "(?:酒店电话|联系电话|电话|前台电话)\\s*[:：]?\\s*((?:\\+?86[- ]?)?(?:0\\d{2,3}[- ]?)?\\d{7,8}|1[3-9]\\d{9})"
        });

        String a = norm(expected), b = norm(s);
        if (!a.isEmpty()) {
            String key = a.length() > 8 ? a.substring(0, 8) : a;
            r.nameCheck = b.contains(key) ? "通过" : "待核实";
        }
        return r;
    }

    private String first(String s, String[] ps) {
        for (String p : ps) {
            Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) return m.group(1).replace(" ", "").trim();
        }
        return "";
    }

    private String first(String s, String[] ps, boolean yearMonth) {
        for (String p : ps) {
            Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                if (yearMonth && m.groupCount() >= 2) {
                    try {
                        int month = Integer.parseInt(m.group(2));
                        return m.group(1) + "-" + String.format(Locale.US, "%02d", month);
                    } catch (Exception ignored) {}
                }
                return m.group(1).replace(" ", "").trim();
            }
        }
        return "";
    }

    private String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s·•・\\-—_（）()【】\\[\\]<>〈〉]", "")
                .replace("大酒店", "酒店").replace("宾馆", "酒店");
    }

    private String compact(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ").replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Override public void onInterrupt() {}
}
