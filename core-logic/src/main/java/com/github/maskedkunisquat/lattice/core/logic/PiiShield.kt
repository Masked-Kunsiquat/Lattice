package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import java.util.regex.Pattern

object PiiShield {

    /**
     * Masks person names and place names in [text] with their respective placeholders.
     * Person names → `[PERSON_uuid]`, place names → `[PLACE_uuid]`.
     * Uses word boundaries to avoid partial-word matches. Longer strings are matched first
     * to prevent shorter tokens from shadowing multi-word variants.
     */
    fun mask(text: String, people: List<Person>, places: List<Place> = emptyList()): String {
        var maskedText = text

        // Person masking
        val namesToMask = people.flatMap { person ->
            val fullName = if (!person.lastName.isNullOrBlank()) "${person.firstName} ${person.lastName}" else null
            listOfNotNull(
                fullName?.let { it to person.id },
                person.firstName to person.id,
                person.lastName?.let { it to person.id },
                person.nickname?.let { it to person.id }
            )
        }.filter { it.first.isNotBlank() }
         .distinctBy { it.first }
         .sortedByDescending { it.first.length }

        for ((name, id) in namesToMask) {
            val pattern = Pattern.compile("\\b${Pattern.quote(name)}\\b", Pattern.CASE_INSENSITIVE)
            maskedText = pattern.matcher(maskedText).replaceAll("[PERSON_$id]")
        }

        // Place masking — same strategy, longest-first
        val placesToMask = places
            .filter { it.name.isNotBlank() }
            .sortedByDescending { it.name.length }

        for (place in placesToMask) {
            val pattern = Pattern.compile("\\b${Pattern.quote(place.name)}\\b", Pattern.CASE_INSENSITIVE)
            maskedText = pattern.matcher(maskedText).replaceAll("[PLACE_${place.id}]")
        }

        return maskedText
    }

    /**
     * Reverses masking, replacing `[PERSON_uuid]` and `[PLACE_uuid]` placeholders with
     * their display names. Used for UI display only — never before inference.
     */
    fun unmask(text: String, people: List<Person>, places: List<Place> = emptyList()): String {
        var unmaskedText = text

        for (person in people) {
            unmaskedText = unmaskedText.replace(
                "[PERSON_${person.id}]",
                person.nickname ?: person.firstName,
            )
        }

        for (place in places) {
            unmaskedText = unmaskedText.replace("[PLACE_${place.id}]", place.name)
        }

        return unmaskedText
    }
}
