package dev.eggnstone.chime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoConfiguration
import com.amazonaws.services.chime.sdk.meetings.audiovideo.AudioVideoFacade
import com.amazonaws.services.chime.sdk.meetings.audiovideo.audio.AudioMode
import com.amazonaws.services.chime.sdk.meetings.audiovideo.audio.AudioStreamType
import com.amazonaws.services.chime.sdk.meetings.audiovideo.audio.activespeakerpolicy.DefaultActiveSpeakerPolicy
import com.amazonaws.services.chime.sdk.meetings.audiovideo.contentshare.DefaultContentShareController
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoRenderView
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.VideoResolution
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.CaptureSourceError
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.CaptureSourceObserver
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultScreenCaptureSource
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.capture.DefaultSurfaceTextureCaptureSourceFactory
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.gl.DefaultEglCoreFactory
import com.amazonaws.services.chime.sdk.meetings.session.*
import com.amazonaws.services.chime.sdk.meetings.utils.Versioning.Companion.sdkVersion
import com.amazonaws.services.chime.sdk.meetings.utils.logger.ConsoleLogger
import com.amazonaws.services.chime.sdk.meetings.utils.logger.LogLevel
import dev.eggnstone.chime.observers.*
import dev.eggnstone.chime.views.ChimeDefaultVideoRenderViewFactory
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.platform.PlatformViewRegistry
import org.amazon.chime.webrtc.MediaStream


class ChimePlugin : FlutterPlugin, MethodCallHandler, ActivityAware
{
    var activity: Activity? = null
    private var resultCallback: MethodChannel.Result? = null

    private var _applicationContext: Context? = null
    private var _methodChannel: MethodChannel? = null
    private var _audioVideoFacade: AudioVideoFacade? = null
    private var _eventSink: EventSink? = null

    private var SCREEN_CAPTURE_REQUEST_CODE = 99
    private val logger = ConsoleLogger(LogLevel.DEBUG)

    private var messenger: BinaryMessenger? = null
    private var registry: PlatformViewRegistry? = null

    private var defaultEglCoreFactory: DefaultEglCoreFactory = DefaultEglCoreFactory()

