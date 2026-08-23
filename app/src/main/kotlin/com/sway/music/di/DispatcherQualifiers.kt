package com.sway.music.di

import javax.inject.Qualifier

/**
 * Dispatcher qualifiers (AR-14 threading law): IO for adapters/storage, Default for
 * parse/extraction/CPU work; the main thread does composition and player commands only.
 *
 * Later epics consume these qualifiers from their owning modules' bindings; :app
 * aggregates the graph at its KSP pass (AD-2/AD-5).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
