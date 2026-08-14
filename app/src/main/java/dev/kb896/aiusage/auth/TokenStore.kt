package dev.kb896.aiusage.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.kb896.aiusage.data.Account
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES/GCM via an Android Keystore key; ciphertext lives in plain SharedPreferences. */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    fun save(account: Account, record: TokenRecord) {
        val json = buildJsonObject {
            put("access", record.accessToken)
            put("refresh", record.refreshToken)
            put("expiresAt", record.expiresAt)
            put("refreshExpiresAt", record.refreshTokenExpiresAt)
        }.toString()
        prefs.edit().putString(account.slug, encrypt(json)).apply()
    }

    fun load(account: Account): TokenRecord? {
        val blob = prefs.getString(account.slug, null) ?: return null
        val json = try { decrypt(blob) } catch (_: Exception) { return null }
        return try {
            val o = Json.parseToJsonElement(json) as? JsonObject ?: return null
            TokenRecord(
                accessToken = (o["access"] as JsonPrimitive).content,
                refreshToken = (o["refresh"] as JsonPrimitive).content,
                expiresAt = (o["expiresAt"] as JsonPrimitive).long,
                refreshTokenExpiresAt = (o["refreshExpiresAt"] as JsonPrimitive).long,
            )
        } catch (_: Exception) { null }
    }

    fun clear(account: Account) = prefs.edit().remove(account.slug).apply()

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val (ivB64, ctB64) = blob.split(":", limit = 2)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(ct))
    }

    // Synchronized: KeyGenParameterSpec.Builder(ALIAS, ...) *overwrites* any existing key under
    // that alias rather than reusing it. Without a lock, two callers racing on the very first
    // save()/load() (plausible here — two accounts can be touched around the same time) can both
    // see the entry absent and both generate a key, with the second generateKey() silently
    // replacing the key material the first just encrypted under. That ciphertext then becomes
    // permanently undecryptable — load() returns null via the caught AEADBadTagException, with
    // no error surfaced anywhere. The check-then-act here must not be separable.
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "claude_status_tokens"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
