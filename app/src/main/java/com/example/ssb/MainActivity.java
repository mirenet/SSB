package com.example.ssb;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusChecker;
    
    // Tvoji GitHub podaci (Ovde unesi svoje podatke)
    private static final String GITHUB_TOKEN = "TVOJ_GITHUB_PERSONAL_ACCESS_TOKEN";
    private static final String GITHUB_OWNER = "tvoje_korisnicko_ime";
    private static final String GITHUB_REPO = "ime_repozitorijuma_kostura";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        
        // Povezivanje JavaScript mosta sa aplikacijom
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void startCreatingApp(String appName, String packageName, String iconBase64, String htmlData, String configContent) {
            triggerGitHubAction(appName, packageName, iconBase64, htmlData, configContent);
        }

        @JavascriptInterface
        public void installApp() {
            promptInstall();
        }
    }

    private void triggerGitHubAction(String appName, String packageName, String iconBase64, String htmlData, String configContent) {
        OkHttpClient client = new OkHttpClient();
        
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("event_type", "build-app");
            
            JSONObject clientPayload = new JSONObject();
            clientPayload.put("app_name", appName);
            clientPayload.put("package_name", packageName);
            clientPayload.put("icon_base64", iconBase64);
            clientPayload.put("html_data", htmlData);
            clientPayload.put("config_content", configContent);
            
            jsonBody.put("client_payload", clientPayload);

            RequestBody body = RequestBody.create(jsonBody.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url("https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/dispatches")
                    .addHeader("Authorization", "Bearer " + GITHUB_TOKEN)
                    .addHeader("Accept", "application/vnd.github+json")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        startCheckingBuildStatus();
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCheckingBuildStatus() {
        statusChecker = new Runnable() {
            @Override
            public void run() {
                checkLatestWorkflowRun();
            }
        };
        handler.postDelayed(statusChecker, 30000); // Provera na svakih 30 sekundi
    }

    private void checkLatestWorkflowRun() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/actions/runs?per_page=1")
                .addHeader("Authorization", "Bearer " + GITHUB_TOKEN)
                .addHeader("Accept", "application/vnd.github+json")
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
        Request request = new Request.Builder()
                .url(artifactsUrl)
                .addHeader("Authorization", "Bearer " + GITHUB_TOKEN)
                .addHeader("Accept", "application/vnd.github+json")
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
        Request request = new Request.Builder()
                .url(zipUrl)
                .addHeader("Authorization", "Bearer " + GITHUB_TOKEN)
                .addHeader("Accept", "application/vnd.github+json")
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

                    // Raspakuj zip da izvuces APK
                    unzipApk(zipFile);

                    // Javi JavaScript-u da je sve spremno za dugme Install
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            webView.evaluateJavascript("setAppReadyForInstall();", null);
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
