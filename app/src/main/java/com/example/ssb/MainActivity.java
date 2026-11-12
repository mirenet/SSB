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
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusChecker;
    
    // Za pretragu fajlova u WebView-u
    private ValueCallback<Uri[]> uploadMessage;
    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    
    // GitHub podaci (vlasnik i repozitorijum su uneseni, token se čita dinamički iz assets/token.txt)
    private static final String GITHUB_OWNER = "mirenet";
    private static final String GITHUB_REPO = "skeleton";

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
        webView.setWebViewClient(new WebViewClient());
        
        // KLJUČNO: Omogućava otvaranje fajl explorera iz HTML input type="file"
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
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
                    showAlert("Greška", "Nema instalirane aplikacije za pregled fajlova.");
                    return false;
                }
                return true;
            }
        });
        
        webView.loadUrl("file:///android_asset/index.html");
    }

    // Pomoćna metoda koja u hodu čita token iz assets/token.txt fajla
    private String readTokenFromAssets() {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open("token.txt"))
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line.trim());
            }
            reader.close();
            return sb.toString().trim();
        } catch (Exception e) {
            e.printStackTrace();
            return ""; 
        }
    }

    // Prihvatamo izabrani fajl iz sistema i vraćamo ga u WebView
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (uploadMessage == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && intent != null) {
                String dataString = intent.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            uploadMessage.onReceiveValue(results);
            uploadMessage = null;
        }
    }

    private void showAlert(String title, String message) {
        handler.post(() -> {
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
        });
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void startCreatingApp(String appName, String packageName, String iconBase64, String finalDataToSend, String configContent) {
            String startUrl = "";
            String htmlData = "";

            if (finalDataToSend != null && (finalDataToSend.startsWith("http://") || finalDataToSend.startsWith("https://"))) {
                startUrl = finalDataToSend;
            } else {
                htmlData = finalDataToSend;
            }

            triggerGitHubAction(appName, packageName, iconBase64, htmlData, startUrl, configContent);
        }

        @JavascriptInterface
        public void installApp() {
            promptInstall();
        }

        @JavascriptInterface
        public void closeApp() {
            finish();
        }
    }

    private void triggerGitHubAction(String appName, String packageName, String iconBase64, String htmlData, String startUrl, String configContent) {
        OkHttpClient client = new OkHttpClient();
        String currentToken = readTokenFromAssets();

        if (currentToken.isEmpty()) {
            showAlert("Greška", "GitHub token nije pronađen u assets/token.txt fajlu!");
            return;
        }
        
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("event_type", "build-app");
            
            JSONObject clientPayload = new JSONObject();
            clientPayload.put("app_name", appName != null ? appName : "");
            clientPayload.put("package_name", packageName != null ? packageName : "");
            clientPayload.put("icon_base64", iconBase64 != null ? iconBase64 : "");
            clientPayload.put("html_data", htmlData != null ? htmlData : "");
            clientPayload.put("start_url", startUrl != null ? startUrl : "");
            clientPayload.put("config_content", configContent != null ? configContent : "");
            
            jsonBody.put("client_payload", clientPayload);

            RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8"));
            
            String url = "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/dispatches";
            
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + currentToken)
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("X-GitHub-Api-Version", "2022-11-28")
                    .addHeader("User-Agent", "SSBFactory-App")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    showAlert("Mrežna greška", e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        showAlert("Uspeh!", "Zahtev je uspešno poslat na GitHub!");
                        startCheckingBuildStatus();
                    } else {
                        showAlert("GitHub Status " + response.code(), responseBody);
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Izuzetak", e.getMessage());
        }
    }

    private void startCheckingBuildStatus() {
        statusChecker = new Runnable() {
            @Override
            public void run() {
                checkLatestWorkflowRun();
            }
        };
        handler.postDelayed(statusChecker, 30000);
    }

    private void checkLatestWorkflowRun() {
        OkHttpClient client = new OkHttpClient();
        String currentToken = readTokenFromAssets();

        Request request = new Request.Builder()
                .url("https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/actions/runs?per_page=1")
                .addHeader("Authorization", "Bearer " + currentToken)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "SSBFactory-App")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handler.postDelayed(statusChecker, 30000);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String jsonResp = response.body().string();
                        JSONObject obj = new JSONObject(jsonResp);
                        JSONArray runs = obj.getJSONArray("workflow_runs");
                        if (runs.length() > 0) {
                            JSONObject run = runs.getJSONObject(0);
                            String status = run.getString("status"); 
                            String conclusion = run.getString("conclusion"); 

                            if ("completed".equals(status)) {
                                if ("success".equals(conclusion)) {
                                    String artifactsUrl = run.getString("artifacts_url");
                                    downloadArtifactsList(artifactsUrl);
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                handler.postDelayed(statusChecker, 30000);
            }
        });
    }

    private void downloadArtifactsList(String artifactsUrl) {
        OkHttpClient client = new OkHttpClient();
        String currentToken = readTokenFromAssets();

        Request request = new Request.Builder()
                .url(artifactsUrl)
                .addHeader("Authorization", "Bearer " + currentToken)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "SSBFactory-App")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        JSONObject obj = new JSONObject(response.body().string());
                        JSONArray artifacts = obj.getJSONArray("artifacts");
                        if (artifacts.length() > 0) {
                            String downloadUrl = artifacts.getJSONObject(0).getString("archive_download_url");
                            downloadApkZip(downloadUrl);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void downloadApkZip(String zipUrl) {
        OkHttpClient client = new OkHttpClient();
        String currentToken = readTokenFromAssets();

        Request request = new Request.Builder()
                .url(zipUrl)
                .addHeader("Authorization", "Bearer " + currentToken)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "SSBFactory-App")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    File zipFile = new File(getExternalFilesDir(null), "apk.zip");
                    FileOutputStream fos = new FileOutputStream(zipFile);
                    fos.write(response.body().bytes());
                    fos.close();

                    unzipApk(zipFile);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript("setAppReadyForInstall();", null);
                            showAlert("Gotovo!", "APK je uspešno skinut i spreman za instalaciju.");
                        }
                    });
                }
            }
        });
    }

    private void unzipApk(File zipFile) {
        try {
            ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zipFile));
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (ze.getName().endsWith(".apk")) {
                    File f = new File(getExternalFilesDir(null), "app-debug.apk");
                    FileOutputStream fout = new FileOutputStream(f);
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = zis.read(buffer)) != -1) {
                        fout.write(buffer, 0, count);
                    }
                    fout.close();
                    break;
                }
                zis.closeEntry();
            }
            zis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void promptInstall() {
        File apkFile = new File(getExternalFilesDir(null), "app-debug.apk");
        if (!apkFile.exists()) {
            showAlert("Greška", "APK fajl nije pronađen na uređaju!");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                showAlert("Dozvola potrebna", "Molimo omogućite instalaciju aplikacija za ovu fabriku.");
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
}
