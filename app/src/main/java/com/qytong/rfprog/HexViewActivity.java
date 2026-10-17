package com.qytong.rfprog;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 原始数据十六进制查看（分页 2048 字节/页）
 */
public class HexViewActivity extends Activity {

    public static byte[] image;
    public static String title = "原始数据";

    private static final int PAGE = 2048;
    private int page = 0;
    private boolean switching = false;
    private TextView tvHex;
    private EditText etGoto;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_hex);

        tvHex = (TextView) findViewById(R.id.tvHex);
        etGoto = (EditText) findViewById(R.id.etGoto);
        Button btnGoto = (Button) findViewById(R.id.btnGoto);
        scrollView = (ScrollView) tvHex.getParent();
        TextView tvTitle = (TextView) findViewById(R.id.tvHexTitle);
        if (tvTitle != null) tvTitle.setText(title);

        btnGoto.setOnClickListener(new GotoClick(this));
        scrollView.getViewTreeObserver().addOnScrollChangedListener(new PageScrollListener(this));
        render();
    }

    int getPage() {
        return page;
    }

    void setPage(int p) {
        page = p;
    }

    boolean isSwitching() {
        return switching;
    }

    void setSwitching(boolean s) {
        switching = s;
    }

    int maxPage() {
        if (image == null) return 1;
        return (image.length + PAGE - 1) / PAGE;
    }

    String gotoText() {
        return etGoto.getText().toString().trim();
    }

    ScrollView getScrollView() {
        return scrollView;
    }

    void render() {
        if (image == null) {
            tvHex.setText("无镜像数据");
            return;
        }
        int start = page * PAGE;
        int end = Math.min(start + PAGE, image.length);
        StringBuilder sb = new StringBuilder();
        sb.append("== 第 ").append(page + 1).append('/').append(maxPage())
                .append(" 页  0x").append(Integer.toHexString(start).toUpperCase())
                .append(" - 0x").append(Integer.toHexString(end - 1).toUpperCase())
                .append(" ==\n");
        for (int off = start; off < end; off += 16) {
            sb.append(String.format("%04X: ", off));
            int lineEnd = Math.min(off + 16, end);
            for (int i = off; i < lineEnd; i++) {
                sb.append(String.format("%02X ", image[i] & 0xFF));
            }
            sb.append('\n');
        }
        tvHex.setText(sb.toString());
    }

    static class GotoClick implements View.OnClickListener {
        private final HexViewActivity act;

        GotoClick(HexViewActivity a) {
            act = a;
        }

        @Override
        public void onClick(View v) {
            try {
                int addr = Integer.parseInt(act.gotoText(), 16);
                int p = addr / PAGE;
                if (p < 0 || p >= act.maxPage()) p = 0;
                act.setPage(p);
                act.render();
            } catch (Exception ignored) {
            }
        }
    }

    static class PageScrollListener implements ViewTreeObserver.OnScrollChangedListener {
        private final HexViewActivity act;

        PageScrollListener(HexViewActivity a) {
            act = a;
        }

        @Override
        public void onScrollChanged() {
            if (act.isSwitching()) return;
            if (act.getPage() + 1 >= act.maxPage()) return;
            ScrollView sv = act.getScrollView();
            if (sv == null) return;
            int diff = sv.getChildAt(0).getMeasuredHeight() - (sv.getHeight() + sv.getScrollY());
            if (diff <= 2) {
                act.setSwitching(true);
                act.setPage(act.getPage() + 1);
                act.render();
                act.getScrollView().post(new Runnable() {
                    @Override
                    public void run() {
                        act.setSwitching(false);
                    }
                });
            }
        }
    }
}
