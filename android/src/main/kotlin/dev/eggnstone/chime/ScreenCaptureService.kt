/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.eggnstone.chime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {
    private lateinit var notificationManager: NotificationManager

    private val CHANNEL_ID = "ScreenCaptureServiceChannelID"
    private val CHANNEL_NAME = "Screen Share"
    private val SERVICE_ID = 110293

    private val binder = ScreenCaptureBinder()

    inner class ScreenCaptureBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()

        notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.d("ScreenCaptureService", "onStartCommand")
        if (isOSVersionAtLeast(Build.VERSION_CODES.O)) {
            Log.d("ScreenCaptureService", "Creating notification channel")
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        if (isOSVersionAtLeast(Build.VERSION_CODES.Q)) {
            Log.d("ScreenCaptureService", "Starting foreground service with media projection")
            startForeground(
                SERVICE_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.logoandroid)
                    .setContentTitle("Screen Share")
                    .setContentText("Screen Share is running")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            Log.d("ScreenCaptureService", "Starting foreground service")
            startForeground(
                SERVICE_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.logoandroid)
                    .setContentTitle("Screen Share")
                    .setContentText("Screen Share is running")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
