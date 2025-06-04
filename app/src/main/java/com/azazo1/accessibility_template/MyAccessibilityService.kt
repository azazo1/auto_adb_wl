package com.azazo1.accessibility_template

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.math.log

class MyAccessibilityService : AccessibilityService() {
    var mServiceInfo: AccessibilityServiceInfo? = null;

    /// 用户同意了无障碍权限的时候
    override fun onServiceConnected() {
        // 见 <pj>/app/src/main/res/xml/accessibility_service_config.xml
        mServiceInfo = AccessibilityServiceInfo().apply {
            // Set the type of events that this service wants to listen to. Others
            // aren't passed to this service.

//             eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            // Default services are invoked only if no package-specific services are
            // present for the type of AccessibilityEvent generated. This service is
            // app-specific, so the flag isn't necessary. For a general-purpose
            // service, consider setting the DEFAULT flag.
//            flags = AccessibilityServiceInfo.DEFAULT;

            // If you only want this service to work with specific apps, set their
            // package names here. Otherwise, when the service is activated, it
            // listens to events from all apps.

//            packageNames = arrayOf("com.example.android.myFirstApp", "com.example.android.mySecondApp")

        }
//        serviceInfo = mServiceInfo
        Log.i("mas", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        event?.let {
//            Log.i("mas", log(it.eventType.toDouble(), 2.toDouble()).toString())
//        }
//        event?.source?.let {
//            Log.i("mas", it.toString())
//        }
        Log.i("mas", "onAccessibilityEvent")
    }

    override fun onInterrupt() {
        Log.i("mas", "onInterrupt")
    }

    /// 用户决定关闭无障碍权限时
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i("mas", "onUnbind")
        return super.onUnbind(intent)
    }
}