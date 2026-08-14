package dev.rgarjoaba.claudestatus.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rgarjoaba.claudestatus.data.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenStoreTest {

    private lateinit var store: TokenStore

    @Before fun setUp() {
        store = TokenStore(InstrumentationRegistry.getInstrumentation().targetContext)
        Account.entries.forEach { store.clear(it) }
    }

    private val sample = TokenRecord("access-abc", "refresh-xyz", 111L, 222L)

    @Test fun roundTrips() {
        store.save(Account.PERSONAL, sample)
        assertEquals(sample, store.load(Account.PERSONAL))
    }

    @Test fun slotsAreIsolated() {
        store.save(Account.PERSONAL, sample)
        assertNull(store.load(Account.WORK))
    }

    @Test fun clearRemovesOnlyThatSlot() {
        store.save(Account.PERSONAL, sample)
        store.save(Account.WORK, sample.copy(accessToken = "other"))
        store.clear(Account.PERSONAL)
        assertNull(store.load(Account.PERSONAL))
        assertEquals("other", store.load(Account.WORK)?.accessToken)
    }

    @Test fun storedBlobIsNotPlaintext() {
        store.save(Account.PERSONAL, sample)
        val raw = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("tokens", 0).getString("personal", "")!!
        assert(!raw.contains("access-abc")) { "token stored in the clear" }
    }

    @Test fun corruptBlobReturnsNullRatherThanThrowing() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("tokens", 0).edit().putString("personal", "garbage").commit()
        assertNull(store.load(Account.PERSONAL))
    }
}
