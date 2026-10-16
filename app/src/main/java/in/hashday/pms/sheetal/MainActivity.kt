package `in`.hashday.pms.sheetal

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val TARGET_URL = "https://192.168.6.1/sheetal/sheetal/"
    }

    private lateinit var myWeb: WebView
    private lateinit var refreshProgressBar: ProgressBar

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
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

    // Activity Result Launcher for File Chooser
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results = if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                Array(count) { i -> data.clipData!!.getItemAt(i).uri }
            } else if (data?.data != null) {
                arrayOf(data.data!!)
            } else null
        } else null

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
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

        setContentView(R.layout.activity_main)

        myWeb = findViewById(R.id.myWeb)
        refreshProgressBar = findViewById(R.id.refreshProgressBar)

        // Initialize persistent device ID
        deviceId = DeviceIdManager.getDeviceId(this)
        Log.d(TAG, "Persistent Device ID: $deviceId")

        // Request runtime permissions
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

        // Load primary URL
        if (myWeb.url.isNullOrEmpty()) {
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
        val webSettings = myWeb.settings

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        request.grant(request.resources)
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

                val intent = fileChooserParams.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Cannot open file chooser", Toast.LENGTH_SHORT).show()
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

    private fun startKioskMode() {
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

    @Deprecated("Deprecated in Java")
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

    override fun onDestroy() {
        stopScreenCaptureSession()
        super.onDestroy()
    }
}
