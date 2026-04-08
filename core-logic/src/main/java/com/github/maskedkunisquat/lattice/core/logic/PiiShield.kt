package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.model.Person
import java.util.regex.Pattern

object PiiShield {

    /**
     * Masks names and nicknames in the given text with a person-specific placeholder.
     * Uses word boundaries to avoid partial-word matches.
     */
    fun mask(text: String, people: List<Person>): String {
        var maskedText = text
        
        // Collect all name variants and associate them with the person's ID.
        // We include full names, first names, last names, and nicknames.
        val namesToMask = people.flatMap { person ->
            val fullName = if (!person.lastName.isNullOrBlank()) "${person.firstName} ${person.lastName}" else null
            listOfNotNull(
                fullName?.let { it to person.id },
                person.firstName to person.id,
                person.lastName?.let { it to person.id },
                person.nickname?.let { it to person.id }
            )
        }.filter { it.first.isNotBlank() }
         .distinctBy { it.first } // Avoid redundant patterns for the same name string
         .sortedByDescending { it.first.length } // Match longer strings first (e.g., full names before first names)

        for ((name, id) in namesToMask) {
            // \b ensures we only match whole words
            val pattern = Pattern.compile("\\b${Pattern.quote(name)}\\b", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(maskedText)
            maskedText = matcher.replaceAll("[PERSON_$id]")
        }

        return maskedText
    }

    /**
     * Reverses the masking process, replacing placeholders with the person's nickname or first name
     * for display in the UI.
     */
    fun unmask(text: String, people: List<Person>): String {
        var unmaskedText = text
        for (person in people) {
            val placeholder = "[PERSON_${person.id}]"
            // Default to nickname for a more personal UI display, falling back to firstName.
            val displayName = person.nickname ?: person.firstName
            unmaskedText = unmaskedText.replace(placeholder, displayName)
        }
        return unmaskedText
    }
}
