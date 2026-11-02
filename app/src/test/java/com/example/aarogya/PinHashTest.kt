package com.example.aarogya

import com.example.aarogya.data.PinHash
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    @Test fun verifiesOnlyTheRightPin() {
        val salt = PinHash.newSalt()
        val stored = PinHash.hash("4821", salt)
        assertTrue(PinHash.matches("4821", salt, stored))
        assertFalse(PinHash.matches("4822", salt, stored))
        assertFalse(PinHash.matches("", salt, stored))
    }

    @Test fun saltMakesSamePinHashDifferently() {
        assertNotEquals(PinHash.hash("1234", PinHash.newSalt()).toList(), PinHash.hash("1234", PinHash.newSalt()).toList())
    }
}
