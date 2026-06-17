package dev.jaredhq.dashboardandroid.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.jaredhq.dashboardandroid.MainActivity

/**
 * The fast-capture home-screen widget — the lowest-friction path from "I have a
 * thought" to "it's in the dashboard". It does NOT capture inline (Glance can't
 * host a text field); instead each control deep-links straight into the app's
 * Capture screen via [MainActivity.EXTRA_START_ROUTE], so a tap lands the user
 * one keystroke away from sending. A second control opens Today.
 *
 * Styled with the dashboard's warm brand surface so it reads as the same product
 * on the home screen. Stateless and network-free: it never blocks.
 */
object CaptureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val context = LocalContext.current
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(BrandWidget.Surface))
                .cornerRadius(16.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Capture a thought",
                style = TextStyle(
                    color = ColorProvider(BrandWidget.OnSurface),
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height(10.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Button(
                    text = "＋ Capture",
                    onClick = actionStartActivity(openRoute(context, "capture")),
                )
                Spacer(GlanceModifier.width(8.dp))
                Button(
                    text = "Today",
                    onClick = actionStartActivity(openRoute(context, "today")),
                )
            }
        }
    }
}

/** Deep link into MainActivity on the given tab route. */
internal fun openRoute(context: Context, route: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra(MainActivity.EXTRA_START_ROUTE, route)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

/** Raw brand tokens for the widgets (Glance can't read the Compose MaterialTheme). */
internal object BrandWidget {
    val Surface = Color(0xFF211C18)
    val OnSurface = Color(0xFFEFE9E1)
    val Muted = Color(0xFFB6ADA1)
    val Accent = Color(0xFFCC7A5C)
}

/** Receiver registered in the manifest. */
class CaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureWidget
}
