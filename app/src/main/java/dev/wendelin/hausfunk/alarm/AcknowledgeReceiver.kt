package dev.wendelin.hausfunk.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wendelin.hausfunk.HausfunkApplication

/** "Acknowledge" on the alarm notification: silences the alarm of the running parent session. */
class AcknowledgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as HausfunkApplication).parentSession.value?.acknowledgeAlarm()
    }
}