    override fun onAttachedToEngine(binding: FlutterPluginBinding)
    {
         messenger = binding.binaryMessenger
         registry = binding.platformViewRegistry
        _applicationContext = binding.applicationContext
        Log.d(TAG, "onAttachedToEngine()")
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding)
    {
        val safeMethodChannel: MethodChannel? = _methodChannel
        if (safeMethodChannel != null)
            safeMethodChannel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result)
    {
        if (call.method == "startActivityForResult") {
            val mediaProjectionManager = activity?.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            this.resultCallback = result
            activity?.startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
        }else {

            when (call.method) {
                "AudioVideoStart" -> handleAudioVideoStart(result)
                "AudioVideoStop" -> handleAudioVideoStop(result)
                "AudioVideoStartLocalVideo" -> handleAudioVideoStartLocalVideo(result)
                "AudioVideoStopLocalVideo" -> handleAudioVideoStopLocalVideo(result)
                "AudioVideoStartRemoteVideo" -> handleAudioVideoStartRemoteVideo(result)
                "AudioVideoStopRemoteVideo" -> handleAudioVideoStopRemoteVideo(result)
                "BindVideoView" -> handleBindVideoView(call, result)
                "ChooseAudioDevice" -> handleChooseAudioDevice(call, result)
                "ClearViewIds" -> handleClearViewIds(call, result)
                "CreateMeetingSession" -> handleCreateMeetingSession(call, result)
                "GetVersion" -> result.success("Chime SDK " + sdkVersion())
                "ListAudioDevices" -> handleListAudioDevices(result)
                "Mute" -> handleMute(result)
                "UnbindVideoView" -> handleUnbindVideoView(call, result)
                "Unmute" -> handleUnmute(result)
                "SendMessage" -> handleSendMessage(call, result)
                "RequestScreenCapturePermission" -> handleRequestScreenCapturePermission(result)
                // "ScreenCaptureStart" -> handleScreenCaptureStart(call, result)
                "ScreenCaptureStop" -> handleScreenCaptureStop(call, result)
                else -> result.notImplemented()
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        // Store the activity when it becomes available
        activity = binding.activity
        Log.d(TAG, "onAttachedToActivity()")

        val safeMethodChannel: MethodChannel = MethodChannel(messenger!!, "ChimePlugin")
        _methodChannel = safeMethodChannel
        safeMethodChannel.setMethodCallHandler(this)

        val eventChannel = EventChannel(messenger!!, "ChimePluginEvents")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler
        {
            override fun onListen(arguments: Any?, events: EventSink?)
            {
                _eventSink = events
            }

            override fun onCancel(arguments: Any?)
            {
                Log.d(TAG, "EventChannel.setStreamHandler()/onCancel()")
            }
        })

        registry!!.registerViewFactory("ChimeDefaultVideoRenderView", ChimeDefaultVideoRenderViewFactory())

        // Register activity result listener
        binding.addActivityResultListener { requestCode, resultCode, data ->
            if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
                if (resultCode == Activity.RESULT_OK) {
                    handleScreenCaptureStart(data)
                    resultCallback?.success("Permission granted")
                } else {
                    resultCallback?.error("CANCELED", "User canceled the action", null)
                }
                resultCallback = null
                true
            } else {
                false
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
//        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
//        activity = null
    }


    private fun handleRequestScreenCapturePermission(result: MethodChannel.Result) {
        val safeApplicationContext: Context? = _applicationContext
        if (safeApplicationContext == null) {
            result.error(UNEXPECTED_ERROR__ERROR_CODE, UNEXPECTED_ERROR__ERROR_MESSAGE, null)
            return
        }

        try {
            val mediaProjectionManager = safeApplicationContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()

            // We need to get the current activity to start the permission request
            if (safeApplicationContext !is android.app.Activity) {
                // Since we're in a plugin, we need a way to get the current activity
                // This is typically done through a plugin binding that provides the activity
                result.error("ACTIVITY_NOT_AVAILABLE", "Cannot request screen capture permission without an activity", null)
                return
            }


            // Start the permission request
            // The result will be handled in the Flutter side through the onActivityResult method
            safeApplicationContext.startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
            result.success(null)
        } catch (e: Exception) {
            result.error("PERMISSION_REQUEST_ERROR", "Error requesting screen capture permission: ${e.message}", null)
        }
    }

    private var serviceConnection: ServiceConnection? = null
    private var screenShareManager: ScreenShareManager? = null

    fun handleScreenCaptureStart(intent: Intent?) {
        Log.d("ScreenCaptureService", "handleScreenCaptureStart")

        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null || intent == null || _applicationContext == null) {
            return
        }

        val safeApplicationContext = _applicationContext!!

        serviceConnection = object : ServiceConnection {
            @SuppressLint("NewApi")
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                Log.d("ScreenCaptureService", "onServiceConnected")

                val mediaProjectionManager = _applicationContext!!.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, intent)

// ✅ Capture system audio
                val audioConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // Capture media sounds
                    .build()

                val audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(audioConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                    .build()

                audioRecord.startRecording()

//
//                val surfaceTexture = SurfaceTexture(0)
//                val surface = Surface(surfaceTexture)
//
//                mMediaProjection.createVirtualDisplay(
//                    "ScreenCapture",
//                    1920,
//                    1080,
//                    1,
//                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
//                    surface,
//                    null,
//                    null
//                )
//
//                if (mMediaProjection == null) {
//                    Log.e("ScreenCaptureService", "Failed to get media projection!")
//                    return
//                }
                val screenCaptureSource = DefaultScreenCaptureSource(
                    safeApplicationContext,
                    logger,
                    DefaultSurfaceTextureCaptureSourceFactory(
                        logger,
                        defaultEglCoreFactory
                    ),
                    Activity.RESULT_OK,
                    intent,
                )

                screenCaptureSource.setMaxResolution(VideoResolution.VideoResolutionFHD)

                // ✅ Start screen capture
                screenCaptureSource.start()



                val screenCaptureSourceObserver = object : CaptureSourceObserver {
                    override fun onCaptureStarted() {
                        Log.d("ScreenCaptureService", "Screen capture started")
                        screenShareManager?.let { source ->
//                            _audioVideoFacade?.stopLocalVideo()
                            _audioVideoFacade?.startContentShare(source)
                        }
                    }

                    override fun onCaptureStopped() {
                        Log.d("ScreenCaptureService", "Screen capture stopped")
                    }

                    override fun onCaptureFailed(error: CaptureSourceError) {
                        Log.e("ScreenCaptureService", "Screen capture failed: $error")
                        _audioVideoFacade?.stopContentShare()
                    }
                }

                screenShareManager = ScreenShareManager(screenCaptureSource, safeApplicationContext)
                screenShareManager?.screenCaptureConnectionService = this
                screenShareManager?.addObserver(screenCaptureSourceObserver)

                // ✅ Restart screen capture properly
                screenShareManager?.stop()
                Handler(Looper.getMainLooper()).postDelayed({
                    screenShareManager?.start()
                }, 1000)
            }

            override fun onServiceDisconnected(arg0: ComponentName) {
                Log.d("ScreenCaptureService", "Service disconnected")
            }
        }

        // ✅ Start the screen capture service
        val serviceIntent = Intent(safeApplicationContext, ScreenCaptureService::class.java)
        safeApplicationContext.startService(serviceIntent)
        safeApplicationContext.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
    }

