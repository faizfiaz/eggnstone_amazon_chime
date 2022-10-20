package dev.eggnstone.chime.observers

import android.os.Handler
import android.os.Looper
import com.amazonaws.services.chime.sdk.meetings.realtime.datamessage.DataMessage
import com.amazonaws.services.chime.sdk.meetings.realtime.datamessage.DataMessageObserver
import io.flutter.plugin.common.EventChannel
import org.json.JSONObject

class ChimeDataObserver(private val _eventSink: EventChannel.EventSink) : DataMessageObserver {
    override fun onDataMessageReceived(dataMessage: DataMessage) {
        val jsonObject = JSONObject()
        jsonObject.put("Name", "OnActiveSpeakerDetected")
        jsonObject.put("Arguments", convertMessage(dataMessage))

        Handler(Looper.getMainLooper()).post {
            _eventSink.success(jsonObject.toString())
        }
    }

    private fun convertMessage(dataMessage: DataMessage): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put("Timestamp", dataMessage.timestampMs)
        jsonObject.put("Topic", dataMessage.topic)
        jsonObject.put("SenderAttendeeId", dataMessage.senderAttendeeId)
        jsonObject.put("SenderExternalUserId", dataMessage.senderExternalUserId)
        jsonObject.put("data", dataMessage.data)
        jsonObject.put("dataText", dataMessage.text())
        return jsonObject
    }
}