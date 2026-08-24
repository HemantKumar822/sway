package com.sway.music.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.sway.core.data.Appearance
import com.sway.core.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun appearance_options_render_and_click() {
        var selected = Appearance.SYSTEM
        compose.setContent {
            SettingsScreen(
                appearance = selected,
                onAppearanceSelected = { selected = it },
                onOpenAbout = {},
            )
        }
        compose.onNodeWithTag("appearance_option_SYSTEM").assertExists()
        compose.onNodeWithTag("appearance_option_LIGHT").assertExists()
        compose.onNodeWithTag("appearance_option_DARK").assertExists()
        compose.onNodeWithTag("appearance_option_DARK").performClick()
        assertEquals(Appearance.DARK, selected)
    }

    @Test fun quality_row_hidden_whenFlagFalse_via_nulls() {
        compose.setContent {
            SettingsScreen(
                appearance = Appearance.SYSTEM,
                onAppearanceSelected = {},
                onOpenAbout = {},
                quality = null,
                onQualityClick = null,
            )
        }
        compose.onNodeWithTag("appearance_option_SYSTEM").assertExists()
        assertEquals(0, compose.onAllNodesWithTag("settings_quality_row").fetchSemanticsNodes().size)
    }

    @Test fun quality_row_visible_whenProvided() {
        compose.setContent {
            SettingsScreen(
                appearance = Appearance.SYSTEM,
                onAppearanceSelected = {},
                onOpenAbout = {},
                quality = Quality.AUTO,
                onQualityClick = {},
            )
        }
        compose.onNodeWithTag("settings_quality_row").assertExists()
    }
}
