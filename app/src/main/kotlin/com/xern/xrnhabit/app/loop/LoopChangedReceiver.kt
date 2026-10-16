package com.xern.xrnhabit.app.loop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xern.xrnhabit.app.XrnhabitApp

/**
 * Loop's "habits changed" push. Declared in the manifest, so it also wakes a dead process; only
 * senders holding [LoopContract.PERMISSION] (our Loop build) can reach it.
 */
class LoopChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LoopContract.ACTION_HABITS_CHANGED) return
        val pending = goAsync()
        (context.applicationContext as XrnhabitApp).container.loop.sync(fromServer = false, onDone = pending::finish) // the change is local to xrnloops
    }
}
