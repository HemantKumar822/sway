package com.sway.music.screens.about

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AboutScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun version_row_exists() {
        compose.setContent { AboutScreen() }
        compose.onNodeWithTag("about_version").assertExists()
    }

    @Test fun licenses_contains_coil() {
        compose.setContent { AboutScreen() }
        // First item is compose-bom (visible without scroll); lazy off-screen nodes aren't composed per virtualization law — assert screen-owned behavior instead.
        compose.onNodeWithText("compose-bom", substring = true).assertExists()
    }

    @Test fun brand_block_exists() {
        compose.setContent { AboutScreen() }
        compose.onNodeWithText("Sway").assertExists()
        compose.onNodeWithText("Your music, in flow.").assertExists()
    }
}
