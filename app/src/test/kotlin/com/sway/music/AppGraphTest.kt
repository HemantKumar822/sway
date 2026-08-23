package com.sway.music

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sway.music.di.AppGraphProbe
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Story 1.2: proves the Hilt graph attaches (@HiltAndroidApp) and the seeded singleton
 * bindings resolve through the real aggregated graph (AD-2), and that MainActivity is a
 * working @AndroidEntryPoint (AR-3).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = SwayApplication::class)
class AppGraphTest {

    @Test
    fun hiltGraph_resolvesSeededDispatcherBindings() {
        val app = ApplicationProvider.getApplicationContext<SwayApplication>()

        val probe = EntryPointAccessors.fromApplication(app, AppGraphProbe::class.java)

        assertEquals(Dispatchers.IO, probe.ioDispatcher())
        assertEquals(Dispatchers.Default, probe.defaultDispatcher())
    }

    @Test
    fun mainActivity_launchesAsHiltEntryPoint() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        scenario.close()
    }
}
