package dev.jaredhq.dashboardandroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.Button
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home-screen "Today" widget (Glance). It renders from the OFFLINE CACHE
 * only — never blocks composition on the network — so it appears instantly. The
 * WorkManager [dev.jaredhq.dashboardandroid.work.RefreshWorker] keeps that cache
 * warm and nudges this widget to recompose.
 *
 * V1 surface: headline, recovery flag, focus block, and the first few habits
 * with ✓ toggles, plus a ▶ focus button. Mutations run server-side via the
 * repository (which returns the fresh Today) and then re-render the widget.
 *
 * NOTE: this is a working skeleton. It compiles against Glance 1.1.0 APIs but
 * has not been run on a device in this environment (no Android SDK here) — see
 * README "Verification status".
 */
object TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ServiceLocator.init(context)
        val today = withContext(Dispatchers.IO) { ServiceLocator.repository.cachedToday() }
        provideContent { WidgetContent(today) }
    }

    @Composable
    private fun WidgetContent(today: TodayPayload?) {
        Column(
            modifier = GlanceModifier.fillMaxWidth().padding(12.dp),
        ) {
            if (today == null) {
                Text("Open the app to connect your dashboard.")
                return@Column
            }

            Text(
                text = if (today.recoveryMode) "Recovery day" else "Today",
                style = TextStyle(fontWeight = FontWeight.Medium),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(today.headline, style = TextStyle(fontWeight = FontWeight.Bold))

            today.focusBlock?.let { block ->
                Spacer(GlanceModifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Focus ${block.startLabel}–${block.endLabel}  ")
                    Button(
                        text = "▶",
                        onClick = actionRunCallback<StartFocusAction>(),
                    )
                }
            }

            if (today.habits.isNotEmpty()) {
                Spacer(GlanceModifier.height(6.dp))
                Text("Habits · ${today.habitsRemaining} left")
                // Keep the widget compact: show at most the first three habits.
                today.habits.take(3).forEach { habit ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            text = if (habit.doneToday) "✓" else "○",
                            onClick = actionRunCallback<ToggleHabitAction>(
                                actionParametersOf(ToggleHabitAction.habitIdKey to habit.id),
                            ),
                        )
                        Text("  ${habit.title}")
                    }
                }
            }
        }
    }
}

/** The receiver registered in the manifest. */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget
}

/** ✓ a habit straight from the widget, then re-render from the fresh payload. */
class ToggleHabitAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val habitId = parameters[habitIdKey] ?: return
        ServiceLocator.init(context)
        ServiceLocator.repository.toggleHabit(habitId)
        TodayWidget.update(context, glanceId)
    }

    companion object {
        val habitIdKey = ActionParameters.Key<Int>("habitId")
    }
}

/** ▶ start the recommended focus block from the widget. */
class StartFocusAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        ServiceLocator.init(context)
        val today = ServiceLocator.repository.cachedToday()
        val taskId = today?.focusBlock?.taskId ?: today?.mainAction?.taskId
        ServiceLocator.repository.startFocus(taskId = taskId)
        TodayWidget.update(context, glanceId)
    }
}
