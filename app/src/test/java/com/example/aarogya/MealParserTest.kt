package com.example.aarogya

import com.example.aarogya.data.FoodDb
import com.example.aarogya.data.MealParser
import com.example.aarogya.data.ParsedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MealParserTest {
    private val db = File("src/main/assets/foods.csv").inputStream().use { FoodDb.load(it) }

    @Test fun parsesEverydayPhrasing() {
        assertEquals(
            listOf(ParsedItem("roti", 2.0, ""), ParsedItem("dal", 1.0, "bowl")),
            MealParser.parseRules("2 rotis and a bowl of dal").map { it.copy(phrase = FoodDb.normalize(it.phrase)) },
        )
        assertEquals(ParsedItem("chicken breast", 200.0, "g"), MealParser.parseRules("200g chicken breast").single())
        assertEquals(ParsedItem("rice", 0.5, "cup"), MealParser.parseRules("1/2 cup rice").single())
        assertEquals(ParsedItem("pizza", 2.0, "slice"), MealParser.parseRules("pizza 2 slices").single())
        assertEquals(ParsedItem("milk", 1.5, "glass"), MealParser.parseRules("1½ glasses of milk").single())
        assertEquals(3, MealParser.parseRules("I had poha, chai & a banana for breakfast").size)
    }

    @Test fun matchesFoodsAndAliases() {
        assertEquals("roti", db.match("rotis")?.name)
        assertEquals("roti", db.match("chapatti")?.name) // typo
        assertEquals("egg", db.match("boiled eggs")?.name)
        assertEquals("chicken biryani", db.match("biryani")?.name)
        assertEquals("coffee", db.match("cafe latte")?.name)
        assertEquals("black coffee", db.match("black coffee")?.name)
        assertNull(db.match("xyzzy plutonium"))
    }

    @Test fun genericPhotoLabelsAreNotMistakenForFood() {
        // ML Kit's generic labeler returns things like these for food photos; none may become a food.
        listOf("Flower", "Plant", "Petal", "Leaf", "Tableware", "Cuisine", "Dish", "Sky", "Table", "Hand", "Fun", "Pattern").forEach {
            assertNull(it, db.match(it))
        }
        // Real foods it can return still map.
        assertEquals("bread", db.match("Bread")?.name)
        assertEquals("pizza", db.match("Pizza")?.name)
    }

    @Test fun convertsUnitsToGrams() {
        val roti = db.match("roti")!!
        val dal = db.match("dal")!!
        assertEquals(80.0, MealParser.grams(roti, 2.0, ""), 0.01)
        assertEquals(200.0, MealParser.grams(dal, 1.0, "bowl"), 0.01) // dal's own bowl serving
        assertEquals(150.0, MealParser.grams(roti, 150.0, "g"), 0.01)
        assertEquals(250.0, MealParser.grams(null, 1.0, "glass"), 0.01)
    }

    @Test fun readsLlmOutput() {
        assertEquals(
            listOf(ParsedItem("eggs", 2.0, "piece"), ParsedItem("toast", 1.0, "slice"), ParsedItem("coke", 1.0, "can")),
            MealParser.parseLlmOutput("eggs; 2; piece\ntoast; 1; slices\n- coke | 1 | can\n"),
        )
    }

    @Test fun everyTableRowIsSane() {
        assert(db.foods.size > 150)
        db.foods.forEach { f ->
            assert(f.kcalPer100g in 0.0..900.0) { f.name }
            assert(f.servingGrams > 0) { f.name }
        }
    }
}
