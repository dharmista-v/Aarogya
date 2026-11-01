package com.example.aarogya

import com.example.aarogya.data.QtyUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class QtyUnitTest {
    @Test fun sameFoodInDifferentUnits() {
        val grams = 100.0 // one taco
        assertEquals(1.0, grams / QtyUnit.SERVING.gramsPer(100.0)!!, 1e-9)
        assertEquals(3.527, grams / QtyUnit.OZ.gramsPer(100.0)!!, 1e-3)
        assertEquals(0.2205, grams / QtyUnit.LB.gramsPer(100.0)!!, 1e-4)
        assertEquals(0.4167, grams / QtyUnit.CUP.gramsPer(100.0)!!, 1e-4) // 240 ml cup
        assertEquals(240.0, 1 * QtyUnit.CUP.gramsPer(null)!!, 0.0)
        assertEquals(29.5735, QtyUnit.FL_OZ.gramsPer(null)!!, 1e-4)
    }

    @Test fun unitsOfferedDependOnWhatsKnown() {
        assertEquals(listOf(QtyUnit.SERVING), QtyUnit.available(hasServing = true, hasGrams = false))
        assertEquals(QtyUnit.entries.size, QtyUnit.available(hasServing = true, hasGrams = true).size)
        assertEquals(QtyUnit.entries.size - 1, QtyUnit.available(hasServing = false, hasGrams = true).size)
    }

    @Test fun namesAndFormatting() {
        assertEquals("tacos", QtyUnit.SERVING.name(2.0, "taco"))
        assertEquals("fillet", QtyUnit.SERVING.name(1.0, "fillet"))
        assertEquals("cups", QtyUnit.CUP.name(1.5, null))
        assertEquals("oz", QtyUnit.OZ.name(3.5, null))
        assertEquals("150", QtyUnit.format(150.4, QtyUnit.G))
        assertEquals("3.53", QtyUnit.format(3.52739, QtyUnit.OZ))
        assertEquals("1.5", QtyUnit.format(1.5, QtyUnit.SERVING))
        assertEquals("2", QtyUnit.format(2.0, QtyUnit.CUP))
    }

    @Test fun typedUnitsMapToPickerUnits() {
        assertEquals(QtyUnit.G, QtyUnit.fromParsed("g"))
        assertEquals(QtyUnit.CUP, QtyUnit.fromParsed("cup"))
        assertEquals(null, QtyUnit.fromParsed("piece"))
    }
}
