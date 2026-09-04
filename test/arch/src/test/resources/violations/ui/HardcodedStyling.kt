package fixtures

// VIOLATION FIXTURE - never compiled. Rule 8 must reject all three of these.
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HardcodedStyling() {
    val brand = Color(0xFF00FF00)
    val style = TextStyle(fontSize = 18.sp)
    Box(modifier = Modifier.padding(12.dp))
}
