package com.sway.music

import android.app.Application

/**
 * Process entry point. Deliberately inert per the startup law (AD-10): no disk,
 * network, or preferences work before first composition. The Hilt graph attaches
 * here in story 1.2.
 */
class SwayApplication : Application()
