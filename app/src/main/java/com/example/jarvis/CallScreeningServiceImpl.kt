package com.example.jarvis

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import android.telephony.SmsManager

class CallScreeningServiceImpl : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val endTime = prefs.getLong("call_screen_end", 0L)
        val now = System.currentTimeMillis()

        if (now > endTime) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val response = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .build()
        respondToCall(callDetails, response)

        val number = callDetails.handle?.schemeSpecificPart
        val message = prefs.getString("call_screen_message", "Sir is busy right now.") ?: "Sir is busy right now."
        if (!number.isNullOrBlank()) {
            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(number, null, message, null, null)
            } catch (e: Exception) {
                // Nothing more we can do from a background service
            }
        }
    }
}
