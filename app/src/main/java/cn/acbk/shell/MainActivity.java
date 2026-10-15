package cn.acbk.shell;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 记账助手 WebView 壳。
 *
 * 这个 App 不重写前端、也不改后端：它只是把面板现有的网页装进一个「没有浏览器界面」的
 * WebView，并补上裸 WebView 默认没有的三根线 —— 打印 / 拍照选图 / 下载。
 *
 * 之所以需要它：Android 的 WebView 对 window.print() 是空实现，<input type="file"> 也
 * 必须由宿主实现 onShowFileChooser，否则点了没反应。各家「网页转应用」壳接不接这两根线
 * 全看作者，所以自己接最稳。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private String panelUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        panelUrl = getString(R.string.panel_url).trim();

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        // WebView 默认白底，深色模式下页面加载前会闪一下白 —— 让它跟着当前主题走
        webView.setBackgroundColor(isNightMode() ? 0xFF131417 : 0xFFF5F6F8);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);      // 面板用 localStorage 记 sessionId
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        if (isDebuggable()) WebView.setWebContentsDebuggingEnabled(true);

        webView.addJavascriptInterface(new Bridge(), "AcbkShell");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectPrintBridge();
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           SslError error) {
                // 不静默放行：面板应该是有效证书，出问题就让用户看到原因
                handler.cancel();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("证书校验失败")
                        .setMessage("无法安全连接面板（错误码 " + error.getPrimaryError() + "）。\n\n"
                                + "请确认 Secret ACBK_PANEL_URL 用的是有效 HTTPS 证书的域名。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    toast("无法打开选择器：" + e.getMessage());
                    return false;
                }
            }
        });

        // 下载（面板的 ⬇ 按钮）：交给系统下载器，并把 WebView 的登录 cookie 带上
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                String cookie = CookieManager.getInstance().getCookie(url);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                if (cookie != null) request.addRequestHeader("Cookie", cookie);
                request.setMimeType(mimeType);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                manager.enqueue(request);
                toast("已开始下载到「下载」目录");
            } catch (Exception e) {
                toast("下载失败：" + e.getMessage());
            }
        });

        // 长按图片 → 存到相册（打印不出来时的兜底：存图后用相册打印）
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = webView.getHitTestResult();
            int type = hit.getType();
            if (type == WebView.HitTestResult.IMAGE_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                String imageUrl = absolute(hit.getExtra());
                if (imageUrl != null) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("保存图片")
                            .setMessage("把这张图保存到相册？")
                            .setPositiveButton("保存", (d, w) -> saveImageToGallery(imageUrl))
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                }
            }
            return false;
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (panelUrl.startsWith("http")) {
            webView.loadUrl(panelUrl);
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("还没配置面板地址")
                    .setMessage("构建时没注入面板地址。请在 GitHub 仓库的 "
                            + "Settings → Secrets and variables → Actions 里配置 ACBK_PANEL_URL "
                            + "（形如 https://你的域名/），然后重新构建。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    // ---------- 导航 ----------

    /** 面板内部的链接交给 WebView；站外的交给系统浏览器。返回 true = 已被接管。 */
    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (sameSite(url)) return false;          // 面板内部：WebView 自己加载
            openExternally(url);                      // 站外：交给系统浏览器
            return true;
        }
        openExternally(url);                          // tel: / mailto: / intent: 等
        return true;
    }

    private void openExternally(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // 没有应用能处理这个 scheme，忽略即可
        }
    }

    private boolean sameSite(String url) {
        try {
            return new URL(url).getHost().equalsIgnoreCase(new URL(panelUrl).getHost());
        } catch (Exception e) {
            return true;               // 解析不了就当站内，避免误跳外部浏览器
        }
    }

    /** 把可能是相对路径的地址补成绝对地址。 */
    private String absolute(String url) {
        if (url == null) return null;
        if (url.startsWith("http")) return url;
        try {
            return new URL(new URL(panelUrl), url).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 打印 ----------

    /**
     * 覆盖页面里的 window.print。
     * 面板的「🖨 打印」调的就是 window.print()，而 WebView 对它是空实现，所以必须接住。
     * 优先拿查看器里的账单 PDF 地址（面板把它放在 #vDownload 上），打印 PDF 本身；
     * 拿不到就退回打印当前网页。
     */
    private void injectPrintBridge() {
        String js = "(function(){"
                + "if(window.__acbkShell){return;}"
                + "window.__acbkShell=1;"
                + "window.print=function(){"
                + "  var u='';"
                + "  try{var el=document.getElementById('vDownload');if(el&&el.href){u=el.href;}}catch(e){}"
                + "  try{AcbkShell.print(u);}catch(e){}"
                + "};"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    /** JS 桥：注入的 window.print 最终走到这里（回调在子线程，要切回 UI 线程）。 */
    private class Bridge {
        @JavascriptInterface
        public void print(final String pdfUrl) {
            runOnUiThread(() -> doPrint(pdfUrl));
        }
    }

    private void doPrint(String pdfUrl) {
        PrintManager manager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (manager == null) {
            toast("这台设备没有可用的打印服务");
            return;
        }
        String job = getString(R.string.app_name) + " - 结账单";

        // 只有确认是面板的 PDF 端点时才走「打印 PDF 本身」这条路，其余一律打印当前网页
        String target = (pdfUrl != null && pdfUrl.contains("/pdf/")) ? absolute(pdfUrl) : null;
        if (target == null) {
            printWebView(manager, job);
            return;
        }
        new Thread(() -> {
            final File pdf = download(target, "print", ".pdf");
            runOnUiThread(() -> {
                if (pdf != null) {
                    manager.print(job, new PdfPrintAdapter(pdf, job), new PrintAttributes.Builder().build());
                } else {
                    toast("账单 PDF 获取失败，改为打印当前页面");
                    printWebView(manager, job);
                }
            });
        }).start();
    }

    private void printWebView(PrintManager manager, String job) {
        try {
            manager.print(job, webView.createPrintDocumentAdapter(job),
                    new PrintAttributes.Builder().build());
        } catch (Exception e) {
            toast("无法调起打印：" + e.getMessage());
        }
    }

    /** 把 PDF 原样交给系统打印服务 —— 打出来和生成的结账单完全一致。 */
    private static class PdfPrintAdapter extends PrintDocumentAdapter {
        private final File file;
        private final String name;

        PdfPrintAdapter(File file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override
        public void onLayout(PrintAttributes oldAttrs, PrintAttributes newAttrs,
                             CancellationSignal signal, LayoutResultCallback callback, Bundle extras) {
            if (signal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder(name + ".pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                            CancellationSignal signal, WriteResultCallback callback) {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                out.flush();
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                callback.onWriteFailed(e.getMessage());
            }
        }

        @Override
        public void onFinish() {
            file.delete();              // 打印结束（含取消）后删掉临时 PDF，别留在缓存里
        }
    }

    // ---------- 下载 / 存图 ----------

    /** 带 WebView 登录 cookie 的下载（面板在鉴权后面，裸请求会拿到登录页）。 */
    private File download(String url, String prefix, String suffix) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) connection.setRequestProperty("Cookie", cookie);
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (connection.getResponseCode() != 200) return null;
            File out = new File(getCacheDir(), prefix + "-" + System.currentTimeMillis() + suffix);
            try (InputStream in = connection.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) os.write(buffer, 0, read);
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void saveImageToGallery(String url) {
        new Thread(() -> {
            File file = download(url, "img", ".png");
            if (file == null) {
                toast("保存失败：图片下载不下来");
                return;
            }
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,
                        "acbk-" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/记账助手");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                }
                Uri uri = getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("无法写入相册");
                try (OutputStream os = getContentResolver().openOutputStream(uri);
                     InputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) os.write(buffer, 0, read);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                }
                toast("已保存到相册");
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            } finally {
                file.delete();          // 只是中转，进了相册就不用留在缓存里
            }
        }).start();
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback == null) return;
        // 这里故意传 RESULT_OK，而不是真实的 resultCode。
        // parseResult 只在 resultCode==RESULT_OK 时才去提取数据，而部分机型的相册/文件管理器
        // 在用户确实选了图片的情况下仍然回 RESULT_CANCELED —— 那样 parseResult 直接返回 null，
        // 页面收到空结果，用户看到的就是"选了没反应"。data 为 null 时它照样返回 null，取消不受影响。
        filePathCallback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(RESULT_OK, data));
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * 当前是否处于系统深色模式。
     *
     * 这只是给 WebView 底色用的（避免加载瞬间闪白）。真正让面板切深浅色的是
     * values-night/styles.xml 里那套 isLightTheme=false 的主题 —— WebView 据此
     * 向网页报告 prefers-color-scheme，面板自带的配色随之生效。
     */
    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void toast(final String message) {
        runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }
}
