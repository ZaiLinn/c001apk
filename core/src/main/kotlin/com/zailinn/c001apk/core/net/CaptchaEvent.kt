package com.zailinn.c001apk.core.net

import okhttp3.HttpUrl

/**
 * One captcha challenge met by an API request, for finding out what triggers
 * them. Holds no credentials, IDs or proofs: only the endpoint path, timing and
 * what happened.
 */
data class CaptchaEvent(
    val time: Long,
    val method: String,
    /** Endpoint path with long numbers replaced by `{id}`; no query. */
    val path: String,
    val signedIn: Boolean,
    /** Proof carried as a `validate` cookie (true) or a form field (false). */
    val cookieProtocol: Boolean,
    /**
     * `skipped` (the page falls back without asking), `paused`, `busy` (another
     * verification is open), `not-prompted`, `cancelled`, `incomplete`,
     * `submitted` or `rejected`.
     */
    val outcome: String,
    val stats: RequestStats?,
) {
    companion object {
        private val longNumber = Regex("/[0-9]{4,}(?=/|$)")

        fun pathOf(url: HttpUrl): String = url.encodedPath.replace(longNumber, "/{id}")
    }
}

/** API request rate when a request was sent, counted in this process. */
data class RequestStats(
    /** Since the previous API request was sent; null for the first one. */
    val sincePreviousMs: Long?,
    /** API requests sent in the minute up to and including this one. */
    val inLastMinute: Int,
    /** API requests sent since the app started, this one included. */
    val sinceStart: Int,
)

/** Counts API requests to describe the rate a challenge was met at. Thread-safe. */
internal class RequestRate(private val clock: () -> Long) {
    private val recent = ArrayDeque<Long>()
    private var total = 0
    private var previous: Long? = null

    @Synchronized
    fun sent(): RequestStats {
        val now = clock()
        while (recent.isNotEmpty() && now - recent.first() >= 60_000) recent.removeFirst()
        recent.addLast(now)
        total++
        val since = previous?.let { now - it }
        previous = now
        return RequestStats(since, recent.size, total)
    }
}
