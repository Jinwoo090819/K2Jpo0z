package com.orbinexia.instafinder;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstagramAccessibilityService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickScheduled = false;
    private int stagnantRounds = 0;
    private int phaseAttempts = 0;
    private long profileOpenedAt = 0L;

    private static final int MAX_CANDIDATES = 80;
    private static final Set<String> BLOCKED_WORDS = new HashSet<>();

    static {
        String[] words = {
                "search", "instagram", "home", "reels", "messages", "notifications",
                "profile", "explore", "accounts", "account", "following", "followers",
                "게시물", "팔로워", "팔로잉", "검색", "계정", "홈", "릴스", "메시지"
        };
        for (String s : words) BLOCKED_WORDS.add(s.toLowerCase(Locale.ROOT));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        log("접근성 서비스 연결됨");
        scheduleTick(300);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && "com.instagram.android".contentEquals(pkg)) {
            scheduleTick(250);
        }
    }

    @Override
    public void onInterrupt() {
        log("접근성 서비스 중단됨");
    }

    private void scheduleTick(long delay) {
        if (tickScheduled) return;
        tickScheduled = true;
        handler.postDelayed(() -> {
            tickScheduled = false;
            try { tick(); } catch (Throwable t) { log("오류: " + t.getMessage()); }
        }, delay);
    }

    private void tick() {
        SharedPreferences p = prefs();
        if (!p.getBoolean("running", false)) return;

        String phase = p.getString("phase", "IDLE");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleTick(500);
            return;
        }

        switch (phase) {
            case "OPEN_SEARCH":
                if (clickByTextOrDescription(root, "검색", "Search")) {
                    p.edit().putString("phase", "ENTER_QUERY").apply();
                    log("검색 화면 열기");
                    phaseAttempts = 0;
                    scheduleTick(900);
                } else {
                    phaseAttempts++;
                    log("검색 버튼 찾는 중 (" + phaseAttempts + ")");
                    if (phaseAttempts > 8) fail("Instagram 검색 버튼을 찾지 못했습니다.");
                    else scheduleTick(700);
                }
                break;

            case "ENTER_QUERY":
                AccessibilityNodeInfo edit = findFirstEditText(root);
                if (edit != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            p.getString("query", ""));
                    boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    if (ok) {
                        p.edit().putString("phase", "SELECT_ACCOUNTS").apply();
                        log("검색어 입력 완료");
                        phaseAttempts = 0;
                        scheduleTick(1200);
                    } else {
                        log("검색어 입력 재시도");
                        scheduleTick(700);
                    }
                } else {
                    phaseAttempts++;
                    if (phaseAttempts > 8) fail("검색 입력창을 찾지 못했습니다.");
                    else scheduleTick(600);
                }
                break;

            case "SELECT_ACCOUNTS":
                if (clickExactText(root, "계정", "Accounts", "People")) {
                    p.edit().putString("phase", "COLLECT").apply();
                    log("계정 탭 선택");
                    stagnantRounds = 0;
                    phaseAttempts = 0;
                    scheduleTick(1000);
                } else {
                    phaseAttempts++;
                    if (phaseAttempts >= 4) {
                        // Some Instagram builds immediately show account results without a tab.
                        p.edit().putString("phase", "COLLECT").apply();
                        log("계정 탭 미확인 → 현재 검색 결과에서 수집 시작");
                        stagnantRounds = 0;
                        scheduleTick(700);
                    } else scheduleTick(600);
                }
                break;

            case "COLLECT":
                collectCandidates(root);
                break;

            case "INSPECT":
                inspectNext();
                break;

            case "WAIT_PROFILE":
                parseCurrentProfile(root);
                break;

            default:
                break;
        }
    }

    private void collectCandidates(AccessibilityNodeInfo root) {
        SharedPreferences p = prefs();
        JSONArray old = readArray(p.getString("candidates", "[]"));
        Set<String> usernames = new HashSet<>();
        for (int i = 0; i < old.length(); i++) usernames.add(old.optString(i));
        int before = usernames.size();

        List<String> texts = new ArrayList<>();
        collectVisibleTexts(root, texts);
        for (String raw : texts) {
            String s = raw == null ? "" : raw.trim();
            if (looksLikeUsername(s)) usernames.add(s);
        }

        JSONArray next = new JSONArray();
        for (String u : usernames) next.put(u);
        p.edit().putString("candidates", next.toString()).apply();

        int after = usernames.size();
        if (after > before) stagnantRounds = 0; else stagnantRounds++;
        log("후보 수집: " + after + "개 (정체 " + stagnantRounds + ")");

        if (after >= MAX_CANDIDATES || stagnantRounds >= 4) {
            if (after == 0) {
                fail("검색 결과에서 계정 아이디를 찾지 못했습니다.");
                return;
            }
            p.edit().putString("phase", "INSPECT").putInt("inspectIndex", 0).apply();
            log("후보 수집 완료: " + after + "개 → 프로필 검사 시작");
            scheduleTick(500);
            return;
        }

        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            scheduleTick(900);
        } else {
            stagnantRounds++;
            scheduleTick(700);
        }
    }

    private void inspectNext() {
        SharedPreferences p = prefs();
        JSONArray candidates = readArray(p.getString("candidates", "[]"));
        int idx = p.getInt("inspectIndex", 0);
        if (idx >= candidates.length()) {
            p.edit().putBoolean("running", false).putString("phase", "DONE").apply();
            log("완료: 결과 " + readArray(p.getString("results", "[]")).length() + "개");
            return;
        }

        String username = candidates.optString(idx, "");
        if (!looksLikeUsername(username)) {
            p.edit().putInt("inspectIndex", idx + 1).apply();
            scheduleTick(150);
            return;
        }

        p.edit().putString("currentUsername", username).putString("phase", "WAIT_PROFILE").apply();
        profileOpenedAt = System.currentTimeMillis();
        log("[" + (idx + 1) + "/" + candidates.length() + "] @" + username + " 확인 중");

        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + username + "/"));
            i.setPackage("com.instagram.android");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception e) {
            p.edit().putInt("inspectIndex", idx + 1).putString("phase", "INSPECT").apply();
            log("프로필 열기 실패: @" + username);
        }
        scheduleTick(2200);
    }

    private void parseCurrentProfile(AccessibilityNodeInfo root) {
        SharedPreferences p = prefs();
        String username = p.getString("currentUsername", "");
        long elapsed = System.currentTimeMillis() - profileOpenedAt;

        List<String> texts = new ArrayList<>();
        collectVisibleTexts(root, texts);
        String all = String.join("\n", texts);
        String lower = all.toLowerCase(Locale.ROOT);

        boolean looksLoaded = lower.contains(username.toLowerCase(Locale.ROOT))
                || lower.contains("게시물") || lower.contains("posts");
        if (!looksLoaded && elapsed < 6000) {
            scheduleTick(800);
            return;
        }

        boolean isPrivate = containsAny(lower,
                "비공개 계정입니다", "비공개 계정", "this account is private", "private account");
        int posts = parseMetric(all, "게시물", "posts");
        int followers = parseMetric(all, "팔로워", "followers");
        boolean highlights = containsAny(lower, "하이라이트", "highlight", "story highlights");

        int maxPosts = p.getInt("maxPosts", 10);
        int maxFollowers = p.getInt("maxFollowers", 1000);
        boolean highlightOnly = p.getBoolean("highlightOnly", false);

        boolean metricKnown = posts >= 0 && followers >= 0;
        boolean pass = !isPrivate && metricKnown && posts < maxPosts && followers < maxFollowers
                && (!highlightOnly || highlights);

        if (pass) {
            addResult(username, posts, followers, highlights);
            log("  통과: 공개 / 게시물 " + posts + " / 팔로워 " + followers
                    + " / 하이라이트 " + (highlights ? "O" : "X"));
        } else {
            String reason;
            if (isPrivate) reason = "비공개";
            else if (!metricKnown) reason = "게시물/팔로워 수 확인 실패";
            else if (posts >= maxPosts) reason = "게시물 기준 초과(" + posts + ")";
            else if (followers >= maxFollowers) reason = "팔로워 기준 초과(" + followers + ")";
            else reason = "하이라이트 없음";
            log("  제외: " + reason);
        }

        int idx = p.getInt("inspectIndex", 0) + 1;
        p.edit().putInt("inspectIndex", idx).putString("phase", "INSPECT").apply();
        scheduleTick(450);
    }

    private void addResult(String username, int posts, int followers, boolean highlights) {
        SharedPreferences p = prefs();
        JSONArray arr = readArray(p.getString("results", "[]"));
        for (int i = 0; i < arr.length(); i++) {
            if (username.equalsIgnoreCase(arr.optJSONObject(i).optString("username"))) return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("username", username);
            o.put("posts", posts);
            o.put("followers", followers);
            o.put("highlights", highlights);
            arr.put(o);
            p.edit().putString("results", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private int parseMetric(String all, String koreanLabel, String englishLabel) {
        String text = all.replace('\u00A0', ' ');
        Pattern[] patterns = new Pattern[] {
                Pattern.compile(Pattern.quote(koreanLabel) + "\\s*([0-9][0-9,]*(?:\\.[0-9]+)?[만천kKmM]?)"),
                Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?[만천kKmM]?)\\s*" + Pattern.quote(koreanLabel)),
                Pattern.compile(Pattern.quote(englishLabel) + "\\s*([0-9][0-9,]*(?:\\.[0-9]+)?[kKmM]?)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?[kKmM]?)\\s*" + Pattern.quote(englishLabel), Pattern.CASE_INSENSITIVE)
        };
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(text);
            if (m.find()) return parseCompactNumber(m.group(1));
        }
        return -1;
    }

    private int parseCompactNumber(String raw) {
        if (raw == null) return -1;
        String s = raw.trim().replace(",", "");
        try {
            double mult = 1.0;
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.endsWith("k")) { mult = 1000; s = s.substring(0, s.length()-1); }
            else if (lower.endsWith("m")) { mult = 1000000; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("만")) { mult = 10000; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("천")) { mult = 1000; s = s.substring(0, s.length()-1); }
            return (int)Math.round(Double.parseDouble(s) * mult);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private boolean looksLikeUsername(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 30) return false;
        if (!s.matches("[A-Za-z0-9._]+")) return false;
        if (!s.matches(".*[A-Za-z0-9].*")) return false;
        return !BLOCKED_WORDS.contains(s.toLowerCase(Locale.ROOT));
    }

    private void collectVisibleTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) collectVisibleTexts(c, out);
        }
    }

    private AccessibilityNodeInfo findFirstEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText") && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFirstEditText(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickExactText(AccessibilityNodeInfo root, String... labels) {
        if (root == null) return false;
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t != null && label.equalsIgnoreCase(t.toString().trim())) {
                    if (clickNodeOrParent(n)) return true;
                }
            }
        }
        return false;
    }

    private boolean clickByTextOrDescription(AccessibilityNodeInfo root, String... labels) {
        if (root == null) return false;
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) if (clickNodeOrParent(n)) return true;
            }
        }
        return clickByDescriptionRecursive(root, labels);
    }

    private boolean clickByDescriptionRecursive(AccessibilityNodeInfo node, String... labels) {
        if (node == null) return false;
        CharSequence d = node.getContentDescription();
        if (d != null) {
            String ds = d.toString().trim();
            for (String label : labels) {
                if (ds.equalsIgnoreCase(label) || ds.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
                    if (clickNodeOrParent(node)) return true;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickByDescriptionRecursive(node.getChild(i), labels)) return true;
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 4 && n != null; i++) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            n = n.getParent();
        }
        return false;
    }

    private JSONArray readArray(String s) {
        try { return new JSONArray(s == null ? "[]" : s); }
        catch (Exception e) { return new JSONArray(); }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private void log(String message) {
        prefs().edit().putString("lastLog", message).apply();
    }

    private void fail(String message) {
        prefs().edit().putBoolean("running", false).putString("phase", "ERROR")
                .putString("lastLog", message).apply();
    }
}
