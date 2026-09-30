package com.orbinexia.instafinder;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    public static final String PREFS = "finder_state";

    private EditText queryInput;
    private EditText maxPostsInput;
    private EditText maxFollowersInput;
    private CheckBox highlightOnly;
    private TextView statusView;
    private TextView resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadPrefs();
        renderResults();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderResults();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Instagram 공개 계정 Finder · Android beta");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Instagram 앱의 계정 검색 결과를 스크롤한 뒤 공개 프로필만 확인합니다. 자동 DM, 비공개 우회, 나이/성별 추정 기능은 없습니다.");
        info.setTextSize(14);
        info.setPadding(0, dp(8), 0, dp(14));
        root.addView(info);

        queryInput = new EditText(this);
        queryInput.setHint("이름 입력 (예: 민지 / 김민지)");
        queryInput.setSingleLine(true);
        root.addView(queryInput, matchWrap());

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0, dp(8), 0, 0);

        maxPostsInput = new EditText(this);
        maxPostsInput.setHint("게시물 <");
        maxPostsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxPostsInput.setText("10");
        filters.addView(maxPostsInput, new LinearLayout.LayoutParams(0, dp(52), 1f));

        maxFollowersInput = new EditText(this);
        maxFollowersInput.setHint("팔로워 <");
        maxFollowersInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxFollowersInput.setText("1000");
        LinearLayout.LayoutParams followerParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        followerParams.setMargins(dp(8), 0, 0, 0);
        filters.addView(maxFollowersInput, followerParams);
        root.addView(filters);

        highlightOnly = new CheckBox(this);
        highlightOnly.setText("하이라이트가 있는 공개 계정만 결과에 남기기");
        root.addView(highlightOnly);

        Button accessibility = new Button(this);
        accessibility.setText("1. 접근성 권한 설정 열기");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, matchWrap());

        Button start = new Button(this);
        start.setText("2. Instagram 검색 시작");
        start.setOnClickListener(v -> startSearch());
        root.addView(start, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = new Button(this);
        refresh.setText("결과 새로고침");
        refresh.setOnClickListener(v -> renderResults());
        controls.addView(refresh, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button stop = new Button(this);
        stop.setText("중지");
        stop.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).apply();
            renderResults();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        controls.addView(stop, stopParams);
        root.addView(controls);

        Button clear = new Button(this);
        clear.setText("결과/후보 초기화");
        clear.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("results", "[]")
                    .putString("candidates", "[]")
                    .putString("phase", "IDLE")
                    .putBoolean("running", false)
                    .apply();
            renderResults();
        });
        root.addView(clear, matchWrap());

        statusView = new TextView(this);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(12), 0, dp(8));
        root.addView(statusView);

        resultView = new TextView(this);
        resultView.setTypeface(Typeface.MONOSPACE);
        resultView.setTextSize(14);
        resultView.setTextIsSelectable(true);
        root.addView(resultView);

        setContentView(scroll);
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        queryInput.setText(p.getString("query", ""));
        maxPostsInput.setText(String.valueOf(p.getInt("maxPosts", 10)));
        maxFollowersInput.setText(String.valueOf(p.getInt("maxFollowers", 1000)));
        highlightOnly.setChecked(p.getBoolean("highlightOnly", false));
    }

    private void startSearch() {
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "검색할 이름을 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int maxPosts = parseInt(maxPostsInput.getText().toString(), 10);
        int maxFollowers = parseInt(maxFollowersInput.getText().toString(), 1000);

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("query", query)
                .putInt("maxPosts", maxPosts)
                .putInt("maxFollowers", maxFollowers)
                .putBoolean("highlightOnly", highlightOnly.isChecked())
                .putBoolean("running", true)
                .putString("phase", "OPEN_SEARCH")
                .putString("candidates", "[]")
                .putString("results", "[]")
                .putInt("inspectIndex", 0)
                .putString("currentUsername", "")
                .putString("lastLog", "Instagram 실행 준비")
                .apply();

        Intent launch = getPackageManager().getLaunchIntentForPackage("com.instagram.android");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            Toast.makeText(this, "Instagram을 열었습니다. 접근성 서비스가 검색을 진행합니다.", Toast.LENGTH_LONG).show();
        } else {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.instagram.android")));
            } catch (Exception e) {
                Toast.makeText(this, "Instagram 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
            }
        }
        renderResults();
    }

    private void renderResults() {
        if (statusView == null || resultView == null) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String phase = p.getString("phase", "IDLE");
        boolean running = p.getBoolean("running", false);
        String log = p.getString("lastLog", "");
        int candidateCount = jsonLength(p.getString("candidates", "[]"));
        int resultCount = jsonLength(p.getString("results", "[]"));

        statusView.setText("상태: " + (running ? "실행 중" : "대기/완료")
                + "\n단계: " + phase
                + "\n후보: " + candidateCount + "  결과: " + resultCount
                + "\n" + log);

        StringBuilder out = new StringBuilder();
        out.append("조건 통과 계정\n\n");
        try {
            JSONArray arr = new JSONArray(p.getString("results", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.append(i + 1).append(". @").append(o.optString("username"))
                        .append("\n   게시물 ").append(o.optInt("posts", -1))
                        .append(" / 팔로워 ").append(o.optInt("followers", -1))
                        .append(" / 하이라이트 ").append(o.optBoolean("highlights", false) ? "O" : "X")
                        .append("\n   https://www.instagram.com/").append(o.optString("username")).append("/\n\n");
            }
        } catch (Exception e) {
            out.append("결과 읽기 오류: ").append(e.getMessage());
        }
        resultView.setText(out.toString());
    }

    private int jsonLength(String s) {
        try { return new JSONArray(s).length(); } catch (Exception e) { return 0; }
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return fallback; }
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
