package `in`.hashday.pms.sheetal

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import java.util.Collections
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {

    interface WebRTCListener {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceConnectionChange(newState: PeerConnection.IceConnectionState)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "WebRTCManager"
    }

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val pendingIceCandidates = Collections.synchronizedList(mutableListOf<IceCandidate>())

    @Volatile
    private var isCapturing = false

    init {
        ensureFactory()
    }

    @Synchronized
    private fun ensureFactory(): Boolean {
        try {
            if (eglBase == null) {
                eglBase = EglBase.create()
            }
            if (peerConnectionFactory == null) {
                val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initializationOptions)

                val options = PeerConnectionFactory.Options()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                    .createPeerConnectionFactory()
                Log.d(TAG, "PeerConnectionFactory initialized successfully")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC PeerConnectionFactory", e)
            listener.onError("WebRTC Initialization Error: ${e.message}")
            return false
        }
    }

    fun startScreenCapture(permissionIntent: Intent) {
        if (isCapturing && videoTrack != null) {
            Log.w(TAG, "Screen capture is already active with valid videoTrack")
            return
        }

        if (!ensureFactory()) {
            Log.e(TAG, "PeerConnectionFactory could not be initialized")
            listener.onError("PeerConnectionFactory not initialized")
            return
        }

        val factory: PeerConnectionFactory = peerConnectionFactory ?: run {
            Log.e(TAG, "PeerConnectionFactory is null")
            listener.onError("PeerConnectionFactory not initialized")
            return
        }

        try {
            // Clean up any stale capturer/source before starting
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoSource?.dispose()
                surfaceTextureHelper?.dispose()
            } catch (ignored: Exception) {}

            val projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection onStop called by system")
                    stop()
                }
            }

            val capturer = ScreenCapturerAndroid(permissionIntent, projectionCallback)
            videoCapturer = capturer
            val source: VideoSource = factory.createVideoSource(true)
            videoSource = source

            val sth = SurfaceTextureHelper.create("ScreenShareThread", eglBase?.eglBaseContext)
            surfaceTextureHelper = sth

            capturer.initialize(
                sth,
                context.applicationContext,
                source.capturerObserver
            )

            val displayMetrics = context.resources.displayMetrics
            val width = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1280
            val height = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 720

            capturer.startCapture(width, height, 30)

            val track = factory.createVideoTrack("ARDAMSv0", source)
            track.setEnabled(true)
            videoTrack = track

            isCapturing = true
            createPeerConnection()
            Log.d(TAG, "Screen capture started at ${width}x${height} @ 30fps")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture", e)
            listener.onError("Screen capture failed: ${e.message}")
            stop()
        }
    }

    private fun createPeerConnection() {
        if (!ensureFactory()) return
        val factory: PeerConnectionFactory = peerConnectionFactory ?: return

        // Clean up previous peer connection if any
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing previous peerConnection: ${e.message}")
        }
        peerConnection = null

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "SignalingState: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "IceConnectionState: $state")
                listener.onIceConnectionChange(state)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "IceGatheringState: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE Candidate generated: ${candidate.sdpMid}")
                listener.onIceCandidate(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        val currentTrack = videoTrack
        if (currentTrack != null) {
            peerConnection?.addTrack(currentTrack, listOf("ARDAMS"))
            Log.d(TAG, "Attached videoTrack to PeerConnection")
        } else {
            Log.w(TAG, "videoTrack is null in createPeerConnection")
        }
    }

    fun handleRemoteOffer(sdpString: String) {
        Log.d(TAG, "handleRemoteOffer received SDP offer, creating fresh PeerConnection session")

        // Fresh session setup: clear pending ICE and create fresh PeerConnection
        pendingIceCandidates.clear()
        createPeerConnection()

        val pc = peerConnection ?: run {
            Log.e(TAG, "PeerConnection is null in handleRemoteOffer")
            listener.onError("PeerConnection creation failed")
            return
        }

        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdpString)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote OFFER set successfully. Flushing pending ICE candidates and creating ANSWER...")
                flushPendingIceCandidates()
                createAnswer()
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                listener.onError("Set Remote Offer Failure: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                listener.onError("Set Remote Offer Failure: $error")
            }
        }, sessionDescription)
    }

    private fun flushPendingIceCandidates() {
        synchronized(pendingIceCandidates) {
            val pc = peerConnection
            if (pc != null) {
                for (candidate in pendingIceCandidates) {
                    try {
                        pc.addIceCandidate(candidate)
                        Log.d(TAG, "Flushed queued remote ICE candidate: ${candidate.sdpMid}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error adding queued ICE candidate: ${e.message}")
                    }
                }
            }
            pendingIceCandidates.clear()
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(d: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local ANSWER set successfully. Notifying listener...")
                            listener.onLocalDescription(desc)
                        }

                        override fun onCreateFailure(e: String?) {
                            Log.e(TAG, "Failed to set local description: $e")
                        }

                        override fun onSetFailure(e: String?) {
                            Log.e(TAG, "Failed to set local description: $e")
                        }
                    }, desc)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create Answer failure: $error")
                listener.onError("Create Answer Failure: $error")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Set Answer failure: $error")
            }
        }, constraints)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdpCandidate: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdpCandidate)
        val pc = peerConnection
        if (pc != null && pc.remoteDescription != null) {
            pc.addIceCandidate(candidate)
            Log.d(TAG, "Added remote ICE candidate immediately: $sdpMid")
        } else {
            Log.d(TAG, "Queued remote ICE candidate (remoteDescription not set yet): $sdpMid")
            pendingIceCandidates.add(candidate)
        }
    }

    fun isScreenCapturing(): Boolean = isCapturing

    fun stop() {
        Log.d(TAG, "Stopping WebRTC session and disposing connection resources...")
        isCapturing = false
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping video capturer: ${e.message}")
        }

        try {
            videoCapturer?.dispose()
            videoSource?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.close()
            peerConnection?.dispose()
            // NOTE: Do NOT dispose peerConnectionFactory or eglBase here!
            // Keeping them alive allows subsequent screen share sessions to start immediately without recreating factories.
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebRTC cleanup", e)
        } finally {
            videoCapturer = null
            videoSource = null
            videoTrack = null
            surfaceTextureHelper = null
            peerConnection = null
            pendingIceCandidates.clear()
        }
    }

    fun destroy() {
        Log.d(TAG, "Destroying WebRTCManager completely...")
        stop()
        try {
            peerConnectionFactory?.dispose()
            eglBase?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing factory/eglBase: ${e.message}")
        } finally {
            peerConnectionFactory = null
            eglBase = null
        }
    }
}
