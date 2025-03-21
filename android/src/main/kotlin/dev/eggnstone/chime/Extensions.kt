package dev.eggnstone.chime

import android.os.Build
import com.amazonaws.services.chime.sdk.meetings.audiovideo.video.RemoteVideoSource
import com.amazonaws.services.chime.sdk.meetings.utils.DefaultModality
import com.amazonaws.services.chime.sdk.meetings.utils.ModalityType

fun String.isContentShare(): Boolean {
    return DefaultModality(this).hasModality(ModalityType.Content)
}

fun RemoteVideoSource.isContentShare(): Boolean {
    return this.attendeeId.isContentShare()
}

fun isOSVersionAtLeast(targetVersion: Int): Boolean = Build.VERSION.SDK_INT >= targetVersion
