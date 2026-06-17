package dev.jaredhq.dashboardandroid.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jaredhq.dashboardandroid.data.api.FakeData
import dev.jaredhq.dashboardandroid.domain.model.FocusBlock
import dev.jaredhq.dashboardandroid.domain.model.Habit
import dev.jaredhq.dashboardandroid.domain.model.MainAction
import dev.jaredhq.dashboardandroid.domain.model.Readiness
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme

/**
 * The Today screen — the V1 hero surface. Mirrors the widget: headline, recovery
 * banner, readiness, main action, focus block (▶ start), and habits (✓ toggle).
 * Stateless body ([TodayContent]) so Compose previews render it with fake data
 * without a ViewModel.
 */
@Composable
fun TodayScreen(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onToggleHabit: (Int) -> Unit,
    onStartFocus: () -> Unit,
) {
    TodayContent(state, onRefresh, onToggleHabit, onStartFocus)
}

@Composable
private fun TodayContent(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onToggleHabit: (Int) -> Unit,
    onStartFocus: () -> Unit,
) {
    val payload = state.payload
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (payload == null) {
            EmptyOrLoading(state)
            return@Column
        }

        state.error?.let { BannerCard(text = it, error = true) }
        if (state.fromCache && state.error == null) {
            BannerCard(text = "Showing cached data — refreshing…", error = false)
        }

        if (payload.recoveryMode) {
            BannerCard(text = "Recovery mode — protect your downtime today.", error = false)
        }

        Text(
            text = payload.headline,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        ReadinessRow(payload.readiness)

        payload.mainAction?.let { MainActionCard(it) }
        payload.focusBlock?.let {
            FocusBlockCard(it, inFlight = state.focusInFlight, onStartFocus = onStartFocus)
        }

        ActionTile(title = payload.bodyAction.title, detail = payload.bodyAction.detail)
        ActionTile(title = payload.resetAction.title, detail = payload.resetAction.detail)

        if (payload.habits.isNotEmpty()) {
            Text(
                text = "Habits · ${payload.habitsRemaining} left",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            payload.habits.forEach { habit ->
                HabitRow(
                    habit = habit,
                    pending = state.pendingHabitId == habit.id,
                    onToggle = { onToggleHabit(habit.id) },
                )
            }
        }

        payload.warnings.forEach { BannerCard(text = it, error = false) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
            Text(if (state.loading) "Refreshing…" else "Refresh")
        }
    }
}

@Composable
private fun EmptyOrLoading(state: TodayUiState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.loading) {
            CircularProgressIndicator()
            Text("Loading today…")
        } else {
            Text(
                text = state.error ?: "No data yet. Add your dashboard URL and token in Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReadinessRow(readiness: Readiness) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Readiness", style = MaterialTheme.typography.labelLarge)
        Text(
            text = "${readiness.score} · ${readiness.band.name.lowercase()}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MainActionCard(action: MainAction) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Main action", style = MaterialTheme.typography.labelMedium)
            Text(action.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            action.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun FocusBlockCard(block: FocusBlock, inFlight: Boolean, onStartFocus: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Focus block", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${block.startLabel} – ${block.endLabel}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Button(onClick = onStartFocus, enabled = !inFlight) {
                if (inFlight) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Focus")
                }
            }
        }
    }
}

@Composable
private fun ActionTile(title: String, detail: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun HabitRow(habit: Habit, pending: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            habit.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (habit.doneToday) FontWeight.Normal else FontWeight.Medium,
        )
        IconButton(onClick = onToggle, enabled = !pending) {
            when {
                pending -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                habit.doneToday -> Icon(Icons.Filled.CheckCircle, contentDescription = "Done")
                else -> Icon(Icons.Outlined.Circle, contentDescription = "Mark done")
            }
        }
    }
}

@Composable
private fun BannerCard(text: String, error: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Previews (fake data, no ViewModel) ──────────────────────────────────────

@Preview(name = "Today — normal", showBackground = true)
@Composable
private fun PreviewToday() {
    DashboardTheme {
        TodayScreen(TodayUiState(payload = FakeData.today), {}, {}, {})
    }
}

@Preview(name = "Today — recovery", showBackground = true)
@Composable
private fun PreviewRecovery() {
    DashboardTheme {
        TodayScreen(TodayUiState(payload = FakeData.recoveryToday, fromCache = true), {}, {}, {})
    }
}

@Preview(name = "Today — empty", showBackground = true)
@Composable
private fun PreviewEmpty() {
    DashboardTheme {
        TodayScreen(TodayUiState(loading = false, payload = null), {}, {}, {})
    }
}
