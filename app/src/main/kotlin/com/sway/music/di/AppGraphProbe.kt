package com.sway.music.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Test-visible view into the real aggregated SingletonComponent graph (story 1.2).
 * Kept in `main` so KSP generates the Hilt component (test sources are not
 * KSP-processed). Probing through an EntryPoint keeps production injection
 * minimal while proving the full @HiltAndroidApp -> module -> qualifier chain (AD-2).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppGraphProbe {

    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher

    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher
}