    private fun handleScreenCaptureStop(call: MethodCall, result: MethodChannel.Result) {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null) {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        try {
            safeAudioVideoFacade.stopContentShare()
            result.success(null)
        } catch (e: Exception) {
            result.error("SCREEN_CAPTURE_ERROR", "Error stopping screen capture: ${e.message}", null)
        }
    }

    private fun handleSendMessage(call: MethodCall, result: MethodChannel.Result) {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }
        if (call.argument<String>("topic") == null)
        {
            return
        }
        if (call.argument<String>("data") == null)
        {
            return
        }

        safeAudioVideoFacade.realtimeSendDataMessage(call.argument("topic")!!, call.argument("data")!!)
        result.success(null)
    }

    private fun handleClearViewIds(call: MethodCall, result: MethodChannel.Result)
    {
        ChimeDefaultVideoRenderViewFactory.clearViewIds()
        result.success(null)
    }

    private fun handleCreateMeetingSession(call: MethodCall, result: MethodChannel.Result)
    {
        val safeApplicationContext: Context? = _applicationContext
        if (safeApplicationContext == null)
        {
            result.error(UNEXPECTED_ERROR__ERROR_CODE, UNEXPECTED_ERROR__ERROR_MESSAGE, null)
            return
        }

        val attendeeId = call.argument<String>("AttendeeId")
        if (attendeeId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "AttendeeId", null)
            return
        }

        val externalMeetingId = call.argument<String>("ExternalMeetingId")
        if (externalMeetingId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "ExternalMeetingId", null)
            return
        }

        val externalUserId = call.argument<String>("ExternalUserId")
        if (externalUserId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "ExternalUserId", null)
            return
        }

        val joinToken = call.argument<String>("JoinToken")
        if (joinToken == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "JoinToken", null)
            return
        }

        val mediaRegion = call.argument<String>("MediaRegion")
        if (mediaRegion == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "MediaRegion", null)
            return
        }

        val meetingId = call.argument<String>("MeetingId")
        if (meetingId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "MeetingId", null)
            return
        }

        val mediaPlacementAudioFallbackUrl = call.argument<String>("MediaPlacementAudioFallbackUrl")
        if (mediaPlacementAudioFallbackUrl == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "MediaPlacementAudioFallbackUrl", null)
            return
        }

        val mediaPlacementAudioHostUrl = call.argument<String>("MediaPlacementAudioHostUrl")
        if (mediaPlacementAudioHostUrl == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "MediaPlacementAudioHostUrl", null)
            return
        }

        val mediaPlacementSignalingUrl = call.argument<String>("MediaPlacementSignalingUrl")
        if (mediaPlacementSignalingUrl == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "MediaPlacementSignalingUrl", null)
            return
        }

        val mediaPlacementTurnControlUrl = call.argument<String>("MediaPlacementTurnControlUrl")
        if (mediaPlacementTurnControlUrl == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "MediaPlacementTurnControlUrl", null)
            return
        }

        val screenDataUrl = call.argument<String>("ScreenDataUrl")
        val screenSharingUrl = call.argument<String>("ScreenSharingUrl")
        val screenViewingUrl = call.argument<String>("ScreenViewingUrl")
        val eventIngestionUrl = call.argument<String>("EventIngestionUrl")

        val mediaPlacement = MediaPlacement(mediaPlacementAudioFallbackUrl, mediaPlacementAudioHostUrl, mediaPlacementSignalingUrl, mediaPlacementTurnControlUrl, eventIngestionUrl)
        val meeting = Meeting(externalMeetingId, mediaPlacement, mediaRegion, meetingId)
        val mr = CreateMeetingResponse(meeting)
        val attendee = Attendee(attendeeId, externalUserId, joinToken)
        val ar = CreateAttendeeResponse(attendee)
        val configuration = MeetingSessionConfiguration(mr, ar) { s: String? -> s!! }

        val meetingSession: MeetingSession = DefaultMeetingSession(configuration, ConsoleLogger(), safeApplicationContext,
            eglCoreFactory = defaultEglCoreFactory)
        val safeAudioVideoFacade: AudioVideoFacade = meetingSession.audioVideo

        meetingSession.configuration
        _audioVideoFacade = safeAudioVideoFacade

        val safeEventSink: EventSink? = _eventSink
        if (safeEventSink == null)
        {
            result.error(UNEXPECTED_ERROR__ERROR_CODE, UNEXPECTED_ERROR__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.addActiveSpeakerObserver(DefaultActiveSpeakerPolicy(), ChimeActiveSpeakerDetectedObserver(safeEventSink))
        safeAudioVideoFacade.addAudioVideoObserver(ChimeAudioVideoObserver(safeEventSink))

        safeAudioVideoFacade.addDeviceChangeObserver(ChimeDeviceChangeObserver(safeEventSink))
        // addEventAnalyticsObserver: onEventReceived
        safeAudioVideoFacade.addMetricsObserver(ChimeMetricsObserver(safeEventSink))
        safeAudioVideoFacade.addRealtimeDataMessageObserver("meeting-topic", ChimeDataObserver(safeEventSink))
        // addRealtimeDataMessageObserver: onDataMessageReceived
        safeAudioVideoFacade.addRealtimeObserver(ChimeRealtimeObserver(safeEventSink))
        safeAudioVideoFacade.addVideoTileObserver(ChimeVideoTileObserver(safeEventSink))

        result.success(null)
    }

    private fun handleAudioVideoStart(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.start(
            audioVideoConfiguration = AudioVideoConfiguration(
                audioStreamType = AudioStreamType.VoiceCall,
            )
        )
        result.success(null)
    }

    private fun handleAudioVideoStop(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.stop()
        result.success(null)
    }

    private fun handleAudioVideoStartLocalVideo(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.startLocalVideo()
        result.success(null)
    }

    private fun handleAudioVideoStopLocalVideo(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.stopLocalVideo()
        result.success(null)
    }

    private fun handleAudioVideoStartRemoteVideo(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.startRemoteVideo()
        result.success(null)
    }

    private fun handleAudioVideoStopRemoteVideo(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.stopRemoteVideo()
        result.success(null)
    }

    private fun handleBindVideoView(call: MethodCall, result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        val viewId = call.argument<Int>("ViewId")
        if (viewId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "ViewId", null)
            return
        }

        val tileId = call.argument<Int>("TileId")
        if (tileId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "TileId", null)
            return
        }

        val view = ChimeDefaultVideoRenderViewFactory.getViewById(viewId)
        if (view == null)
        {
            result.error(VIEW_NOT_FOUND__ERROR_CODE, VIEW_NOT_FOUND__ERROR_MESSAGE + viewId, null)
            return
        }

        val videoRenderView: VideoRenderView = view.videoRenderView

        safeAudioVideoFacade.bindVideoView(videoRenderView, tileId)
        result.success(null)
    }

    private fun handleUnbindVideoView(call: MethodCall, result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        val tileId = call.argument<Int>("TileId")
        if (tileId == null)
        {
            result.error(UNEXPECTED_NULL_PARAMETER__ERROR_CODE, UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE + "TileId", null)
            return
        }

        result.success(null)
    }

    private fun handleMute(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        val results = safeAudioVideoFacade.realtimeLocalMute()
        result.success(results)
    }

    private fun handleUnmute(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        safeAudioVideoFacade.realtimeLocalUnmute()
        result.success(null)
    }

    private fun handleListAudioDevices(result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        var jsonString = ""
        for (device in safeAudioVideoFacade.listAudioDevices())
            jsonString += "{\"Label\": \"" + device.label + "\", \"Type\": \"" + device.type + "\", \"Port\": \"no-port\", \"Description\": \"no-description\"},"

        jsonString = jsonString.substring(0, jsonString.length - 1)
        @Suppress("ConvertToStringTemplate")
        jsonString = "[" + jsonString + "]"
        result.success(jsonString)
    }

    private fun handleChooseAudioDevice(call: MethodCall, result: MethodChannel.Result)
    {
        val safeAudioVideoFacade: AudioVideoFacade? = _audioVideoFacade
        if (safeAudioVideoFacade == null)
        {
            result.error(NO_AUDIO_VIDEO_FACADE__ERROR_CODE, NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE, null)
            return
        }

        val deviceLabel = call.argument<String>("Label")

        for (device in safeAudioVideoFacade.listAudioDevices())
        {
            if (device.label == deviceLabel)
            {
                safeAudioVideoFacade.chooseAudioDevice(mediaDevice = device)
                result.success(null)
                return
            }
        }

        // result.error(ERROR__NO_AUDIO_VIDEO_FACADE__ERROR_CODE, "exception caught during choosing an audio device", null)
    }

    companion object
    {
        private const val TAG = "ChimePlugin"
        private const val NO_AUDIO_VIDEO_FACADE__ERROR_CODE = "2"
        private const val NO_AUDIO_VIDEO_FACADE__ERROR_MESSAGE = "No AudioVideoFacade created."
        private const val VIEW_NOT_FOUND__ERROR_CODE = "3"
        private const val VIEW_NOT_FOUND__ERROR_MESSAGE = "No View found with ViewId="
        private const val UNEXPECTED_NULL_PARAMETER__ERROR_CODE = "4"
        private const val UNEXPECTED_NULL_PARAMETER__ERROR_MESSAGE = "Unexpected null parameter: "
        private const val UNEXPECTED_ERROR__ERROR_CODE = "99"
        private const val UNEXPECTED_ERROR__ERROR_MESSAGE = "Unexpected error."
    }
}
