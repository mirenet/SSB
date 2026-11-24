package com.example.ssb;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_RESULT_CODE = 1;
    private static final long POLL_INTERVAL_MS = 10_000L;
    private static final int MAX_POLL_ATTEMPTS = 90;

    private static final String GITHUB_OWNER = "mirenet";
    private static final String GITHUB_REPO = "skeleton";
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
    private volatile boolean buildCancelled = false;

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
        buildCancelled = true;
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
            String raw = readStreamToString(is);
            // Ukloni UTF-8 BOM, razmake i nove redove
            String token = raw.replace("\uFEFF", "").trim();
            if (token.isEmpty()) {
                throw new IOException("assets/token.txt is empty.");
            }
            if (token.contains(" ") || token.contains("\n") || token.contains("\r")) {
                throw new IOException("assets/token.txt contains spaces or multiple lines.");
            }
            return token;
        } catch (IOException e) {
            throw new IOException("Token error (assets/token.txt): " + e.getMessage(), e);
        }
    }

    private void triggerAndMonitorGitHubBuild(
            String appName,
            String packageName,
            String iconBase64,
            String startUrl,
            String configContent,
            String sdkMin,
            String sdkMax
    ) {
        buildCancelled = false;
        try {
            if (GITHUB_OWNER == null || GITHUB_OWNER.trim().isEmpty()
                    || GITHUB_REPO == null || GITHUB_REPO.trim().isEmpty()
                    || "YOUR_GITHUB_USERNAME".equals(GITHUB_OWNER)
                    || "YOUR_SKELETON_REPO".equals(GITHUB_REPO)) {
                throw new IllegalArgumentException("Set GITHUB_OWNER and GITHUB_REPO in MainActivity.java.");
            }

            logToUi("[INFO] Reading token from assets/token.txt..");
            String token = readGitHubToken();
            long dispatchStartTimeMs = System.currentTimeMillis();

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

            String dispatchUrl = "https://api.github.com/repos/"
                    + GITHUB_OWNER + "/" + GITHUB_REPO
                    + "/actions/workflows/" + WORKFLOW_FILE + "/dispatches";

            logToUi("[INFO] Dispatching workflow " + WORKFLOW_FILE + " on " + GITHUB_BRANCH + "..");
            int dispatchCode = postJson(token, dispatchUrl, payload.toString());
            if (dispatchCode != HttpURLConnection.HTTP_NO_CONTENT && dispatchCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Workflow dispatch failed (HTTP " + dispatchCode + ").");
            }

            logToUi("[INFO] Workflow triggered. Polling build status every 10s..");
            long runId = pollForCompletedRunId(token, dispatchStartTimeMs);
            if (buildCancelled) return;

            logToUi("[INFO] Build finished (run #" + runId + "). Fetching artifact..");
            File outApk = getOutputApkFile();
            downloadApkFromRunArtifacts(token, runId, outApk);

            logToUi("[SUCCESS] APK ready: " + outApk.getName());
            setReadyUi();
        } catch (Exception e) {
            if (buildCancelled) {
                logToUi("[INFO] Build monitoring cancelled.");
                return;
            }
            e.printStackTrace();
            setErrorUi(e.getMessage() != null ? e.getMessage() : "GitHub build failed.");
        }
    }

    private long parseIsoToMillis(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return 0L;
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(isoDate);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private long pollForCompletedRunId(String token, long dispatchStartTimeMs) throws Exception {
        String runsUrl = "https://api.github.com/repos/"
                + GITHUB_OWNER + "/" + GITHUB_REPO
                + "/actions/workflows/" + WORKFLOW_FILE
                + "/runs?branch=" + GITHUB_BRANCH + "&event=workflow_dispatch&per_page=10";

        long minAllowedTimeMs = dispatchStartTimeMs - 30_000L;

        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            if (buildCancelled) {
                throw new InterruptedException("Cancelled");
            }

            String jsonText = httpGetString(token, runsUrl);
            JSONObject root = new JSONObject(jsonText);
            JSONArray runs = root.optJSONArray("workflow_runs");

            JSONObject targetRun = null;
            if (runs != null) {
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject run = runs.optJSONObject(i);
                    if (run == null) continue;
                    long createdMs = parseIsoToMillis(run.optString("created_at", ""));
                    if (createdMs == 0L || createdMs >= minAllowedTimeMs) {
                        targetRun = run;
                        break;
                    }
                }
            }

            if (targetRun == null) {
                logToUi("[POLL " + attempt + "] Waiting for workflow run to appear.. (10s)");
            } else {
                long runId = targetRun.optLong("id", -1L);
                String status = targetRun.optString("status", "unknown");
                String conclusion = targetRun.optString("conclusion", "");
                logToUi("[POLL " + attempt + "] Run #" + runId + " | status: " + status
                        + (conclusion.isEmpty() ? "" : " | conclusion: " + conclusion));

                if ("completed".equalsIgnoreCase(status)) {
                    if ("success".equalsIgnoreCase(conclusion)) {
                        return runId;
                    } else {
                        String htmlUrl = targetRun.optString("html_url", "");
                        throw new IOException("Workflow failed (" + conclusion + "). " + htmlUrl);
                    }
                }
            }

            if (attempt < MAX_POLL_ATTEMPTS) {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        throw new IOException("Timed out waiting for GitHub Actions build to complete.");
    }

    private void downloadApkFromRunArtifacts(String token, long runId, File destApk) throws Exception {
        String artifactsUrl = "https://api.github.com/repos/"
                + GITHUB_OWNER + "/" + GITHUB_REPO
                + "/actions/runs/" + runId + "/artifacts";

        String jsonText = httpGetString(token, artifactsUrl);
        JSONObject root = new JSONObject(jsonText);
        JSONArray artifacts = root.optJSONArray("artifacts");
        if (artifacts == null || artifacts.length() == 0) {
            throw new IOException("No artifacts found for run #" + runId + ".");
        }

        JSONObject chosen = artifacts.optJSONObject(0);
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject item = artifacts.optJSONObject(i);
            if (item != null && "app-debug".equalsIgnoreCase(item.optString("name", ""))) {
                chosen = item;
                break;
            }
        }

        if (chosen == null) {
            throw new IOException("Could not select artifact from run #" + runId + ".");
        }

        String artifactName = chosen.optString("name", "artifact");
        String downloadUrl = chosen.optString("archive_download_url", "");
        if (downloadUrl.isEmpty()) {
            throw new IOException("Artifact archive_download_url missing.");
        }

        File workDir = new File(getCacheDir(), "gh_artifact");
        if (workDir.exists()) {
            deleteRecursive(workDir);
        }
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        File zipFile = new File(workDir, artifactName + ".zip");
        logToUi("[INFO] Downloading artifact '" + artifactName + "'..");
        httpDownloadToFile(token, downloadUrl, zipFile);

        logToUi("[INFO] Extracting APK from artifact ZIP..");
        boolean extractedApk = extractFirstApkFromZip(zipFile, destApk);
        if (!extractedApk || !destApk.exists() || destApk.length() == 0) {
            throw new IOException("No .apk file found inside downloaded artifact.");
        }
    }

    private boolean extractFirstApkFromZip(File zipFile, File destApk) throws IOException {
        File parent = destApk.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    try (FileOutputStream fos = new FileOutputStream(destApk)) {
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    zis.closeEntry();
                    return true;
                }
                zis.closeEntry();
            }
        }
        return false;
    }

    private int postJson(String token, String endpoint, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

        byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(out);
        }

        int code = conn.getResponseCode();
        if (code >= 400) {
            InputStream err = conn.getErrorStream();
            String errText = err != null ? readStreamToString(err) : "";
            conn.disconnect();
            throw new IOException("GitHub API POST HTTP " + code + ": " + errText.trim());
        }
        conn.disconnect();
        return code;
    }

    private String httpGetString(String token, String endpoint) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

        int code = conn.getResponseCode();
        InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        String body = stream != null ? readStreamToString(stream) : "";
        conn.disconnect();

        if (code >= 400) {
            throw new IOException("GitHub API GET HTTP " + code + ": " + body.trim());
        }
        return body;
    }

    private void httpDownloadToFile(String token, String endpoint, File outFile) throws IOException {
        String currentUrl = endpoint;
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without Location header (HTTP " + code + ").");
                }
                currentUrl = location;
                continue;
            }

            if (code >= 400) {
                InputStream err = conn.getErrorStream();
                String errText = err != null ? readStreamToString(err) : "";
                conn.disconnect();
                throw new IOException("Download failed (HTTP " + code + "): " + errText.trim());
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
            conn.disconnect();
            return;
        }
        throw new IOException("Too many redirects while downloading artifact.");
    }

    private File getOutputApkFile() {
        File outDir = getExternalFilesDir(null);
        if (outDir != null && !outDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        }
        return new File(outDir, "output-signed.apk");
    }

    private void promptInstall() {
        File apkFile = getOutputApkFile();
        if (!apkFile.exists()) {
            showAlert("Error", "Downloaded APK file not found!");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                showAlert("Permission required", "Enable install unknown apps permission for this app.");
                Uri settingsUri = Uri.parse("package:" + getPackageName());
                Intent settingsIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settingsUri);
                startActivity(settingsIntent);
                return;
            }
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
            buildCancelled = true;
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
            executor.execute(() -> triggerAndMonitorGitHubBuild(
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
            mainHandler.post(() -> promptInstall());
        }

        @JavascriptInterface
        public void closeApp() {
            mainHandler.post(() -> finish());
        }
    }
}
