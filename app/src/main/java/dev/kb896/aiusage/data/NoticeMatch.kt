package dev.kb896.aiusage.data

/**
 * "Match the front notifications": which of the back's cards stay, given the keys of the
 * notifications on the front now. A card stays while its notification is there; one saved
 * before cards carried a key (an empty key) is left alone, there being nothing to match.
 */
object NoticeMatch {
    fun keep(keys: List<String>, active: Set<String>): List<Boolean> =
        keys.map { it.isEmpty() || it in active }
}
