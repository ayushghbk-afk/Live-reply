package com.liveaireply.app.personas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaRepositoryTest {

    @Test
    fun shipsWithTheBuiltInPersonasIncludingARoleplayExample() {
        val repo = InMemoryPersonaRepository()
        assertTrue(repo.all().any { it.label == "Friendly" })
        assertTrue(repo.all().any { it.isRoleplay })
        val kaelen = repo.byId("preset-kaelen")
        assertNotNull(kaelen)
        assertTrue(kaelen!!.rules.contains("Never break character."))
    }

    @Test
    fun savesAndSelectsCustomPersonas() {
        val repo = InMemoryPersonaRepository()
        val custom = Persona(
            id = "persona-mine",
            label = "Mine",
            personaName = "Rin",
            personality = "dry humour",
            speakingStyle = "very short"
        )
        repo.save(custom)
        repo.select("persona-mine")
        assertEquals("persona-mine", repo.selectedId())
        assertEquals("Rin", repo.byId("persona-mine")?.personaName)
    }

    @Test
    fun deletesCustomButNotBuiltInPersonas() {
        val repo = InMemoryPersonaRepository()
        repo.save(Persona(id = "persona-temp", label = "Temp"))
        repo.delete("persona-temp")
        assertEquals(null, repo.byId("persona-temp"))
        repo.delete(PersonaPresets.FRIENDLY.id)
        assertNotNull(repo.byId(PersonaPresets.FRIENDLY.id))
    }

    @Test
    fun selectingAnUnknownIdKeepsTheCurrentSelection() {
        val repo = InMemoryPersonaRepository()
        repo.select("does-not-exist")
        assertEquals(PersonaPresets.default().id, repo.selectedId())
    }

    @Test
    fun blankPersonasAreDetected() {
        assertTrue(Persona(id = "x", label = "x").isBlank)
        assertFalse(Persona(id = "x", label = "x", personality = "warm").isBlank)
    }
}
