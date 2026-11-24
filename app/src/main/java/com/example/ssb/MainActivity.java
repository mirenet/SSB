package com.example.ssb;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_RESULT_CODE = 1;

    // TODO: Upisi svoj GitHub username/org, naziv repo-a i granu
    private static final String GITHUB_OWNER = "YOUR_GITHUB_USERNAME";
    private static final String GITHUB_REPO = "YOUR_SKELETON_REPO";
    private static final String GITHUB_BRANCH = "main";
    private static final String WORKFLOW_FILE = "build.yml";

    private static final String FALLBACK_CONFIG =
            "const config = {\n" +
            "external: [],\n" +
            "startURL: \"\",\n" +
            "broAgent: \"\",\n" +
            "portrait: true,\n" +
            "screenOn: false,\n" +
            "allowZoom: false,\n" +
            "forceDark: false,\n" +
            "refreshOn: false,\n" +
            "scrollBar: true,\n" +
            "mobilView: true,\n" +
            "background: false\n" +
            "};";

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ValueCallback<Uri[]> uploadMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        webView.setBackgroundColor(Color.BLACK);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);

        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                executor.execute(() -> sendDefaultConfigToUi());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (uploadMessage != null) {
                    uploadMessage.onReceiveValue(null);
                    uploadMessage = null;
                }
                uploadMessage = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (ActivityNotFoundException e) {
                    uploadMessage = null;
                    showAlert("Error", "No file picker application found.");
                    return false;
                }
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null && intent.getDataString() != null) {
                results = new Uri[]{Uri.parse(intent.getDataString())};
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void sendDefaultConfigToUi() {
        String content = readDefaultConfigContent();
        String encoded = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        mainHandler.post(() -> webView.evaluateJavascript(
                "setDefaultConfigContent(decodeURIComponent(escape(atob('" + encoded + "'))));",
                null
        ));
    }

    private String readDefaultConfigContent() {
        try (InputStream is = getAssets().open("appConfig.js")) {
            return readStreamToString(is);
        } catch (IOException ignored) {
            return FALLBACK_CONFIG;
        }
    }

    private String readGitHubToken() throws IOException {
        try (InputStream is = getAssets().open("token.txt")) {
            String token = readStreamToString(is).trim();
            if (token.isEmpty()) {
                throw new IOException("assets/token.txt is empty.");
            }
            return token;
        }
    }

    private void triggerGitHubWorkflow(
            String appName,
            String packageName,
            String iconBase64,
            String startUrl,
            String configContent,
            String sdkMin,
            String sdkMax
    ) {
        try {
            if ("YOUR_GITHUB_USERNAME".equals(GITHUB_OWNER) || "YOUR_SKELETON_REPO".equals(GITHUB_REPO)) {
                throw new IllegalArgumentException("Set GITHUB_OWNER and GITHUB_REPO in MainActivity.java.");
            }

            logToUi("[INFO] Reading GitHub token from assets/token.txt..");
            String token = readGitHubToken();

            JSONObject inputs = new JSONObject();
            inputs.put("app_name", appName);
            inputs.put("package_name", packageName);
            inputs.put("start_url", startUrl);
            inputs.put("config_content", configContent);
            inputs.put("min_sdk", sdkMin);
            inputs.put("max_sdk", sdkMax);
            inputs.put("icon_base64", iconBase64);

            JSONObject payload = new JSONObject();
            payload.put("ref", GITHUB_BRANCH);
            payload.put("inputs", inputs);

            String endpoint = "https://api.github.com/repos/"
                    + GITHUB_OWNER + "/" + GITHUB_REPO
                    + "/actions/workflows/" + WORKFLOW_FILE + "/dispatches";

            logToUi("[INFO] Sending workflow_dispatch to " + GITHUB_OWNER + "/" + GITHUB_REPO + "..");

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_NO_CONTENT || responseCode == HttpURLConnection.HTTP_OK) {
                logToUi("[SUCCESS] GitHub Actions workflow triggered (HTTP " + responseCode + ").");
                logToUi("[INFO] Check Actions tab on GitHub for APK artifact.");
                setReadyUi();
            } else {
                InputStream errStream = conn.getErrorStream();
                String errBody = errStream != null ? readStreamToString(errStream) : "";
                throw new IOException("GitHub API HTTP " + responseCode + ": " + errBody);
            }
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
            setErrorUi(e.getMessage() != null ? e.getMessage() : "Failed to trigger GitHub workflow.");
        }
    }

    private String readStreamToString(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private void logToUi(String message) {
        mainHandler.post(() -> {
            String escaped = message
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n");
            webView.evaluateJavascript("appendLog('" + escaped + "');", null);
        });
    }

    private void setReadyUi() {
        mainHandler.post(() -> webView.evaluateJavascript("setAppReadyForInstall();", null));
    }

    private void setErrorUi(String errorMsg) {
        mainHandler.post(() -> {
            String escaped = errorMsg
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n");
            webView.evaluateJavascript("setBuildError('" + escaped + "');", null);
        });
    }

    private void showAlert(String title, String message) {
        mainHandler.post(() -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show());
    }

    private void openActionsPage() {
        String url = "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/actions";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            showAlert("GitHub Actions", url);
        }
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void requestDefaultConfig() {
            executor.execute(() -> sendDefaultConfigToUi());
        }

        @JavascriptInterface
        public void inspectImportedApk(String importedApkBase64) {
            if (importedApkBase64 == null || importedApkBase64.trim().isEmpty()) return;
            executor.execute(() -> {
                try {
                    File importedFile = new File(getCacheDir(), "imported_source.apk");
                    byte[] bytes = Base64.decode(importedApkBase64.trim(), Base64.DEFAULT);
                    try (FileOutputStream fos = new FileOutputStream(importedFile)) {
                        fos.write(bytes);
                    }
                    logToUi("[INFO] Cached imported APK (" + bytes.length + " bytes).");
                } catch (Exception e) {
                    logToUi("[WARN] Failed to cache imported APK: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void reloadSkeletonMetadata() {
            executor.execute(() -> sendDefaultConfigToUi());
        }

        @JavascriptInterface
        public void startCreatingApp(
                String appName,
                String packageName,
                String iconBase64,
                String startUrl,
                String configContent,
                String sdkMin,
                String sdkMax,
                String importedApkBase64
        ) {
            executor.execute(() -> triggerGitHubWorkflow(
                    appName != null ? appName.trim() : "",
                    packageName != null ? packageName.trim() : "",
                    iconBase64 != null ? iconBase64.trim() : "",
                    startUrl != null ? startUrl.trim() : "",
                    configContent != null ? configContent : "",
                    sdkMin != null ? sdkMin.trim() : "",
                    sdkMax != null ? sdkMax.trim() : ""
            ));
        }

        @JavascriptInterface
        public void installApp() {
            mainHandler.post(() -> openActionsPage());
        }

        @JavascriptInterface
        public void closeApp() {
            mainHandler.post(() -> finish());
        }
    }
}
