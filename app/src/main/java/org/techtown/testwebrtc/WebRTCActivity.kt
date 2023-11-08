package org.techtown.testwebrtc

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import org.json.JSONObject
import org.techtown.testwebrtc.databinding.ActivityWebrtcBinding
import org.webrtc.*
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WebRTCActivity : AppCompatActivity(), SdpObserver, PeerConnection.Observer {
    private val binding by lazy { ActivityWebrtcBinding.inflate(layoutInflater) }

    private var isInitiator = false
    private var partnerIp: String? = null

    private val disposables = CompositeDisposable()
    private var localCapture: VideoCapturer? = null
    private var peerConnection: PeerConnection? = null

    private var audioTrack: AudioTrack? = null
    private var isAudioMuted = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.localView.setZOrderMediaOverlay(true)

        partnerIp = intent.getStringExtra("partnerIp")
        isInitiator = intent.getBooleanExtra("isInitiator", false)

        startSignalling()

        if (isInitiator) {
            startWebRTC()
            peerConnection?.createOffer(this, MediaConstraints())
        }

        initButton()
    }

    private fun initButton() = with(binding) {
        settingButton.setOnClickListener {
            settingLayout.visibility = if (settingLayout.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        videoOnButton.setOnClickListener {
        }
        audioOnButton.setOnClickListener {
            toggleAudioMute()
        }
        callEndButton.setOnClickListener {
            backToActivity("callEndButton")
        }
    }

    override fun onDestroy() {
        disposables.dispose()
        binding.localView.release()
        binding.remoteView.release()
        audioTrack?.dispose()
        localCapture?.dispose()
        peerConnection?.dispose()
        super.onDestroy()
    }

    private fun startWebRTC() {
        val camera1Enumerator = Camera1Enumerator(false)
        val cameraNames = camera1Enumerator.deviceNames
        var cameraName = cameraNames.singleOrNull { camera1Enumerator.isFrontFacing(it) }
        if (cameraName == null) {
            cameraName = cameraNames.lastOrNull()

            if (cameraName == null) {
                onError(Throwable(getString(R.string.the_device_has_no_camera)))
                return
            }
        }

        localCapture = camera1Enumerator.createCapturer(cameraName, null)

        val eglBase = EglBase.create()
        binding.localView.init(eglBase.eglBaseContext, null)
        binding.remoteView.init(eglBase.eglBaseContext, null)

        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(this)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(initializationOptions)

        val peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        val iceServer1 = PeerConnection.IceServer.builder("stun:91.220.207.146:3478").createIceServer()
        val iceServer2 = PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        val iceServer3 = PeerConnection.IceServer.builder("stun:stun.schlund.de").createIceServer()
        val iceServer4 = PeerConnection.IceServer.builder("stun:74.125.140.127:19302").createIceServer()
        val iceServer5 = PeerConnection.IceServer.builder("stun:[2A00:1450:400C:C08::7F]:19302").createIceServer()
        val iceServers = listOf(iceServer1, iceServer2, iceServer3, iceServer4, iceServer5)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, this)

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(localCapture!!.isScreencast)
        localCapture!!.initialize(helper, this, videoSource.capturerObserver)
        localCapture!!.startCapture(1280, 720, 30)
        val videoTrack = peerConnectionFactory.createVideoTrack("video", videoSource)
        videoTrack.addSink(binding.localView)

        val mediaStreamLabels = listOf("ARDAMS")
        peerConnection!!.addTrack(videoTrack, mediaStreamLabels)

        var remoteVideoTrack: VideoTrack? = null
        for (transceiver in peerConnection!!.transceivers) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                remoteVideoTrack = track
                break
            }
        }

        if (remoteVideoTrack == null) {
            onError(Throwable(getString(R.string.strange)))
            return
        }

        remoteVideoTrack.addSink(binding.remoteView)

        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("audio", audioSource)
        peerConnection!!.addTrack(audioTrack, mediaStreamLabels)
        audioTrack?.setEnabled(false)
    }

    private fun setRemoteDescription(description: String) {
        peerConnection?.let {
            val type = if (isInitiator) SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER
            val sessionDescription = SessionDescription(type, description)
            it.setRemoteDescription(this, sessionDescription)
            it.createAnswer(this, MediaConstraints())
        }
    }

    private fun onError(error: Throwable) {
        error.printStackTrace()
        Toast.makeText(this, error.message.toString(), Toast.LENGTH_SHORT).show()
        backToActivity(error.message.toString())
    }

    override fun onCreateSuccess(sessionDescription: SessionDescription) {
        peerConnection?.setLocalDescription(this, sessionDescription)

        val packet = JSONObject()
        packet.put("action", "setSessionDescription")
        packet.put("value", sessionDescription.description)

        sendPacket(packet)
    }

    override fun onSetSuccess() {}

    override fun onCreateFailure(s: String) {}

    override fun onSetFailure(s: String) {}

    override fun onIceCandidate(iceCandidate: IceCandidate) {
        val iceCandidateJson = JSONObject()
        iceCandidateJson.put("sdp", iceCandidate.sdp)
        iceCandidateJson.put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        iceCandidateJson.put("sdpMid", iceCandidate.sdpMid)

        val packet = JSONObject()
        packet.put("action", "addIceCandidate")
        packet.put("value", iceCandidateJson)

        sendPacket(packet)
    }

    override fun onDataChannel(dataChannel: DataChannel) {}

    override fun onIceConnectionReceivingChange(receivingChange: Boolean) {}

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            backToActivity("PeerConnection.IceConnectionState.DISCONNECTED")
        }
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}

    override fun onAddStream(mediaStream: MediaStream) {}

    override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}

    override fun onIceCandidatesRemoved(list: Array<out IceCandidate>) {}

    override fun onRemoveStream(mediaStream: MediaStream) {}

    override fun onRenegotiationNeeded() {}

    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>) {}

    private fun startSignalling() {
        val observable = Observable.create<String> { emitter ->
            ServerSocket().use { serverSocket ->
                serverSocket.soTimeout = 3 * 1000
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(50102))

                while (!emitter.isDisposed) {
                    try {
                        val socket = serverSocket.accept()
                        socket.use {
                            it.getInputStream().use { input ->
                                val text = input.bufferedReader().readText()
                                emitter.onNext(text)
                            }
                        }
                    } catch (e: Exception) {
                        println("startSignalling... ${e.message}")
                    }
                }
            }
        }

        val disposable = observable
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(this::onJsonReceived, this::onError)

        disposables.add(disposable)
    }

    private fun onJsonReceived(json: String) {
        val packet = JSONObject(json)

        when (packet.getString("action")) {
            "setSessionDescription" -> {
                if (!isInitiator) {
                    startWebRTC()
                }
                val value = packet.getString("value")
                setRemoteDescription(value)
            }

            "addIceCandidate" -> {
                val iceCandidateJson = packet.getJSONObject("value")
                val sdp = iceCandidateJson.getString("sdp")
                val sdpMLineIndex = iceCandidateJson.getInt("sdpMLineIndex")
                val sdpMid = iceCandidateJson.getString("sdpMid")

                val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                peerConnection?.addIceCandidate(iceCandidate)
            }
        }
    }

    private fun sendPacket(packet: JSONObject) {
        var action = Completable.fromAction {
            Socket().use {
                it.soTimeout = 10 * 1000
                it.connect(InetSocketAddress(partnerIp, 50102), 10 * 1000)
                it.getOutputStream().write(packet.toString().toByteArray())
            }
        }

        action = action.retry { error -> error is ConnectException }
        action = action.subscribeOn(Schedulers.newThread())
        action = action.observeOn(AndroidSchedulers.mainThread())

        val disposable = action.subscribe({}, this::onError)
        disposables.add(disposable)
    }

    private fun toggleAudioMute() {
        audioTrack?.setEnabled(isAudioMuted)
        if (isAudioMuted) {
            binding.audioOnButton.setImageResource(R.drawable.ic_audio_on)
        } else {
            binding.audioOnButton.setImageResource(R.drawable.ic_audio_off)
        }
        isAudioMuted = !isAudioMuted
    }

    private fun backToActivity(reason: String) {
        println("backToActivity called. reason: $reason")
        val intent = Intent(this, PartnerDetectorActivity::class.java)
        startActivity(intent)
        finish()
    }
}