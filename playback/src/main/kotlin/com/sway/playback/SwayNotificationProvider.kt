package com.sway.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * Story 6.1 thin media-notification wrapper (FR-17, AD-6 rule 8, A-10).
 *
 * Architecture mandates "notification follows Media3 defaults wrapped thin":
 * this provider delegates EVERYTHING to a configured
 * [DefaultMediaNotificationProvider] and customizes only the branded channel
 * id/name and the stable [NOTIFICATION_ID]. Stock 1.11.0 behavior preserved
 * verbatim (verified against upstream sources at implementation time):
 *  - actions prev / play-pause / next rendered from available player commands;
 *  - content title = MediaMetadata.title, text = artist, largeIcon via the
 *    session bitmap loader (artwork);
 *  - `Util.ensureNotificationChannel` creates the channel on first post;
 *  - deleteIntent wiring for platform dismissal semantics; notifications are
 *    built non-ongoing so pause degrades them to swipe-dismissable (A-10).
 *
 * The [NOTIFICATION_ID] constant is deliberately distinct from Media3
 * internals (default 1001, internal shutdown 20938) so the service's
 * onDestroy zombie-notification purge cancels exactly what we post.
 */
internal class SwayNotificationProvider(context: Context) : MediaNotification.Provider {

    private val delegate: DefaultMediaNotificationProvider =
        DefaultMediaNotificationProvider.Builder(context)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.sway_notification_channel_name)
            .setNotificationId(NOTIFICATION_ID)
            .build()

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = delegate.createNotification(
        mediaSession,
        mediaButtonPreferences,
        actionFactory,
        onNotificationChangedCallback,
    )

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    companion object {
        /** Branded notification channel id — created lazily by the delegate on first post. */
        const val CHANNEL_ID: String = "sway.playback.media"

        /**
         * Stable notification id for sway media notifications; distinct from
         * Media3 internals (1001 default / 20938 shutdown) per spec Design Note 7.
         */
        const val NOTIFICATION_ID: Int = 2001
    }
}
