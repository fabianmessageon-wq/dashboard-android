package dev.jaredhq.dashboardandroid.data.api

import dev.jaredhq.dashboardandroid.data.api.dto.CaptureRequest
import dev.jaredhq.dashboardandroid.data.api.dto.ChatRequest
import dev.jaredhq.dashboardandroid.data.api.dto.FocusStartRequest
import dev.jaredhq.dashboardandroid.data.api.dto.toDomain
import dev.jaredhq.dashboardandroid.domain.model.CaptureResult
import dev.jaredhq.dashboardandroid.domain.model.QuotePayload
import dev.jaredhq.dashboardandroid.domain.model.TodayPayload
import retrofit2.HttpException

/**
 * Live client: calls the dashboard over HTTPS via [DashboardService] and maps
 * the wire DTOs to domain models. Translates transport failures into the
 * client-neutral [ApiException] so callers never import Retrofit/OkHttp.
 *
 * Construction (base URL + auth header + JSON) lives in [ApiClientFactory] so
 * this class stays a thin DTO→domain adapter.
 */
class RetrofitDashboardApiClient(
    private val service: DashboardService,
) : DashboardApiClient {

    override suspend fun getToday(): TodayPayload =
        call { service.getToday().toDomain() }

    override suspend fun getQuote(): QuotePayload =
        call { service.getQuote().toDomain() }

    override suspend fun toggleHabit(habitId: Int): TodayPayload =
        call { service.toggleHabit(habitId).toDomain() }

    override suspend fun startFocus(taskId: Int?, durationMinutes: Int?): TodayPayload =
        call {
            service.startFocus(
                FocusStartRequest(taskId = taskId, durationMinutes = durationMinutes),
            ).toDomain()
        }

    override suspend fun capture(title: String): TodayPayload =
        call { service.capture(CaptureRequest(title = title)).toDomain() }

    override suspend fun chat(message: String): CaptureResult =
        call { service.chat(ChatRequest(message = message)).toDomain() }

    /** Run a service call, normalizing every failure into [ApiException]. */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: HttpException) {
        throw ApiException(e.code(), httpMessage(e.code()), e)
    } catch (e: Exception) {
        // IOException (no network/DNS/timeout), serialization errors, etc.
        throw ApiException(0, e.message ?: "Network error", e)
    }

    private fun httpMessage(code: Int): String = when (code) {
        401 -> "Unauthorized — check the device token in Settings."
        403 -> "This token lacks the required scope (needs \"actions\")."
        404 -> "Not found."
        in 500..599 -> "Dashboard server error ($code)."
        else -> "Request failed ($code)."
    }
}
