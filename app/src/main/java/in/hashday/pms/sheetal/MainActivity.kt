package `in`.hashday.pms.sheetal

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TARGET_URL = "https://192.168.6.1/sheetal/sheetal/"
    }

    private lateinit var myWeb: WebView
    private lateinit var refreshProgressBar: ProgressBar

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private var webRtcManager: WebRTCManager? = null
    private var webRtcBridge: WebRTCBridge? = null
    private var deviceId: String = ""

    private var hasInitialized = false

    // Activity Result Launcher for MediaProjection screen capture consent
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection consent granted by user")
            ScreenCaptureService.startService(this)
            webRtcManager?.startScreenCapture(result.data!!)
            webRtcBridge?.sendStateToJs("CAPTURING")
        } else {
            Log.w(TAG, "MediaProjection consent denied or cancelled by user")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            webRtcBridge?.sendStateToJs("DENIED")
        }
    }

    // Activity Result Launcher for File Chooser / Camera capture
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.data != null) {
                arrayOf(data.data!!)
            } else if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            } else if (cameraPhotoUri != null) {
                arrayOf(cameraPhotoUri!!)
            } else null
        } else null

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraPhotoUri = null
    }

    // Activity Result Launcher for standard runtime permissions
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, isGranted) ->
            Log.d(TAG, "Permission $permission granted: $isGranted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasInitialized) {
            Log.d(TAG, "MainActivity already initialized, skipping duplicate onCreate")
            return
        }

        // Enable Chrome DevTools inspection (chrome://inspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Hardware acceleration at the window level for smooth camera video rendering
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        myWeb = findViewById(R.id.myWeb)
        refreshProgressBar = findViewById(R.id.refreshProgressBar)
        val mainContainer = findViewById<FrameLayout>(R.id.mainContainer)

        // Set solid background colors to prevent transparent flash/glitches during resize/rotation
        mainContainer.setBackgroundColor(Color.WHITE)
        myWeb.setBackgroundColor(Color.WHITE)
        myWeb.overScrollMode = View.OVER_SCROLL_NEVER
        myWeb.isVerticalScrollBarEnabled = false
        myWeb.isHorizontalScrollBarEnabled = false

        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            refreshProgressBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = systemBars.top
            }
            // Padding so top buttons/video controls are never obscured
            myWeb.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        hideSystemBars()

        // Initialize persistent device ID
        deviceId = DeviceIdManager.getDeviceId(this)
        Log.d(TAG, "Persistent Device ID: $deviceId")

        // Request runtime permissions (Camera, Microphone, Notifications)
        requestRequiredPermissions()

        // Configure WebView and JS Bridge
        configureWebView()

        // Attach two-finger refresh gesture listener
        setupTwoFingerRefresh()

        // Check for updates via UpdateManager
        lifecycleScope.launch(Dispatchers.IO) {
            UpdateManager.checkForUpdates(this@MainActivity)
        }

        // Enable Kiosk mode
        startKioskMode()

        // Load primary URL or restore saved instance state
        if (savedInstanceState != null) {
            Log.d(TAG, "Restoring WebView state from savedInstanceState")
            myWeb.restoreState(savedInstanceState)
        } else if (myWeb.url.isNullOrEmpty()) {
            myWeb.loadUrl(TARGET_URL)
        }

        hasInitialized = true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent received. Single instance active, preserving current WebView state.")
        startKioskMode()
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        myWeb.clipToPadding = false
        myWeb.setBackgroundColor(Color.WHITE)

        // LAYER_TYPE_NONE lets Chromium handle GPU surface composition dynamically
        // without forcing extra view buffers that cause MediaTek GPU tile glitches.
        myWeb.setLayerType(View.LAYER_TYPE_NONE, null)

        val webSettings = myWeb.settings

        @Suppress("DEPRECATION")
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT

        @Suppress("DEPRECATION")
        webSettings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        webSettings.allowUniversalAccessFromFileURLs = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(myWeb, true)
        }

        // Initialize WebRTC Manager and JS Bridge
        initializeWebRtc()

        myWeb.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Log.w(TAG, "SSL Warning ignored for local development: ${error.url}")
                handler.proceed()
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                refreshProgressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                refreshProgressBar.visibility = View.GONE
                injectDeviceIdIfNeeded(url)
                injectCameraGpuOptimizationCss(view)
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                refreshProgressBar.visibility = View.GONE
                if (errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT) {
                    Toast.makeText(applicationContext, "Network error: $description", Toast.LENGTH_SHORT).show()
                }
            }
        }

        myWeb.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    refreshProgressBar.visibility = View.VISIBLE
                } else {
                    refreshProgressBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WebViewConsole", "[${it.messageLevel()}] ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d(TAG, "WebChromeClient.onPermissionRequest triggered for resources: ${request.resources?.joinToString()}")
                runOnUiThread {
                    try {
                        request.grant(request.resources)
                        Log.d(TAG, "Successfully granted WebView permission request.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error granting WebView permission request", e)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                var captureIntent: Intent? = null
                if (fileChooserParams.isCaptureEnabled || fileChooserParams.acceptTypes.any { it.contains("image") }) {
                    val photoFile = try {
                        val imageDir = File(cacheDir, "camera_photos")
                        if (!imageDir.exists()) imageDir.mkdirs()
                        File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", imageDir)
                    } catch (e: Exception) { null }

                    if (photoFile != null) {
                        cameraPhotoUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }

                val contentSelectionIntent = fileChooserParams.createIntent()
                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    if (captureIntent != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open camera or file chooser", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    private fun injectCameraGpuOptimizationCss(webView: WebView) {
        val cssJs = """
            (function() {
                var style = document.getElementById('gpu_camera_fix');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'gpu_camera_fix';
                    style.type = 'text/css';
                    style.innerHTML = 'video, canvas { transform: translateZ(0) !important; -webkit-transform: translateZ(0) !important; backface-visibility: hidden !important; -webkit-backface-visibility: hidden !important; will-change: transform !important; }';
                    (document.head || document.documentElement).appendChild(style);
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(cssJs, null)
    }

    private fun initializeWebRtc() {
        val listener = object : WebRTCManager.WebRTCListener {
            override fun onLocalDescription(sdp: SessionDescription) {
                Log.d(TAG, "Local SDP Answer generated, passing to JS bridge")
                webRtcBridge?.sendAnswerToJs(sdp)
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE Candidate generated, passing to JS bridge")
                webRtcBridge?.sendIceCandidateToJs(candidate)
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE Connection State: ${newState.name}")
                webRtcBridge?.sendStateToJs(newState.name)
            }

            override fun onError(message: String) {
                Log.e(TAG, "WebRTC Error: $message")
                webRtcBridge?.sendStateToJs("ERROR: $message")
            }
        }

        webRtcManager = WebRTCManager(this, listener)

        webRtcBridge = WebRTCBridge(
            webView = myWeb,
            onRequestScreenShare = { requestScreenCaptureConsent() },
            onStopScreenShare = { stopScreenCaptureSession() },
            onSendOffer = { offerSdp -> webRtcManager?.handleRemoteOffer(offerSdp) },
            onSendIceCandidate = { mid, index, candidate ->
                webRtcManager?.addRemoteIceCandidate(mid, index, candidate)
            }
        )

        myWeb.addJavascriptInterface(webRtcBridge!!, WebRTCBridge.JS_INTERFACE_NAME)
        myWeb.addJavascriptInterface(webRtcBridge!!, "AndroidBridge") // Backward compatibility
    }

    private fun requestScreenCaptureConsent() {
        Log.d(TAG, "Requesting MediaProjection screen capture consent")
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val consentIntent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(consentIntent)
    }

    private fun stopScreenCaptureSession() {
        Log.d(TAG, "Stopping screen capture session")
        webRtcManager?.stop()
        ScreenCaptureService.stopService(this)
        webRtcBridge?.sendStateToJs("STOPPED")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTwoFingerRefresh() {
        val gestureDetector = TwoFingerRefreshGesture(this, myWeb) {
            refreshProgressBar.visibility = View.VISIBLE
            Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show()
            myWeb.reload()
        }
        myWeb.setOnTouchListener(gestureDetector)
    }

    private fun injectDeviceIdIfNeeded(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host ?: ""
        val path = uri.path ?: ""

        val isTargetHost = host == "192.168.6.1" || url.contains("192.168.6.1")
        val isTargetTerminalPath = path.contains("/sheetal/sheetal/terminal") ||
                path.endsWith("/terminal") ||
                path.endsWith("/terminal.php")

        if (isTargetHost && isTargetTerminalPath) {
            Log.d(TAG, "Valid terminal URL detected ($url). Injecting persistent device ID: $deviceId")

            val injectionJs = """
                (function() {
                    var devId = "${deviceId}";
                    function setOrAddInput(form) {
                        if (!form) return;
                        var input = form.querySelector("input#unique_device_id") || form.querySelector("input[name='unique_device_id']");
                        if (!input) {
                            input = document.createElement("input");
                            input.type = "hidden";
                            input.name = "unique_device_id";
                            input.id = "unique_device_id";
                            form.appendChild(input);
                        }
                        input.value = devId;
                    }
                    function injectAll() {
                        var directInput = document.getElementById("unique_device_id") || document.querySelector("input[name='unique_device_id']");
                        if (directInput) { directInput.value = devId; }
                        var forms = document.forms;
                        for (var i = 0; i < forms.length; i++) {
                            setOrAddInput(forms[i]);
                        }
                    }
                    injectAll();
                    document.addEventListener("submit", function(e) { injectAll(); }, true);
                    if (window.MutationObserver) {
                        new MutationObserver(function() { injectAll(); }).observe(document.body || document.documentElement, { childList: true, subtree: true });
                    }
                })();
            """.trimIndent()

            myWeb.evaluateJavascript(injectionJs, null)
        }
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun startKioskMode() {
        hideSystemBars()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                try {
                    startLockTask()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start lock task mode: ${e.message}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (myWeb.canGoBack()) {
            myWeb.goBack()
        } else {
            // Prevent exiting kiosk mode on back button
            Log.d(TAG, "Back button pressed in root page - suppressed for kiosk mode")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.d(TAG, "Saving WebView state in onSaveInstanceState")
        myWeb.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Log.d(TAG, "Restoring WebView state in onRestoreInstanceState")
        myWeb.restoreState(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged triggered: orientation=${newConfig.orientation}")
        hideSystemBars()
        myWeb.invalidate()
    }

    override fun onDestroy() {
        stopScreenCaptureSession()
        super.onDestroy()
    }
}
