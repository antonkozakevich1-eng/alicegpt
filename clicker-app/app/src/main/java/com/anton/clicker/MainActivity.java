package com.anton.clicker;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0E0A24"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E0A24"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0E0A24"));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        // localStorage нужен для сохранения прогресса
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onPause() {
        // Сохраняем игру при сворачивании приложения
        webView.evaluateJavascript("window.saveGame && saveGame()", null);
        super.onPause();
    }
}
