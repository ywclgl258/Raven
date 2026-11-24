package com.raven.app.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple HIG inspired corner geometry shared across all Raven surfaces.
 */
object RavenShapes {
    val CardRadius: Dp = 14.dp
    val SheetRadius: Dp = 18.dp
    val DialogRadius: Dp = 18.dp
    val ControlRadius: Dp = 10.dp
    val BadgeRadius: Dp = 6.dp

    val CardShape: RoundedCornerShape = RoundedCornerShape(CardRadius)
    val SheetShape: RoundedCornerShape = RoundedCornerShape(topStart = SheetRadius, topEnd = SheetRadius)
    val DialogShape: RoundedCornerShape = RoundedCornerShape(DialogRadius)
    val ControlShape: RoundedCornerShape = RoundedCornerShape(ControlRadius)
    val BadgeShape: RoundedCornerShape = RoundedCornerShape(BadgeRadius)
    val PillShape: RoundedCornerShape = CircleShape

    val MaterialShapes: Shapes = Shapes(
        extraSmall = BadgeShape,
        small = ControlShape,
        medium = CardShape,
        large = DialogShape,
        extraLarge = SheetShape,
    )
}
