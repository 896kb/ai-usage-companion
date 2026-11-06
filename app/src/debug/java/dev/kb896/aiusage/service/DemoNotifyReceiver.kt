package dev.kb896.aiusage.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kb896.aiusage.rear.NoticeShade
import dev.kb896.aiusage.rear.RearActivity

/**
 * DEBUG BUILDS ONLY (declared in the debug manifest): a made-up notification on the back, for
 * filming, sent from adb. It goes through the same two doors as the Debug card's "Send fake
 * notifications": the panel's list and the shell's banner and pull-down.
 *
 *   adb shell am broadcast -a dev.kb896.aiusage.dev.DEMO_NOTIFY -p dev.kb896.aiusage.dev \
 *     --es app Messages --es pkg com.android.mms --es title Ana --es text 'See you at 8?'
 *   adb shell am broadcast -a dev.kb896.aiusage.dev.DEMO_NOTIFY -p dev.kb896.aiusage.dev --ez clear true
 *
 * Clearing empties the back's list only (the panel's saved one included); the front keeps its own.
 *
 * `pkg` only picks the icon. Guarded by android.permission.DUMP, which adb's shell holds.
 */
class DemoNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("clear", false)) {
            // The panel's list is the one list (BackNotices follows it): "nothing is on the
            // front" empties it, as Match the front notifications would — and everything, the
            // cards saved before cards had keys too, which that leaves (a real WhatsApp card
            // showed in a take, 2026-11-06).
            context.sendBroadcast(Intent(RearActivity.ACTION_NOTIFICATIONS_ACTIVE).setPackage(context.packageName)
                .putExtra(RearActivity.EXTRA_NOTIFICATION_KEYS, emptyArray<String>())
                .putExtra(RearActivity.EXTRA_NOTIFICATIONS_EVERYTHING, true))
            BackNotices.keys().forEach { BackNotices.removed(context, it) }
            return
        }
        val app = intent.getStringExtra("app") ?: "Messages"
        val pkg = intent.getStringExtra("pkg") ?: context.packageName
        val title = intent.getStringExtra("title").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()
        val key = "demo:${System.currentTimeMillis()}"
        context.sendBroadcast(Intent(RearActivity.ACTION_NOTIFICATION).setPackage(context.packageName)
            .putExtra(RearActivity.EXTRA_NOTIFICATION_APP, app)
            .putExtra(RearActivity.EXTRA_NOTIFICATION_PKG, pkg)
            .putExtra(RearActivity.EXTRA_NOTIFICATION_KEY, key)
            .putExtra(RearActivity.EXTRA_NOTIFICATION_TITLE, title)
            .putExtra(RearActivity.EXTRA_NOTIFICATION_TEXT, text))
        BackNotices.posted(context, NoticeShade.Item(key, pkg, app, title, text))
    }
}
