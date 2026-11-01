package com.example.aarogya

import com.example.aarogya.data.Portions
import com.example.aarogya.data.Portions.Parsed
import org.junit.Assert.assertEquals
import org.junit.Test

class PortionsTest {
    @Test fun parsesGeminiPortions() {
        assertEquals(Parsed(2.0, "roti"), Portions.parse("2 rotis"))
        assertEquals(Parsed(1.0, "katori"), Portions.parse("1 katori"))
        assertEquals(Parsed(0.5, "cup"), Portions.parse("1/2 cup"))
        assertEquals(Parsed(1.0, "bowl"), Portions.parse("a bowl"))
        assertEquals(Parsed(8.0, "piece"), Portions.parse("8 pieces"))
        assertEquals(Parsed(1.0, "glass"), Portions.parse("1 glass"))
        assertEquals(Parsed(1.0, "large serving in cast iron pan"), Portions.parse("1 large serving in cast iron pan"))
        assertEquals(Parsed(1.0, "serving"), Portions.parse(""))
        assertEquals(Parsed(1.0, "plate"), Portions.parse("plate")) // no number: one of it
    }

    @Test fun labelsWithPlurals() {
        assertEquals("1 taco", Portions.label(1.0, "taco"))
        assertEquals("2 tacos", Portions.label(2.0, "taco"))
        assertEquals("1.5 cups", Portions.label(1.5, "cup"))
        assertEquals("2 glasses", Portions.label(2.0, "glass"))
        assertEquals("3 idlis", Portions.label(3.0, "idli"))
        assertEquals("2 fillets", Portions.label(2.0, "fillet"))
        assertEquals("2 large servings in cast iron pan", Portions.label(2.0, "large serving in cast iron pan"))
        assertEquals("0.5 bowl", Portions.label(0.5, "bowl"))
    }
}
