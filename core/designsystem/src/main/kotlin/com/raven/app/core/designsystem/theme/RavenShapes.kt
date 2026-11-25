package com.raven.app.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * HarmonyOS Design Z-depth corner geometry (`4dp` micro -> `16dp` cards -> `20dp` buttons -> `32dp` sheets)
 * shared across all Raven surfaces.
 */
object RavenShapes {
    val MicroRadius: Dp = 4.dp
    val BadgeRadius: Dp = 6.dp
    val SmallRadius: Dp = 8.dp
    val ControlRadius: Dp = 12.dp
    val CardRadius: Dp = 16.dp
    val ButtonRadius: Dp = 20.dp
    val DialogRadius: Dp = 28.dp
    val SheetRadius: Dp = 32.dp

    val MicroShape: RoundedCornerShape = RoundedCornerShape(MicroRadius)
    val BadgeShape: RoundedCornerShape = RoundedCornerShape(BadgeRadius)
    val SmallShape: RoundedCornerShape = RoundedCornerShape(SmallRadius)
    val ControlShape: RoundedCornerShape = RoundedCornerShape(ControlRadius)
    val CardShape: RoundedCornerShape = RoundedCornerShape(CardRadius)
    val ButtonShape: RoundedCornerShape = RoundedCornerShape(ButtonRadius)
    val DialogShape: RoundedCornerShape = RoundedCornerShape(DialogRadius)
    val SheetShape: RoundedCornerShape = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius)
    val PillShape: RoundedCornerShape = CircleShape

    val MaterialShapes: Shapes = Shapes(
        extraSmall = BadgeShape,
        small = ControlShape,
        medium = CardShape,
        large = DialogShape,
        extraLarge = SheetShape,
    )
}
