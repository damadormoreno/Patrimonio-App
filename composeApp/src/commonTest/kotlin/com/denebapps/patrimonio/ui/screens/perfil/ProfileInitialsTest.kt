package com.denebapps.patrimonio.ui.screens.perfil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileInitialsTest {
    @Test
    fun `both names produce two uppercase initials`() {
        assertEquals("AG", profileInitials("Ana", "Gil"))
    }

    @Test
    fun `lowercase names are uppercased`() {
        assertEquals("AG", profileInitials("ana", "gil"))
    }

    @Test
    fun `only first name produces a single initial`() {
        assertEquals("A", profileInitials("Ana", ""))
    }

    @Test
    fun `only last name produces a single initial`() {
        assertEquals("G", profileInitials("", "Gil"))
    }

    @Test
    fun `both names empty produce null for the neutral avatar`() {
        assertNull(profileInitials("", ""))
    }

    @Test
    fun `blank names are trimmed and produce null`() {
        assertNull(profileInitials("   ", "  "))
    }

    @Test
    fun `padding around real names is ignored`() {
        assertEquals("AG", profileInitials("  Ana ", " Gil  "))
    }
}
