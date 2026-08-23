package com.sway.music.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * App-scope bindings that are not yet worth their own file (AD-2). Deliberately
 * minimal today — the empty-but-real seed of the aggregated graph.
 *
 * Aggregation pattern for later epics: bindings live in their OWNING module
 * (:catalog provides CatalogSource, :playback provides PlayerConnection,
 * :core:data provides repositories, ...) as @InstallIn modules declared there;
 * Hilt discovers them because :app depends on every module (AD-5) and runs the
 * aggregation KSP pass. Keep this file for genuinely app-scope bindings only.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
