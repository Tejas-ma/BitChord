package com.music.bitchord.ui.palette

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The palette's matcher: what it accepts, and in what order.
 *
 * The ordering is the part that matters — a palette that finds the right row but
 * ranks it twelfth is as good as one that did not find it.
 */
class CommandFuzzyTest {

    @Test
    fun `letters out of the middle of words still match, in order`() {
        assertNotNull(CommandFuzzy.score("thsng", "This Song"))
        assertNotNull(CommandFuzzy.score("nw", "Nothing Was The Same"))
        // Out of order is not a match.
        assertNull(CommandFuzzy.score("gn", "song"))
    }

    @Test
    fun `nothing matches when a letter is missing`() {
        assertNull(CommandFuzzy.score("zzz", "Bohemian Rhapsody"))
        assertNull(CommandFuzzy.score("qq", "Queen"))
    }

    @Test
    fun `an exact title beats a longer title that merely contains it`() {
        val exact = CommandFuzzy.score("song", "Song")
        val contained = CommandFuzzy.score("song", "A Very Long Song For You")
        assertNotNull(exact)
        assertNotNull(contained)
        assertTrue(exact > contained, "expected $exact > $contained")
    }

    @Test
    fun `matches at word starts beat matches inside a word`() {
        val wordStarts = CommandFuzzy.score("bs", "Blue Sky")
        val inside = CommandFuzzy.score("bs", "Obsession")
        assertNotNull(wordStarts)
        assertNotNull(inside)
        assertTrue(wordStarts > inside, "expected $wordStarts > $inside")
    }

    @Test
    fun `an empty query matches everything with no ranking`() {
        assertEquals(0, CommandFuzzy.score("", "Anything"))
        assertEquals(0, CommandFuzzy.score("   ", "Anything"))
    }

    @Test
    fun `punctuation and case in the title do not stop a match`() {
        assertNotNull(CommandFuzzy.score("dont", "Don't Stop Me Now"))
        assertNotNull(CommandFuzzy.score("Beyonce", "beyoncé - halo"))
    }

    @Test
    fun `best takes the strongest of the fields it is given`() {
        val byArtist = CommandFuzzy.best("arctic", "Do I Wanna Know?", "Arctic Monkeys")
        val byTitle = CommandFuzzy.best("arctic", "Arctic Monkeys", "Whatever People Say")
        assertNotNull(byArtist)
        assertNotNull(byTitle)
        // Both matched, and each on a different field: neither may be dropped.
        assertTrue(byArtist > 0 && byTitle > 0)
        assertNull(CommandFuzzy.best("zzzz", "Title", "Artist", null))
    }
}
