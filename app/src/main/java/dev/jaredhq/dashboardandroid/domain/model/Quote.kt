package dev.jaredhq.dashboardandroid.domain.model

/**
 * Lock-screen / widget quote. A SEPARATE endpoint from Today on purpose — a
 * slow knowledge-base read must never stall the Today view. Mirrors the
 * server's `WidgetQuote` contract (GET /api/widget/v1/quote).
 */
data class QuotePayload(
    val version: Int,
    val date: String,
    val text: String,
    val source: QuoteSource?,
)

data class QuoteSource(
    val title: String,
    val slug: String,
)
