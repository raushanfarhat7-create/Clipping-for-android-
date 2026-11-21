package app.autoclip.mobile

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Thin native shell around the AutoClip web UI. All processing happens on the
 * AutoClip server; this app adds file picking, downloads and fullscreen video.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorText: TextView

    private lateinit var serverUrl: String
    private var pageFailed = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingDownload: DownloadRequest? = null

    private data class DownloadRequest(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val saved = Prefs.serverUrl(this)
        if (saved.isNullOrBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        serverUrl = saved

        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.error_view)
        errorText = findViewById(R.id.error_text)

        findViewById<Button>(R.id.retry).setOnClickListener { load() }
        findViewById<Button>(R.id.change_server).setOnClickListener { openSetup() }

        configureWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            load()
        }
    }

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                // Links that leave the AutoClip server open in the system browser.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageFailed = false
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                if (!pageFailed) errorView.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    pageFailed = true
                    errorText.text = "$serverUrl\n${error.description}"
                    errorView.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                root.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                webView.visibility = View.GONE
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                actionBar?.hide()
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(DownloadRequest(url, userAgent, contentDisposition, mimeType))
        }
    }

    private fun isInternal(uri: Uri): Boolean {
        val base = Uri.parse(serverUrl)
        return uri.host == base.host && uri.port == base.port
    }

    private fun load() {
        errorView.visibility = View.GONE
        webView.loadUrl(serverUrl)
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        actionBar?.show()
    }

    // ---- Downloads -------------------------------------------------------

    private fun startDownload(req: DownloadRequest) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = req
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        enqueue(req)
    }

    private fun enqueue(req: DownloadRequest) {
        val fileName = URLUtil.guessFileName(req.url, req.contentDisposition, req.mimeType)
        val request = DownloadManager.Request(Uri.parse(req.url))
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        req.mimeType?.let { request.setMimeType(it) }
        req.userAgent?.let { request.addRequestHeader("User-Agent", it) }

        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(this, getString(R.string.downloading, fileName), Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val pending = pendingDownload
            pendingDownload = null
            if (pending != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                enqueue(pending)
            }
        }
    }

    // ---- File upload result ---------------------------------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ---- Menu / navigation / lifecycle ----------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> { load(); true }
        R.id.action_change_server -> { openSetup(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            ::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private companion object {
        const val REQ_FILE = 1001
        const val REQ_STORAGE = 1002
    }
}
