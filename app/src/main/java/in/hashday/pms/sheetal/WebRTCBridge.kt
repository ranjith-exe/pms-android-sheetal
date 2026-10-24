package `in`.hashday.pms.sheetal

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class WebRTCBridge(
    private val webView: WebView,
    private val onRequestScreenShare: () -> Unit,
    private val onStopScreenShare: () -> Unit,
    private val onSendOffer: (String) -> Unit,
    private val onSendIceCandidate: (String, Int, String) -> Unit,
    private val onForceRestart: () -> Unit
) {

    companion object {
        private const val TAG = "WebRTCBridge"
        const val JS_INTERFACE_NAME = "AndroidScreenShareBridge"
    }

    @JavascriptInterface
    fun requestScreenShare() {
        Log.d(TAG, "JS called requestScreenShare()")
        webView.post {
            onRequestScreenShare()
        }
    }

    @JavascriptInterface
    fun forceAppRestart() {
        Log.d(TAG, "JS requested application force restart")
        webView.post {
            onForceRestart()
        }
    }

    @JavascriptInterface
    fun startScreenShare() {
        Log.d(TAG, "JS called startScreenShare()")
        webView.post {
            onRequestScreenShare()
        }
    }

    @JavascriptInterface
    fun stopScreenShare() {
        Log.d(TAG, "JS called stopScreenShare()")
        webView.post {
            onStopScreenShare()
        }
    }

    @JavascriptInterface
    fun sendOffer(sdpJson: String) {
        Log.d(TAG, "JS sent Offer: $sdpJson")
        try {
            val json = JSONObject(sdpJson)
            val sdp = json.optString("sdp", sdpJson)
            webView.post {
                onSendOffer(sdp)
            }
        } catch (e: Exception) {
            webView.post {
                onSendOffer(sdpJson)
            }
        }
    }

    @JavascriptInterface
    fun sendIceCandidate(candidateJson: String) {
        Log.d(TAG, "JS sent ICE Candidate: $candidateJson")
        try {
            val json = JSONObject(candidateJson)
            val sdpMid = json.optString("sdpMid", "0")
            val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
            val sdpCandidate = json.optString("candidate", "")
            webView.post {
                onSendIceCandidate(sdpMid, sdpMLineIndex, sdpCandidate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ICE candidate JSON", e)
        }
    }

    fun sendAnswerToJs(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp.description)
        }
        val base64Json = android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val jsCall = "var data = JSON.parse(atob('$base64Json')); if (window.onAndroidWebRTCAnswer) { window.onAndroidWebRTCAnswer(data); } else { window.dispatchEvent(new CustomEvent('android_webrtc_answer', { detail: data })); }"
        webView.post {
            webView.evaluateJavascript(jsCall, null)
        }
    }

    fun sendIceCandidateToJs(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "candidate")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        val base64Json = android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val jsCall = "var data = JSON.parse(atob('$base64Json')); if (window.onAndroidWebRTCIceCandidate) { window.onAndroidWebRTCIceCandidate(data); } else { window.dispatchEvent(new CustomEvent('android_webrtc_candidate', { detail: data })); }"
        webView.post {
            webView.evaluateJavascript(jsCall, null)
        }
    }

    fun sendStateToJs(state: String) {
        val json = JSONObject().apply {
            put("type", "state")
            put("state", state)
        }
        val base64Json = android.util.Base64.encodeToString(json.toString().toByteArray(), android.util.Base64.NO_WRAP)
        val jsCall = "var data = JSON.parse(atob('$base64Json')); if (window.onAndroidWebRTCState) { window.onAndroidWebRTCState(data); } else { window.dispatchEvent(new CustomEvent('android_webrtc_state', { detail: data })); }"
        webView.post {
            webView.evaluateJavascript(jsCall, null)
        }
    }
}
