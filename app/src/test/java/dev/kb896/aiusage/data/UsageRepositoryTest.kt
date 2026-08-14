package dev.kb896.aiusage.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers UsageRepository.healthFor — the failure-to-Health mapping that decides what
 * the user sees on the panel when a fetch degrades. This is a pure function (no
 * UsageApi/OAuthClient/TokenStore instance needed), which is why it lives on the
 * companion object rather than as an instance member: TokenStore's constructor needs
 * a real android.content.Context and Android Keystore access, neither available to a
 * plain JVM unit test, so UsageRepository itself cannot be instantiated here.
 *
 * `healthFor`'s `when` has no `else` branch and is used as an expression (its return
 * value is the function's result), so Kotlin's compiler already refuses to build this
 * module if UsageApi.Kind gains a fifth entry without a matching branch — verified by
 * temporarily deleting the SERVER branch and observing:
 *   "'when' expression must be exhaustive. Add the 'SERVER' branch or an 'else' branch."
 * (see task-11-report.md for the transcript). A runtime test asserting that exhaustiveness
 * would be redundant with a guarantee the compiler already enforces, so none is added here.
 *
 * Not covered here: the "no stored token" and "refresh-token expired" paths in
 * UsageRepository.fetchOne. Both require driving TokenStore.load()/a TokenRecord through
 * the real repository, which needs either a real Context (TokenStore's constructor) or an
 * injection seam (e.g. extracting a TokenSource interface) that does not exist yet and
 * that this task deliberately does not introduce speculatively. See task-11-report.md.
 */
class UsageRepositoryTest {

    @Test fun `unauthorized maps to auth`() {
        assertEquals(Health.AUTH, UsageRepository.healthFor(UsageApi.Kind.UNAUTHORIZED))
    }

    @Test fun `rate limited maps to stale`() {
        assertEquals(Health.STALE, UsageRepository.healthFor(UsageApi.Kind.RATE_LIMITED))
    }

    @Test fun `network maps to stale`() {
        assertEquals(Health.STALE, UsageRepository.healthFor(UsageApi.Kind.NETWORK))
    }

    @Test fun `server maps to error`() {
        assertEquals(Health.ERROR, UsageRepository.healthFor(UsageApi.Kind.SERVER))
    }
}
