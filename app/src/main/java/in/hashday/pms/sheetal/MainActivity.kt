package `in`.hashday.pms.sheetal

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.MotionEvent
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
    private var isScreenCaptureIntentLaunched = false
    private var twoFingerGesture: TwoFingerRefreshGesture? = null

    // Activity Result Launcher for MediaProjection screen capture consent
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isScreenCaptureIntentLaunched = false

        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection consent granted by user")
            ScreenCaptureService.startService(this)
            
            // Wait briefly to allow foreground service to register before starting projection
            myWeb.postDelayed({
                webRtcManager?.startScreenCapture(result.data!!)
                webRtcBridge?.sendStateToJs("CAPTURING")
            }, 150)
        } else {
            Log.w(TAG, "MediaProjection consent denied or cancelled by system/user. Debouncing...")
            
            // DEBOUNCE FIX: Android's LockTask transition fires a phantom RESULT_CANCELED just milliseconds 
            // before the real RESULT_OK arrives. We wait 500ms before declaring it actually denied.
            myWeb.postDelayed({
                if (webRtcManager?.isScreenCapturing() != true) {
                    Log.e(TAG, "Screen capture was definitively denied.")
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                    webRtcBridge?.sendStateToJs("DENIED")
                } else {
                    Log.d(TAG, "Ghost cancellation ignored. Screen capture is active.")
                }
            }, 500)
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

        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            refreshProgressBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = systemBars.top
            }
            // Apply padding to the wrapper container instead of myWeb directly
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        try {
            ev?.let { twoFingerGesture?.observe(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in twoFingerGesture observe: ${e.message}")
        }
        return super.dispatchTouchEvent(ev)
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
        myWeb.clipToPadding = true
        myWeb.setBackgroundColor(Color.WHITE)

        val webSettings = myWeb.settings

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

            // Detects the "White Screen of Death" (WebView Out-of-Memory / Renderer Crash)
            // and automatically self-heals by restarting the Activity.
            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                Log.e(TAG, "WebView Renderer crashed! (OOM or WebGL issue). Restarting Activity safely.")
                val restartIntent = Intent(this@MainActivity, MainActivity::class.java)
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(restartIntent)
                finish()
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                refreshProgressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                refreshProgressBar.visibility = View.GONE
                injectDeviceIdIfNeeded(url)
                // Force flush cookies to disk immediately after page finishes loading
                CookieManager.getInstance().flush()
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
            },
            onForceRestart = {
                Log.w(TAG, "Force restart triggered by WebView JS. Restarting activity safely.")
                val restartIntent = Intent(this@MainActivity, MainActivity::class.java)
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(restartIntent)
                finish()
            }
        )

        myWeb.addJavascriptInterface(webRtcBridge!!, WebRTCBridge.JS_INTERFACE_NAME)
        myWeb.addJavascriptInterface(webRtcBridge!!, "AndroidBridge") // Backward compatibility
    }

    private fun requestScreenCaptureConsent() {
        if (webRtcManager?.isScreenCapturing() == true) {
            Log.d(TAG, "Screen capture already active! Reusing session and notifying JS CAPTURING")
            webRtcBridge?.sendStateToJs("CAPTURING")
            return
        }
        if (isScreenCaptureIntentLaunched) {
            Log.d(TAG, "Screen capture intent is already active, ignoring duplicate request.")
            return
        }
        
        Log.d(TAG, "Requesting MediaProjection screen capture consent")
        isScreenCaptureIntentLaunched = true
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        val consentIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            projectionManager.createScreenCaptureIntent(config)
        } else {
            projectionManager.createScreenCaptureIntent()
        }

        screenCaptureLauncher.launch(consentIntent)
    }

    private fun stopScreenCaptureSession() {
        Log.d(TAG, "Stopping screen capture session")
        webRtcManager?.stop()
        ScreenCaptureService.stopService(this)
        webRtcBridge?.sendStateToJs("STOPPED")
    }

    private fun setupTwoFingerRefresh() {
        twoFingerGesture = TwoFingerRefreshGesture(this, myWeb) {
            refreshProgressBar.visibility = View.VISIBLE
            Toast.makeText(this, "Refreshing...", Toast.LENGTH_SHORT).show()
            myWeb.reload()
        }
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
    }

    override fun onResume() {
        super.onResume()
        try {
            myWeb.onResume()
            myWeb.resumeTimers()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume for WebView: ${e.message}")
        }
        hideSystemBars()
    }

    override fun onPause() {
        try {
            myWeb.onPause()
            myWeb.pauseTimers()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause for WebView: ${e.message}")
        }
        // Force flush cookies to disk immediately when the app goes into the background
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        // Guarantee cookies are written to disk if the activity is completely stopped
        CookieManager.getInstance().flush()
        super.onStop()
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
        webRtcManager?.destroy()
        
        // Force flush cookies one final time during lifecycle destruction
        CookieManager.getInstance().flush()

        // Safely destroy WebView to prevent native memory leaks during standard lifecycle events.
        // NOTE: We DO NOT clear cookies/storage here. The web app strictly relies on persistent
        // cookies (TAB_USERID, TERMINAL_ID, etc.) to maintain session state across reboots.
        myWeb.let {
            it.clearHistory()
            // clearCache(false) clears RAM cache but leaves disk cache and cookies intact.
            it.clearCache(false)
            it.loadUrl("about:blank")
            it.onPause()
            it.removeAllViews()
            it.destroy()
        }
        super.onDestroy()
    }
}
