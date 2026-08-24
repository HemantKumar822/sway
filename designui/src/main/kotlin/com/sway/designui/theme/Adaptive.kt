package com.sway.designui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Adaptive layout helpers (story 14.5, FR-29, UX-P11).
 *
 * Thresholds are window-width driven (dp) and testable via LocalConfiguration overrides
 * in Robolectric qualifiers / device matrix.
 */
object Adaptive {

    const val COMPACT_MAX = 599
    const val MEDIUM_BREAKPOINT = 600
    const val EXPANDED_BREAKPOINT = 840

    /** Content max-width for >=600dp: 640dp centered (lists, hub, detail heroes). */
    val CONTENT_MAX_WIDTH = 640.dp

    /** Pure classification, window width in dp → class. */
    fun widthClass(screenWidthDp: Int): WindowWidthClass = when {
        screenWidthDp >= EXPANDED_BREAKPOINT -> WindowWidthClass.Expanded
        screenWidthDp >= MEDIUM_BREAKPOINT -> WindowWidthClass.Medium
        else -> WindowWidthClass.Compact
    }

    @Composable
    fun currentWidthClass(): WindowWidthClass {
        val w = LocalConfiguration.current.screenWidthDp
        return widthClass(w)
    }

    @Composable
    fun isAtLeastMedium(): Boolean = LocalConfiguration.current.screenWidthDp >= MEDIUM_BREAKPOINT

    @Composable
    fun isExpanded(): Boolean = LocalConfiguration.current.screenWidthDp >= EXPANDED_BREAKPOINT
}

enum class WindowWidthClass { Compact, Medium, Expanded }

/**
 * Container modifier for >=600dp: caps width at 640dp and centers content.
 * Compact keeps fillMaxWidth. Use inside a centered parent Box.
 */
@Composable
fun Modifier.adaptiveContentWidth(): Modifier {
    val isMedium = Adaptive.isAtLeastMedium()
    return if (isMedium) this.fillMaxWidth().widthIn(max = Adaptive.CONTENT_MAX_WIDTH) else this.fillMaxWidth()
}

/**
 * Helper Box that centers adaptive content on >=600dp, fills otherwise.
 */
@Composable
fun AdaptiveContentBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Box(Modifier.adaptiveContentWidth()) { content() }
    }
}
