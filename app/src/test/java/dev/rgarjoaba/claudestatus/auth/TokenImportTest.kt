package dev.rgarjoaba.claudestatus.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenImportTest {

    /** The exact shape the macOS keychain stores. */
    private val keychainShape = """
    {"claudeAiOauth":{"accessToken":"acc-1","refreshToken":"ref-1",
     "expiresAt":1786720213529,"refreshTokenExpiresAt":1788564340529,
     "scopes":["user:profile"],"subscriptionType":"max"}}
    """.trimIndent()

    @Test fun `reads the keychain blob verbatim`() {
        val r = TokenImport.parse(keychainShape)!!
        assertEquals("acc-1", r.accessToken)
        assertEquals("ref-1", r.refreshToken)
        assertEquals(1786720213529L, r.expiresAt)
        assertEquals(1788564340529L, r.refreshTokenExpiresAt)
    }

    @Test fun `accepts an already-unwrapped object`() {
        val r = TokenImport.parse("""{"accessToken":"a","refreshToken":"b",
            "expiresAt":1,"refreshTokenExpiresAt":2}""")!!
        assertEquals("a", r.accessToken)
        assertEquals(2L, r.refreshTokenExpiresAt)
    }

    @Test fun `rejects a blob with no refresh token`() {
        assertNull(TokenImport.parse("""{"claudeAiOauth":{"accessToken":"a","expiresAt":1}}"""))
    }

    @Test fun `rejects malformed json`() {
        assertNull(TokenImport.parse("not json"))
        assertNull(TokenImport.parse(""))
    }

    @Test fun `rejects blank token values`() {
        assertNull(TokenImport.parse("""{"accessToken":"","refreshToken":"","expiresAt":1,
            "refreshTokenExpiresAt":2}"""))
    }
}
