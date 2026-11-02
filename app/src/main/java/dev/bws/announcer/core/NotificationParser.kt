package dev.bws.announcer.core

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

object NotificationParser {

    fun parse(context: Context, sbn: StatusBarNotification, ranking: RankingMap?): Parsed {
        val n = sbn.notification
        val extras = n.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        var body = if (big.length > text.length) big else text
        var fromSelf = false
        var sender: String? = null

        // Chat apps: read "Sender in Group: latest message" instead of "3 new messages".
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.let { style ->
            val last = style.messages.lastOrNull()
            if (last?.text != null) {
                // A null person (or the style's own user) means the device owner sent it, i.e. the
                // app re-posted the notification with your own reply appended.
                val person = last.person
                fromSelf = person == null ||
                    (person.key != null && person.key == style.user.key) ||
                    (person.key == null && person.name == style.user.name)
                val who = person?.name?.toString()
                sender = who
                val convo = style.conversationTitle?.toString()
                title = when {
                    who != null && style.isGroupConversation && !convo.isNullOrBlank() -> "$who in $convo"
                    who != null -> who
                    else -> convo ?: title
                }
                body = last.text.toString()
            }
        }

        val r = Ranking()
        val hasRanking = ranking?.getRanking(sbn.key, r) == true
        val flags = n.flags
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val progress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        // Incoming call: CallStyle with type INCOMING (API 31+), or a ringing CATEGORY_CALL with a full-screen intent.
        val callType = extras.getInt("android.callType", 0)
        val incomingCall = n.category == Notification.CATEGORY_CALL &&
            (callType == 1 || (callType == 0 && n.fullScreenIntent != null))

        return Parsed(
            pkg = sbn.packageName,
            key = sbn.key,
            appLabel = appLabel(context, sbn),
            title = title.trim(),
            body = body.trim(),
            fromSelf = fromSelf,
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isSilent = hasRanking && r.importance < NotificationManager.IMPORTANCE_DEFAULT,
            passesDnd = !hasRanking || r.matchesInterruptionFilter(),
            isMediaOrProgress = progress || template.contains("MediaStyle") ||
                n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS,
            sender = sender?.trim()?.takeIf { it.isNotEmpty() },
            isIncomingCall = incomingCall,
        )
    }

    /** Label lookup that works even for packages hidden by Android 11+ package visibility. */
    fun appLabel(context: Context, sbn: StatusBarNotification): String {
        val pm = context.packageManager
        runCatching { return pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
        val info: ApplicationInfo? = if (Build.VERSION.SDK_INT >= 33) {
            sbn.notification.extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
        } else {
            @Suppress("DEPRECATION") sbn.notification.extras.getParcelable("android.appInfo")
        }
        return info?.loadLabel(pm)?.toString() ?: sbn.packageName.substringAfterLast('.')
    }

    fun labelFor(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}
