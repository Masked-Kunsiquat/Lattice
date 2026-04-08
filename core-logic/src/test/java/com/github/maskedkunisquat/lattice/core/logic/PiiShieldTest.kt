package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PiiShieldTest {

    @Test
    fun testMask_WholeWordMatchOnly() {
        val personId = UUID.randomUUID()
        val people = listOf(
            Person(
                id = personId,
                firstName = "Jordan",
                lastName = null,
                nickname = null,
                relationshipType = RelationshipType.FRIEND
            )
        )

        val input = "I saw Jordan at the Jordanian embassy"
        val expected = "I saw [PERSON_$personId] at the Jordanian embassy"
        val result = PiiShield.mask(input, people)

        assertEquals(expected, result)
    }
}
