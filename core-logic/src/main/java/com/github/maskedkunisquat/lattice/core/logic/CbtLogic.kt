package com.github.maskedkunisquat.lattice.core.logic

object CbtLogic {
    private val allOrNothingWords = listOf("always", "never", "everyone", "everybody", "nobody", "none", "everything", "nothing")

    /**
     * Detects cognitive distortions in the given text.
     * Currently supports 'All-or-Nothing' thinking.
     */
    fun detectDistortions(text: String): List<String> {
        val distortions = mutableListOf<String>()
        
        val words = text.lowercase().split(Regex("\\s+")).map { it.trim(',', '.', '!', '?', ';', ':') }
        
        if (words.any { it in allOrNothingWords }) {
            distortions.add("All-or-Nothing")
        }
        
        return distortions
    }
}
