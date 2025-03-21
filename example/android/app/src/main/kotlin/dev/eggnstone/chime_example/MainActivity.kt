package dev.eggnstone.chime_example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import dev.eggnstone.chime.ChimePlugin

class MainActivity : FlutterActivity() {

    private val CHANNEL = "main.app.method/channel"
    private val SCREEN_CAPTURE_REQUEST_CODE = 1001
    private var resultCallback: MethodChannel.Result? = null

    private var flutterEngine: FlutterEngine? = null

    private var chimePlugin: ChimePlugin? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        this.flutterEngine = flutterEngine

        chimePlugin = ChimePlugin()
        // flutterEngine.plugins.add(chimePlugin)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "startActivityForResult") {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                resultCallback = result
                startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
            } else {
                result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                //get chime plugin instance from flutterEngine
                // val chimePlugin = flutterEngine?.plugins?.get(ChimePlugin::class.java)
            
                chimePlugin?.handleScreenCaptureStart(data)
            } else {
                resultCallback?.success(false)
            }
        }
    }
}
