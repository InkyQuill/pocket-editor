package net.inkyquill.pocketeditor.load

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import net.inkyquill.pocketeditor.yandex.YandexDiskError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ProgressiveLoadRetryTest {
    private val now = Instant.parse("2026-08-15T10:00:00Z")
    private val policy = ProgressiveLoadRetryPolicy(
        now = { now },
        jitterMillis = { 0L },
    )

    @Test
    fun `Retry-After seconds and HTTP date are honored`() {
        assertEquals(Duration.ofSeconds(18), RetryAfterParser.parse("18", now))
        assertEquals(
            Duration.ofSeconds(30),
            RetryAfterParser.parse("Sat, 15 Aug 2026 10:00:30 GMT", now),
        )
        assertEquals(null, RetryAfterParser.parse("invalid", now))
    }

    @Test
    fun `transient attempts never become terminal and cap at six hours`() {
        val failure = YandexDiskError.ServerFailure(503, retryAfterSeconds = null)

        val attemptOne = policy.classify(failure, attempt = 1) as LoadFailureDisposition.Retry
        val attemptFifty = policy.classify(failure, attempt = 50) as LoadFailureDisposition.Retry

        assertEquals(Duration.ofSeconds(10), Duration.between(now, attemptOne.retryAt))
        assertEquals(Duration.ofHours(6), Duration.between(now, attemptFifty.retryAt))
    }

    @Test
    fun `authorization and invalid data are the only action dispositions`() {
        assertEquals(
            LoadFailureDisposition.SignInRequired,
            policy.classify(YandexDiskError.Unauthorized(), 1),
        )
        assertEquals(
            LoadFailureDisposition.ActionRequired(ProgressiveLoadErrorCategory.INVALID_REMOTE),
            policy.classify(YandexDiskError.InvalidRemote("bad binder"), 1),
        )
    }

    @Test
    fun `each transient failure records only its retry category`() {
        val cases = listOf(
            YandexDiskError.Offline(IOException("signed-url=https://secret.example")) to
                (ProgressiveLoadErrorCategory.OFFLINE to Duration.ofSeconds(10)),
            SocketTimeoutException("server body") to
                (ProgressiveLoadErrorCategory.TIMEOUT to Duration.ofSeconds(10)),
            YandexDiskError.RateLimited(18) to
                (ProgressiveLoadErrorCategory.RATE_LIMITED to Duration.ofSeconds(18)),
            YandexDiskError.ServerFailure(503, 22) to
                (ProgressiveLoadErrorCategory.SERVER to Duration.ofSeconds(22)),
            YandexDiskError.NotFound() to
                (ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY to Duration.ofSeconds(10)),
            TemporaryAvailabilityException("not visible yet") to
                (ProgressiveLoadErrorCategory.TEMPORARY_AVAILABILITY to Duration.ofSeconds(10)),
        )

        cases.forEach { (failure, expected) ->
            val disposition = policy.classify(failure, 1) as LoadFailureDisposition.Retry

            assertEquals(expected.first, disposition.category)
            assertEquals(expected.second, Duration.between(now, disposition.retryAt))
            assertFalse(disposition.toString().contains("secret.example"))
            assertFalse(disposition.toString().contains("server body"))
        }
    }

    @Test
    fun `explicit Retry-After is honored without jitter and capped at six hours`() {
        val short = policy.classify(YandexDiskError.RateLimited(18), 1) as LoadFailureDisposition.Retry
        val long = policy.classify(YandexDiskError.ServerFailure(503, 86_400), 1) as LoadFailureDisposition.Retry

        assertEquals(Duration.ofSeconds(18), Duration.between(now, short.retryAt))
        assertEquals(Duration.ofHours(6), Duration.between(now, long.retryAt))
    }
}
