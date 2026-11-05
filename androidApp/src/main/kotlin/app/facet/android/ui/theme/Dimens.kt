package app.facet.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object Space {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

object Radius {
    val chip = 8.dp
    val card = 14.dp
    val fab = 20.dp
    val panel = 20.dp
    val sheet = 28.dp
}

object Sizes {
    val touchMin = 48.dp
    val touchMinAr = 60.dp
    val button = 56.dp
    val buttonText = 48.dp
    val fab = 60.dp
    val finishWidth = 184.dp
    val finishHeight = 64.dp
    val icon = 24.dp
    val galleryThumb = 112.dp
}

val FacetShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.chip),
    small = RoundedCornerShape(Radius.chip),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.panel),
    extraLarge = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
)
