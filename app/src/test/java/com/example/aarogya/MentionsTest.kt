package com.example.aarogya

import com.example.aarogya.data.FoodDb
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Pulling table foods out of SmolVLM's sentences (real outputs from the sample photos). */
class MentionsTest {
    private val db = File("src/main/assets/foods.csv").inputStream().use { FoodDb.load(it) }
    private fun names(text: String) = db.mentions(text).map { it.name }

    @Test fun findsFoodsInModelSentences() {
        assertEquals(listOf("rice", "chicken breast", "fish"), names("Rice, Chicken and Fish."))
        assertEquals(listOf("soup", "bread"), names("The food in this image is a bowl of soup, and two round breads."))
        assertEquals(listOf("nachos", "salsa"), names("The food in this image is a nachos with red onions and green salsa."))
        assertEquals(emptyList<String>(), names("Mexican food."))
    }

    @Test fun prefersLongestNameAndDoesNotDoubleCount() {
        assertEquals(listOf("chicken biryani"), names("a plate of chicken biryani"))
        assertEquals(listOf("black coffee", "bread"), names("black coffee and toast"))
    }
}
