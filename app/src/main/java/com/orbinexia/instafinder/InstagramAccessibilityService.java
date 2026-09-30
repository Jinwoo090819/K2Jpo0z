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
import java.util.LinkedHashSet;
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
    private long accountsTabClickedAt = 0L;

    private static final int MAX_CANDIDATES = 100;
    private static final int MAX_STAGNANT_ROUNDS = 5;

    private static final Set<String> BLOCKED_WORDS = new HashSet<>();
    private static final Set<String> FOLLOW_LABELS = new HashSet<>();
    private static final Set<String> FORBIDDEN_CLICK_LABELS = new HashSet<>();

    static {
        String[] blocked = {
                "search", "instagram", "home", "reels", "messages", "notifications",
                "profile", "explore", "accounts", "account", "following", "followers",
                "posts", "post", "게시물", "팔로워", "팔로잉", "검색", "계정", "홈", "릴스", "메시지"
        };
        for (String s : blocked) BLOCKED_WORDS.add(s.toLowerCase(Locale.ROOT));

        String[] follow = {
                "팔로우", "팔로잉", "맞팔로우", "follow", "following", "follow back", "requested"
        };
        for (String s : follow) FOLLOW_LABELS.add(s.toLowerCase(Locale.ROOT));

        String[] forbidden = {
                "팔로우", "팔로잉", "맞팔로우", "follow", "following", "follow back", "requested",
                "메시지", "message", "message button"
        };
        for (String s : forbidden) FORBIDDEN_CLICK_LABELS.add(s.toLowerCase(Locale.ROOT));
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
            try {
                tick();
            } catch (Throwable t) {
                log("오류: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                scheduleTick(800);
            }
        }, delay);
    }

    private void tick() {
        SharedPreferences p = prefs();
        if (!p.getBoolean("running", false)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleTick(500);
            return;
        }

        String phase = p.getString("phase", "IDLE");
        switch (phase) {
            case "OPEN_SEARCH":
            case "OPEN_SEARCH_TAB":
                openSearchTab(root);
                break;

            case "OPEN_SEARCH_BOX":
                openSearchBox(root);
                break;

            case "ENTER_QUERY":
                enterQuery(root);
                break;

            case "WAIT_SEARCH_RESULTS":
                waitForSearchResults(root);
                break;

            case "SELECT_ACCOUNTS":
                selectAccountsTab(root);
                break;

            case "VERIFY_ACCOUNTS":
                verifyAccountsTab(root);
                break;

            case "COLLECT":
            case "COLLECT_ACCOUNTS":
                collectAccountRows(root);
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

    private void openSearchTab(AccessibilityNodeInfo root) {
        if (clickSearchNavigationNearBottom(root)) {
            prefs().edit().putString("phase", "OPEN_SEARCH_BOX").apply();
            phaseAttempts = 0;
            log("하단 검색 탭 열기");
            scheduleTick(900);
            return;
        }

        phaseAttempts++;
        log("하단 검색 탭 찾는 중 (" + phaseAttempts + ")");
        if (phaseAttempts > 10) fail("Instagram 하단 검색 탭을 찾지 못했습니다.");
        else scheduleTick(650);
    }

    private void openSearchBox(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo edit = findFirstEditable(root);
        if (edit != null) {
            safeFocusEditable(edit);
            prefs().edit().putString("phase", "ENTER_QUERY").apply();
            phaseAttempts = 0;
            log("상단 검색 입력창 확인");
            scheduleTick(350);
            return;
        }

        if (clickSearchBoxNearTop(root)) {
            log("상단 검색 입력창 열기");
            scheduleTick(700);
            return;
        }

        phaseAttempts++;
        if (phaseAttempts > 10) fail("상단 검색 입력창을 찾지 못했습니다.");
        else {
            log("상단 검색 입력창 찾는 중 (" + phaseAttempts + ")");
            scheduleTick(600);
        }
    }

    private void enterQuery(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo edit = findFirstEditable(root);
        if (edit == null) {
            phaseAttempts++;
            if (phaseAttempts > 10) fail("검색 입력창을 찾지 못했습니다.");
            else scheduleTick(500);
            return;
        }

        String query = prefs().getString("query", "").trim();
        if (query.isEmpty()) {
            fail("검색어가 비어 있습니다.");
            return;
        }

        safeFocusEditable(edit);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
        boolean ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) {
            phaseAttempts++;
            if (phaseAttempts > 8) fail("검색어 입력에 실패했습니다.");
            else {
                log("검색어 입력 재시도");
                scheduleTick(550);
            }
            return;
        }

        prefs().edit().putString("phase", "WAIT_SEARCH_RESULTS").apply();
        phaseAttempts = 0;
        log("검색어 입력 완료: " + query);
        scheduleTick(1000);
    }

    private void waitForSearchResults(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo accountTab = findExactTextNode(root, "계정", "Accounts", "People");
        if (accountTab != null) {
            prefs().edit().putString("phase", "SELECT_ACCOUNTS").apply();
            phaseAttempts = 0;
            log("검색 결과 탭 확인 → 계정 탭 선택 준비");
            scheduleTick(250);
            return;
        }

        phaseAttempts++;
        if (phaseAttempts > 12) fail("검색 결과의 '계정' 탭을 찾지 못했습니다. 추천 화면에서는 수집하지 않습니다.");
        else {
            log("검색 결과 로딩 대기 (" + phaseAttempts + ")");
            scheduleTick(600);
        }
    }

    private void selectAccountsTab(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo tab = findExactTextNode(root, "계정", "Accounts", "People");
        if (tab == null) {
            phaseAttempts++;
            if (phaseAttempts > 10) fail("'계정' 탭을 찾지 못했습니다. 추천 화면에서는 수집하지 않습니다.");
            else scheduleTick(500);
            return;
        }

        if (!safeClickAllowed(tab, "계정", "Accounts", "People")) {
            phaseAttempts++;
            if (phaseAttempts > 8) fail("'계정' 탭 클릭에 실패했습니다.");
            else scheduleTick(500);
            return;
        }

        accountsTabClickedAt = System.currentTimeMillis();
        prefs().edit().putString("phase", "VERIFY_ACCOUNTS").apply();
        phaseAttempts = 0;
        log("계정 탭 클릭 완료");
        scheduleTick(800);
    }

    private void verifyAccountsTab(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo tab = findExactTextNode(root, "계정", "Accounts", "People");
        if (tab == null) {
            phaseAttempts++;
            if (phaseAttempts > 8) fail("계정 탭 전환을 확인하지 못했습니다.");
            else scheduleTick(450);
            return;
        }

        boolean selected = tab.isSelected();
        long elapsed = System.currentTimeMillis() - accountsTabClickedAt;

        if (selected || (accountsTabClickedAt > 0 && elapsed >= 650)) {
            prefs().edit().putString("phase", "COLLECT_ACCOUNTS").apply();
            stagnantRounds = 0;
            phaseAttempts = 0;
            log(selected ? "계정 탭 선택 상태 확인 → 수집 시작" : "계정 탭 클릭 확인 → 수집 시작");
            scheduleTick(500);
            return;
        }

        scheduleTick(300);
    }

    private void collectAccountRows(AccessibilityNodeInfo root) {
        SharedPreferences p = prefs();
        String query = p.getString("query", "").trim();

        JSONArray old = readArray(p.getString("candidates", "[]"));
        LinkedHashSet<String> usernames = new LinkedHashSet<>();
        for (int i = 0; i < old.length(); i++) {
            String u = old.optString(i, "");
            if (looksLikeUsername(u)) usernames.add(u);
        }
        int before = usernames.size();

        List<AccessibilityNodeInfo> actionNodes = new ArrayList<>();
        findFollowActionNodes(root, actionNodes);

        for (AccessibilityNodeInfo action : actionNodes) {
            AccessibilityNodeInfo row = findSmallestAccountRow(action, query);
            if (row == null) continue;

            List<String> rowTexts = new ArrayList<>();
            collectVisibleTexts(row, rowTexts);
            if (!rowMatchesQuery(rowTexts, query)) continue;

            String username = extractUsername(rowTexts);
            if (username != null && looksLikeUsername(username)) {
                usernames.add(username);
            }
        }

        JSONArray next = new JSONArray();
        for (String u : usernames) next.put(u);
        p.edit().putString("candidates", next.toString()).apply();

        int after = usernames.size();
        if (after > before) stagnantRounds = 0;
        else stagnantRounds++;

        log("계정 행 후보 수집: " + after + "개 (새로 " + (after - before) + ", 정체 " + stagnantRounds + ")");

        if (after >= MAX_CANDIDATES || stagnantRounds >= MAX_STAGNANT_ROUNDS) {
            if (after == 0) {
                fail("'계정' 탭에서 검색어와 일치하는 계정 행을 찾지 못했습니다.");
                return;
            }
            p.edit().putString("phase", "INSPECT").putInt("inspectIndex", 0).apply();
            log("후보 수집 완료: " + after + "개 → 프로필 읽기 전용 검사 시작");
            scheduleTick(500);
            return;
        }

        AccessibilityNodeInfo scrollable = findBestScrollable(root);
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

        String username = candidates.optString(idx, "").trim();
        if (!looksLikeUsername(username)) {
            p.edit().putInt("inspectIndex", idx + 1).apply();
            scheduleTick(150);
            return;
        }

        p.edit().putString("currentUsername", username).putString("phase", "WAIT_PROFILE").apply();
        profileOpenedAt = System.currentTimeMillis();
        log("[" + (idx + 1) + "/" + candidates.length() + "] @" + username + " 읽기 전용 확인 중");

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
        if (!looksLoaded && elapsed < 6500) {
            scheduleTick(750);
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
        scheduleTick(400);
    }

    private void addResult(String username, int posts, int followers, boolean highlights) {
        SharedPreferences p = prefs();
        JSONArray arr = readArray(p.getString("results", "[]"));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject existing = arr.optJSONObject(i);
            if (existing != null && username.equalsIgnoreCase(existing.optString("username"))) return;
        }

        try {
            JSONObject o = new JSONObject();
            o.put("username", username);
            o.put("posts", posts);
            o.put("followers", followers);
            o.put("highlights", highlights);
            arr.put(o);
            p.edit().putString("results", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private AccessibilityNodeInfo findSmallestAccountRow(AccessibilityNodeInfo actionNode, String query) {
        AccessibilityNodeInfo n = actionNode;
        for (int depth = 0; depth < 6 && n != null; depth++) {
            List<String> texts = new ArrayList<>();
            collectVisibleTexts(n, texts);
            if (texts.size() >= 2 && rowMatchesQuery(texts, query) && extractUsername(texts) != null) {
                return n;
            }
            n = n.getParent();
        }
        return null;
    }

    private boolean rowMatchesQuery(List<String> texts, String query) {
        String nq = normalizeName(query);
        if (nq.isEmpty()) return false;

        for (String raw : texts) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty() || isFollowLabel(t)) continue;
            String nt = normalizeName(t);
            if (!nt.isEmpty() && nt.contains(nq)) return true;
        }
        return false;
    }

    private String extractUsername(List<String> texts) {
        for (String raw : texts) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.startsWith("@")) s = s.substring(1);
            if (looksLikeUsername(s)) return s;
        }
        return null;
    }

    private String normalizeName(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("@", "")
                .replace("·", "")
                .replace("•", "");
    }

    private void findFollowActionNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;

        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        if (isFollowLabel(text) || isFollowLabel(desc)) out.add(node);

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findFollowActionNodes(child, out);
        }
    }

    private boolean isFollowLabel(String s) {
        if (s == null) return false;
        String x = s.trim().toLowerCase(Locale.ROOT);
        if (FOLLOW_LABELS.contains(x)) return true;
        return x.equals("팔로우하기") || x.equals("follow user") || x.equals("following user");
    }

    private boolean looksLikeUsername(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.startsWith("@")) s = s.substring(1);
        if (s.length() < 2 || s.length() > 30) return false;
        if (!s.matches("[A-Za-z0-9._]+")) return false;
        if (!s.matches(".*[A-Za-z].*")) return false;
        return !BLOCKED_WORDS.contains(s.toLowerCase(Locale.ROOT));
    }

    private boolean clickSearchNavigationNearBottom(AccessibilityNodeInfo root) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int minY = rootBounds.top + Math.max(1, (int) (rootBounds.height() * 0.60f));

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findNodesMatchingLabel(root, candidates, "검색", "Search");
        for (AccessibilityNodeInfo n : candidates) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.centerY() >= minY && safeClickAllowed(n, "검색", "Search")) return true;
        }
        return false;
    }

    private boolean clickSearchBoxNearTop(AccessibilityNodeInfo root) {
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int maxY = rootBounds.top + Math.max(1, (int) (rootBounds.height() * 0.35f));

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findNodesMatchingLabel(root, candidates, "검색", "Search");
        for (AccessibilityNodeInfo n : candidates) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.centerY() <= maxY && safeClickAllowed(n, "검색", "Search")) return true;
        }
        return false;
    }

    private void findNodesMatchingLabel(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, String... labels) {
        if (node == null) return;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        for (String label : labels) {
            if (text.equalsIgnoreCase(label)
                    || desc.equalsIgnoreCase(label)
                    || desc.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
                out.add(node);
                break;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findNodesMatchingLabel(child, out, labels);
        }
    }

    private AccessibilityNodeInfo findExactTextNode(AccessibilityNodeInfo root, String... labels) {
        if (root == null) return null;
        String text = root.getText() == null ? "" : root.getText().toString().trim();
        for (String label : labels) {
            if (text.equalsIgnoreCase(label)) return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findExactTextNode(child, labels);
            if (found != null) return found;
        }
        return null;
    }

    private boolean safeClickAllowed(AccessibilityNodeInfo target, String... allowedLabels) {
        if (target == null) return false;
        AccessibilityNodeInfo n = target;
        for (int depth = 0; depth < 4 && n != null; depth++) {
            if (containsForbiddenClickLabel(n)) return false;
            if (n.isClickable() && labelMatchesAllowed(target, allowedLabels)) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            n = n.getParent();
        }
        return false;
    }

    private boolean labelMatchesAllowed(AccessibilityNodeInfo node, String... allowedLabels) {
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        for (String label : allowedLabels) {
            if (text.equalsIgnoreCase(label)
                    || desc.equalsIgnoreCase(label)
                    || desc.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsForbiddenClickLabel(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String text = node.getText() == null ? "" : node.getText().toString().trim().toLowerCase(Locale.ROOT);
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim().toLowerCase(Locale.ROOT);
        if (FORBIDDEN_CLICK_LABELS.contains(text) || FORBIDDEN_CLICK_LABELS.contains(desc)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && containsForbiddenClickLabel(child)) return true;
        }
        return false;
    }

    private void safeFocusEditable(AccessibilityNodeInfo edit) {
        if (edit == null) return;
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("EditText") && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findFirstEditable(child);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findBestScrollable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectScrollable(root, nodes);
        AccessibilityNodeInfo best = null;
        long bestArea = -1;
        for (AccessibilityNodeInfo n : nodes) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            long area = (long) Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea) {
                bestArea = area;
                best = n;
            }
        }
        return best;
    }

    private void collectScrollable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isScrollable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectScrollable(child, out);
        }
    }

    private void collectVisibleTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectVisibleTexts(child, out);
        }
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
            if (lower.endsWith("k")) {
                mult = 1000;
                s = s.substring(0, s.length() - 1);
            } else if (lower.endsWith("m")) {
                mult = 1000000;
                s = s.substring(0, s.length() - 1);
            } else if (s.endsWith("만")) {
                mult = 10000;
                s = s.substring(0, s.length() - 1);
            } else if (s.endsWith("천")) {
                mult = 1000;
                s = s.substring(0, s.length() - 1);
            }
            return (int) Math.round(Double.parseDouble(s) * mult);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private JSONArray readArray(String s) {
        try {
            return new JSONArray(s == null ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private void log(String message) {
        prefs().edit().putString("lastLog", message).apply();
    }

    private void fail(String message) {
        prefs().edit()
                .putBoolean("running", false)
                .putString("phase", "ERROR")
                .putString("lastLog", message)
                .apply();
    }
}
