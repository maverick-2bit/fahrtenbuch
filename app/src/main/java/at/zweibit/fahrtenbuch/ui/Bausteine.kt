package at.zweibit.fahrtenbuch.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

fun kategorieFarbe(farbe: Long?): Color = if (farbe == null) Color(0xFF9E9E9E) else Color(farbe)

fun schriftAuf(hintergrund: Color): Color = if (hintergrund.luminance() > 0.55f) Color.Black else Color.White

@Composable
fun Farbpunkt(farbe: Color, groesse: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(groesse).background(farbe, CircleShape))
}

@Composable
fun Abschnitt(titel: String, modifier: Modifier = Modifier) {
    Text(
        titel,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

fun datumWaehlen(context: Context, start: LocalDate, gewaehlt: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, j, m, t -> gewaehlt(LocalDate.of(j, m + 1, t)) },
        start.year, start.monthValue - 1, start.dayOfMonth,
    ).show()
}

fun zeitWaehlen(context: Context, start: LocalTime, gewaehlt: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> gewaehlt(LocalTime.of(h, m)) }, start.hour, start.minute, true).show()
}
