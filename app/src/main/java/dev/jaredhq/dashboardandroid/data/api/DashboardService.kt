package dev.jaredhq.dashboardandroid.data.api

import dev.jaredhq.dashboardandroid.data.api.dto.CaptureRequest
import dev.jaredhq.dashboardandroid.data.api.dto.CaptureResponseDto
import dev.jaredhq.dashboardandroid.data.api.dto.ChatRequest
import dev.jaredhq.dashboardandroid.data.api.dto.FocusStartRequest
import dev.jaredhq.dashboardandroid.data.api.dto.FocusStartResponseDto
import dev.jaredhq.dashboardandroid.data.api.dto.NotificationsPayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.QuotePayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.TodayPayloadDto
import dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthResponseDto
import dev.jaredhq.dashboardandroid.data.api.dto.WatchHealthUploadDto
import dev.jaredhq.dashboardandroid.data.api.dto.WatchSyncDto
import dev.jaredhq.dashboardandroid.data.api.dto.WatchSyncResponseDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit description of the dashboard's `/api/widget/v1` surface. The
 * `Authorization: Bearer <device-token>` header is added by an OkHttp
 * interceptor ([AuthInterceptor]) so it is attached uniformly and never appears
 * in this declaration (or in logs).
 *
 * Paths are relative to the configured base URL (the user's Tailscale HTTPS
 * dashboard origin), which must end in `/` for Retrofit to resolve them.
 */
interface DashboardService {

    @GET("api/widget/v1/today")
    suspend fun getToday(): TodayPayloadDto

    @GET("api/widget/v1/quote")
    suspend fun getQuote(): QuotePayloadDto

    @GET("api/widget/v1/notifications")
    suspend fun getNotifications(): NotificationsPayloadDto

    @POST("api/widget/v1/habits/{id}/toggle")
    suspend fun toggleHabit(@Path("id") habitId: Int): TodayPayloadDto

    @POST("api/widget/v1/focus/start")
    suspend fun startFocus(@Body body: FocusStartRequest): FocusStartResponseDto

    @POST("api/widget/v1/capture")
    suspend fun capture(@Body body: CaptureRequest): CaptureResponseDto

    @POST("api/widget/v1/chat")
    suspend fun chat(@Body body: ChatRequest): CaptureResponseDto

    @POST("api/widget/v1/watch/sync")
    suspend fun syncWatch(@Body body: WatchSyncDto): WatchSyncResponseDto

    @POST("api/widget/v1/watch/health")
    suspend fun uploadWatchHealth(@Body body: WatchHealthUploadDto): WatchHealthResponseDto
}
