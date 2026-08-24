package com.sway.designui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape ramp (story 9.1, UX frontmatter {rounded.xs..full}): crisp, Notion-
 * adjacent small radii at the content scale; generous only where artwork or
 * sheets earn it.
 */
object SwayShapes {

    val xs = RoundedCornerShape(8.dp)
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(20.dp)
    val full = RoundedCornerShape(50)

    val m3: Shapes = Shapes(
        extraSmall = xs,
        small = sm,
        medium = md,
        large = lg,
        extraLarge = full,
    )
}
