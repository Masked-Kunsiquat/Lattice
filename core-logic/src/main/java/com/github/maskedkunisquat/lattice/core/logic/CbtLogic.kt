package com.github.maskedkunisquat.lattice.core.logic

object CbtLogic {
    private val allOrNothingWords = setOf("always", "never", "everyone", "everybody", "nobody", "none", "everything", "nothing")
    private val catastrophizingWords = setOf("disaster", "catastrophe", "catastrophic", "ruined", "ruin", "destroyed", "hopeless", "unbearable", "terrible", "horrible", "awful", "worst", "devastating", "doomed", "irreparable", "end")

    private val rules: Map<String, (List<String>) -> Boolean> = mapOf(
        "All-or-Nothing" to { words -> words.any { it in allOrNothingWords } },
        "Catastrophizing" to { words -> words.any { it in catastrophizingWords } }
    )

    /**
     * Detects cognitive distortions in the given text.
     * Add new distortion types by extending the [rules] map.
     */
    fun detectDistortions(text: String): List<String> {
        val words = text.lowercase().split(Regex("\\s+"))
            .map { it.replace(Regex("'s$"), "").replace(Regex("[^a-z]"), "") }
        return buildList {
            rules.forEach { (name, matches) -> if (matches(words)) add(name) }
        }
    }
}
