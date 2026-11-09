package dev.kb896.aiusage.rear

/**
 * The back's own lock: while the phone is locked, what the back would otherwise open to anybody
 * holding it — other apps through the portal — asks for the phone's own lock code first. Pure, so
 * the rules are tested on the JVM; the shell feeds it and LockCheck asks the system.
 *
 * WHY. With the front locked, the back still opened any pinned app, Resume included: a phone left
 * on a table gave its apps to whoever turned it over (the owner, 2026-11-08). The code is the
 * phone's own (LockCheck: the system checks it; nothing here keeps it), and a wrong one counts
 * against the phone's own attempts, as one on the front does.
 *
 * Locked when the phone has a secure lock, the phone is locked, and the back has not been unlocked
 * since it last went dark or the phone last locked. Unlocking the front unlocks the back too (the
 * phone is not locked then). Not thread-safe: the shell holds it under a lock of its own.
 */
class BackLock {

    /** The phone's lock, as `dumpsys lock_settings` names it for the user. */
    enum class Kind { NONE, PIN, PATTERN, PASSWORD }

    /** The back unlocked with the code since it last went dark or the phone last locked. */
    var unlockedHere = false
        private set

    private var phoneWasLocked: Boolean? = null

    /** Whether the back is locked now, for a phone whose lock is [kind] and is [phoneLocked]. */
    fun locked(kind: Kind?, phoneLocked: Boolean): Boolean {
        seen(phoneLocked)
        return kind != Kind.NONE && phoneLocked && !unlockedHere
    }

    /** The phone's locked state as last read: going from unlocked to locked locks the back. */
    fun seen(phoneLocked: Boolean) {
        if (phoneLocked && phoneWasLocked == false) unlockedHere = false
        phoneWasLocked = phoneLocked
    }

    /** The right code, given on the back. */
    fun unlocked() {
        unlockedHere = true
    }

    /** The back went dark: the next look at it asks for the code again. */
    fun dark() {
        unlockedHere = false
    }

    /** A code being typed: digits, a pattern's cells or a password, as LockCheck takes it. */
    class Entry(private val max: Int = MAX) {
        private val chars = StringBuilder()
        val length: Int get() = chars.length
        val text: String get() = chars.toString()
        fun add(c: Char): Boolean {
            if (chars.length >= max) return false
            chars.append(c)
            return true
        }
        fun back() { if (chars.isNotEmpty()) chars.setLength(chars.length - 1) }
        fun clear() { chars.setLength(0) }
    }

    companion object {
        /** No lock code is longer than this (Android caps passwords at 16 by default; leaves room). */
        const val MAX = 64
        /** A pattern needs at least four cells (LockPatternUtils.MIN_LOCK_PATTERN_SIZE). */
        const val PATTERN_MIN = 4

        /**
         * The lock of [user] from `dumpsys lock_settings`: its "User N" block's CredentialType.
         * Null where it says nothing readable (the caller keeps what it had).
         */
        fun kindOf(dump: String, user: Int = 0): Kind? {
            var inUser = false
            for (raw in dump.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("User ") && raw.startsWith("  ") && !raw.startsWith("    ")) {
                    inUser = line.removePrefix("User ").trim().takeWhile { it.isDigit() } == user.toString()
                    continue
                }
                if (inUser && line.startsWith("CredentialType:")) {
                    return when (line.substringAfter(':').trim().uppercase()) {
                        "PIN" -> Kind.PIN
                        "PATTERN" -> Kind.PATTERN
                        "PASSWORD", "PASSWORD_OR_PIN" -> Kind.PASSWORD
                        "NONE" -> Kind.NONE
                        else -> null
                    }
                }
            }
            return null
        }
    }
}
