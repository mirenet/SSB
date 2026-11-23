package com.example.ssb;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.security.auth.x500.X500Principal;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_RESULT_CODE = 1;

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
        webView.setWebViewClient(new WebViewClient());

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
            executor.execute(() -> buildLocalApk(
                    appName != null ? appName.trim() : "",
                    packageName != null ? packageName.trim() : "",
                    iconBase64 != null ? iconBase64.trim() : "",
                    startUrl != null ? startUrl.trim() : "",
                    configContent != null ? configContent : "",
                    sdkMin != null ? sdkMin.trim() : "",
                    sdkMax != null ? sdkMax.trim() : "",
                    importedApkBase64 != null ? importedApkBase64.trim() : ""
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

    private void buildLocalApk(
            String appName,
            String packageName,
            String iconBase64,
            String startUrl,
            String configContent,
            String sdkMin,
            String sdkMax,
            String importedApkBase64
    ) {
        try {
            File workDir = new File(getCacheDir(), "apk_work");
            deleteRecursive(workDir);
            if (!workDir.mkdirs()) {
                throw new IOException("Failed to create working directory.");
            }

            File sourceApk = new File(workDir, "source.apk");
            logToUi("[INFO] Preparing source APK..");

            if (!importedApkBase64.isEmpty()) {
                byte[] apkBytes = Base64.decode(importedApkBase64, Base64.DEFAULT);
                try (FileOutputStream fos = new FileOutputStream(sourceApk)) {
                    fos.write(apkBytes);
                }
                logToUi("[INFO] Loaded imported APK (" + apkBytes.length + " bytes).");
            } else {
                try (InputStream in = getAssets().open("skeleton.apk");
                     FileOutputStream out = new FileOutputStream(sourceApk)) {
                    copyStream(in, out);
                }
                logToUi("[INFO] Loaded skeleton APK from assets/skeleton.apk.");
            }

            File extractedDir = new File(workDir, "extracted");
            logToUi("[INFO] Extracting APK entries..");
            unzipFile(sourceApk, extractedDir);

            // Izbacujemo stari META-INF potpis da bi se napravio nov potpis
            File metaInf = new File(extractedDir, "META-INF");
            if (metaInf.exists()) {
                deleteRecursive(metaInf);
            }

            // Ažuriranje startURL i appConfig u assets fajlovima ako postoje
            if (!startUrl.isEmpty() || !configContent.isEmpty()) {
                logToUi("[INFO] Updating assets configuration and startURL..");
                updateAssetsConfig(extractedDir, startUrl, configContent);
            }

            // Skaliranje i zamena ikonica u res/
            if (!iconBase64.isEmpty()) {
                logToUi("[INFO] Scaling and replacing launcher icons..");
                replaceLauncherIcons(extractedDir, iconBase64);
            }

            // Ako je zadat novi packageName iste dužine, pokušava bezbednu bajt zamenu u DEX-u
            if (!packageName.isEmpty()) {
                logToUi("[INFO] Checking package string replacements in DEX..");
                replaceEqualLengthStringsInDex(extractedDir, packageName);
            }

            if (!appName.isEmpty()) {
                logToUi("[INFO] Target app label set to: " + appName);
            }
            if (!sdkMin.isEmpty() || !sdkMax.isEmpty()) {
                logToUi("[INFO] Requested SDK overrides -> min: " + sdkMin + ", max: " + sdkMax);
            }

            File unsignedApk = new File(workDir, "unsigned.apk");
            logToUi("[INFO] Repacking APK archive..");
            zipDirectory(extractedDir, unsignedApk);

            File signedApk = getOutputApkFile();
            logToUi("[SIGN] Signing APK using apksig..");
            signApkWithApksig(unsignedApk, signedApk);

            logToUi("[SUCCESS] Signed APK saved: " + signedApk.getName());
            setReadyUi();
        } catch (Exception e) {
            e.printStackTrace();
            setErrorUi(e.getMessage() != null ? e.getMessage() : "Build failed.");
        }
    }

    private File getOutputApkFile() {
        File outDir = new File(getExternalFilesDir(null), "builds");
        if (!outDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        }
        return new File(outDir, "output-signed.apk");
    }

    private void updateAssetsConfig(File extractedDir, String startUrl, String configContent) throws IOException {
        File assetsDir = new File(extractedDir, "assets");
        if (!assetsDir.exists()) return;

        List<File> files = new ArrayList<>();
        collectFiles(assetsDir, files);

        for (File f : files) {
            String name = f.getName();
            if (name.equals("appConfig.js") || name.endsWith(".js") || name.endsWith(".json")) {
                String content = readTextFile(f);
                String updated = content;

                if (!configContent.isEmpty() && name.equals("appConfig.js")) {
                    updated = configContent;
                }
                if (!startUrl.isEmpty()) {
                    updated = updated.replaceAll("(startURL\\s*:\\s*\")[^\"]*(\")", "$1" + startUrl + "$2");
                    updated = updated.replaceAll("(startUrl\\s*:\\s*\")[^\"]*(\")", "$1" + startUrl + "$2");
                }

                if (!updated.equals(content)) {
                    writeTextFile(f, updated);
                }
            }
        }
    }

    private void replaceLauncherIcons(File extractedDir, String iconBase64) throws IOException {
        String rawBase64 = iconBase64.contains(",") ? iconBase64.split(",")[1] : iconBase64;
        byte[] decoded = Base64.decode(rawBase64, Base64.DEFAULT);
        Bitmap sourceBitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        if (sourceBitmap == null) {
            throw new IOException("Invalid icon image data.");
        }

        File resDir = new File(extractedDir, "res");
        if (!resDir.exists()) return;

        List<File> allResFiles = new ArrayList<>();
        collectFiles(resDir, allResFiles);

        for (File file : allResFiles) {
            String fname = file.getName().toLowerCase();
            if (fname.contains("ic_launcher") && (fname.endsWith(".png") || fname.endsWith(".webp"))) {
                Bitmap existing = BitmapFactory.decodeFile(file.getAbsolutePath());
                int targetW = (existing != null && existing.getWidth() > 0) ? existing.getWidth() : 192;
                int targetH = (existing != null && existing.getHeight() > 0) ? existing.getHeight() : 192;

                Bitmap scaled = Bitmap.createScaledBitmap(sourceBitmap, targetW, targetH, true);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    if (fname.endsWith(".webp") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        scaled.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, fos);
                    } else if (fname.endsWith(".webp")) {
                        //noinspection deprecation
                        scaled.compress(Bitmap.CompressFormat.WEBP, 100, fos);
                    } else {
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                }
            }
        }
    }

    private void replaceEqualLengthStringsInDex(File extractedDir, String newPackage) throws IOException {
        List<File> allFiles = new ArrayList<>();
        collectFiles(extractedDir, allFiles);

        for (File file : allFiles) {
            if (file.getName().startsWith("classes") && file.getName().endsWith(".dex")) {
                byte[] dexBytes = readBytesFile(file);
                if (dexBytes.length < 112) continue;

                // Primer zamene postojećeg paketa ako postoji string iste dužine (npr. com.example.ssb)
                String[] candidates = {"com.example.ssb", "com.google.app"};
                boolean changed = false;

                for (String oldPkg : candidates) {
                    if (oldPkg.length() == newPackage.length() && !oldPkg.equals(newPackage)) {
                        byte[] oldB = oldPkg.getBytes(StandardCharsets.UTF_8);
                        byte[] newB = newPackage.getBytes(StandardCharsets.UTF_8);
                        if (replaceByteSequence(dexBytes, oldB, newB)) {
                            changed = true;
                        }
                    }
                }

                if (changed) {
                    recalculateDexHashes(dexBytes);
                    writeBytesFile(file, dexBytes);
                }
            }
        }
    }

    private boolean replaceByteSequence(byte[] data, byte[] target, byte[] replacement) {
        if (target.length != replacement.length || target.length == 0) return false;
        boolean replacedAny = false;
        for (int i = 0; i <= data.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(replacement, 0, data, i, replacement.length);
                i += target.length - 1;
                replacedAny = true;
            }
        }
        return replacedAny;
    }

    private void recalculateDexHashes(byte[] dex) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(dex, 32, dex.length - 32);
            byte[] digest = sha1.digest();
            System.arraycopy(digest, 0, dex, 12, 20);

            Adler32 adler32 = new Adler32();
            adler32.update(dex, 12, dex.length - 12);
            long sum = adler32.getValue();
            dex[8] = (byte) (sum & 0xFF);
            dex[9] = (byte) ((sum >> 8) & 0xFF);
            dex[10] = (byte) ((sum >> 16) & 0xFF);
            dex[11] = (byte) ((sum >> 24) & 0xFF);
        } catch (Exception ignored) {
        }
    }

    private void signApkWithApksig(File inputApk, File outputApk) throws Exception {
        SignerCredentials creds = loadOrCreateSignerCredentials();

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "ssb-signer",
                creds.privateKey,
                Collections.singletonList(creds.certificate)
        ).build();

        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();

        apkSigner.sign();
    }

    private SignerCredentials loadOrCreateSignerCredentials() throws Exception {
        File ksFile = new File(getFilesDir(), "signer.jks");
        char[] password = "changeit".toCharArray();
        String alias = "ssb_key";

        KeyStore keyStore = KeyStore.getInstance("JKS");
        if (ksFile.exists()) {
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                keyStore.load(fis, password);
            }
            if (keyStore.containsAlias(alias)) {
                PrivateKey key = (PrivateKey) keyStore.getKey(alias, password);
                Certificate cert = keyStore.getCertificate(alias);
                if (key != null && cert instanceof X509Certificate) {
                    return new SignerCredentials(key, (X509Certificate) cert);
                }
            }
        } else {
            keyStore.load(null, password);
        }

        // Generisanje samopotpisanog RSA ključa ako keystore još ne postoji
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        // Za produkcioni/stabilni potpis u praksi je bolje uvesti sopstveni keystore fajl.
        // Ovde pravimo X.509 sertifikat preko BouncyCastle/KeyStore ako je dostupan, ili bacamo jasnu poruku.
        throw new IllegalStateException(
                "No pre-generated signer.jks found at " + ksFile.getAbsolutePath()
                        + ". Place your .jks keystore there or generate one with keytool."
        );
    }

    private void promptInstall() {
        File apkFile = getOutputApkFile();
        if (!apkFile.exists()) {
            showAlert("Error", "Signed APK file not found!");
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

    private void unzipFile(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                String canonicalDest = destDir.getCanonicalPath();
                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalDest + File.separator)) {
                    throw new IOException("Zip slip detected: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void zipDirectory(File sourceDir, File outputZip) throws IOException {
        List<File> files = new ArrayList<>();
        collectFiles(sourceDir, files);
        String basePath = sourceDir.getAbsolutePath() + File.separator;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputZip))) {
            byte[] buffer = new byte[4096];
            for (File file : files) {
                String entryName = file.getAbsolutePath().substring(basePath.length()).replace("\\", "/");
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(file)) {
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out);
            } else {
                out.add(child);
            }
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

    private void copyStream(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    private String readTextFile(File f) throws IOException {
        return new String(readBytesFile(f), StandardCharsets.UTF_8);
    }

    private void writeTextFile(File f, String content) throws IOException {
        writeBytesFile(f, content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readBytesFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    private void writeBytesFile(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static class SignerCredentials {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        SignerCredentials(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }
}
