package net.inkyquill.pocketeditor.load

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import net.inkyquill.pocketeditor.yandex.YandexDiskError

class TemporaryAvailabilityException(message: String) : IOException(message)

sealed interface LoadFailureDisposition {
    data class Retry(
        val category: ProgressiveLoadErrorCategory,
        val retryAt: Instant,
    ) : LoadFailureDisposition

    data object SignInRequired : LoadFailureDisposition

    data class ActionRequired(
        val category: ProgressiveLoadErrorCategory,
    ) : LoadFailureDisposition
}

object RetryAfterParser {
    fun parse(value: String?, now: Instant): Duration? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        text.toLongOrNull()?.takeIf { it >= 0 }?.let { return Duration.ofSeconds(it) }
        val instant = runCatching {
            ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull() ?: return null
        return Duration.between(now, instant).coerceAtLeast(Duration.ZERO)
    }
}

class ProgressiveLoadRetryPolicy(
    private val now: () -> Instant = Instant::now,
    private val jitterMillis: (Long) -> Long = { ceiling -> Random.nextLong(0, ceiling + 1) },
) {
    fun classify(failure: Throwable, attempt: Int): LoadFailureDisposition {
        require(attempt > 0)
        return when (failure) {
            is YandexDiskError.Unauthorized -> LoadFailureDisposition.SignInRequired
            is YandexDiskError.InvalidRemote -> LoadFailureDisposition.ActionRequired(
                ProgressiveLoadErrorCategory.INVALID_REMOTE,
            )
            is YandexDiskError.RateLimited -> retry(
                ProgressiveLoadErrorCategory.RATE_LIMITED,
                attempt,
                failure.retryAfterSeconds?.let(Duration::ofSeconds),
            )
            is YandexDiskError.ServerFailure -> retry(
                ProgressiveLoadErrorCategory.SERVER,
                attempt,
                failure.retryAfterSeconds?.let(Duration::ofSeconds),
            )
            is YandexDiskError.NotFound,
            is TemporaryAvailabilityException,
            -> retry(ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY, attempt)
            is SocketTimeoutException -> retry(ProgressiveLoadErrorCategory.TIMEOUT, attempt)
            is YandexDiskError.Offline,
            is IOException,
            -> retry(ProgressiveLoadErrorCategory.OFFLINE, attempt)
            else -> LoadFailureDisposition.ActionRequired(ProgressiveLoadErrorCategory.INVALID_REMOTE)
        }
    }

    private fun retry(
        category: ProgressiveLoadErrorCategory,
        attempt: Int,
        explicit: Duration? = null,
    ): LoadFailureDisposition.Retry {
        val exponentialSeconds = 10L
            .shl((attempt - 1).coerceAtMost(20))
            .coerceAtMost(MAX_BACKOFF.seconds)
        val base = explicit ?: Duration.ofSeconds(exponentialSeconds)
        val capped = base.coerceAtMost(MAX_BACKOFF)
        val jitter = if (explicit != null || capped.isZero) {
            0L
        } else {
            jitterMillis((capped.toMillis() / 5).coerceAtLeast(1))
        }
        return LoadFailureDisposition.Retry(category, now().plusMillis(capped.toMillis() + jitter))
    }

    private companion object {
        val MAX_BACKOFF: Duration = Duration.ofHours(6)
    }
}
