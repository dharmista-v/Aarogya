package com.example.aarogya

import com.example.aarogya.data.FoodCategories
import com.example.aarogya.data.FoodCategory
import com.example.aarogya.data.FoodDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoodCategoriesTest {
    private fun cat(name: String) = FoodCategories.categorize(name)

    @Test fun commonFoodsAndGeminiNames() {
        assertEquals(FoodCategory.GRAINS, cat("Roti"))
        assertEquals(FoodCategory.GRAINS, cat("Aloo paratha"))
        assertEquals(FoodCategory.GRAINS, cat("Masala dosa"))
        assertEquals(FoodCategory.LEGUMES, cat("Dal tadka"))
        assertEquals(FoodCategory.LEGUMES, cat("Chole"))
        assertEquals(FoodCategory.MEAT, cat("Chicken biryani"))      // meat beats rice
        assertEquals(FoodCategory.MEAT, cat("Butter chicken"))       // meat beats dairy
        assertEquals(FoodCategory.VEG_CURRIES, cat("Palak paneer"))
        assertEquals(FoodCategory.DRINKS, cat("Banana milkshake"))   // drink beats fruit and dairy
        assertEquals(FoodCategory.DRINKS, cat("Masala chai"))
        assertEquals(FoodCategory.SWEETS, cat("Rice pudding"))       // sweet beats rice
        assertEquals(FoodCategory.SWEETS, cat("Gulab jamun"))
        assertEquals(FoodCategory.SNACKS, cat("Samosa"))
        assertEquals(FoodCategory.FAST_FOOD, cat("Al pastor taco"))
        assertEquals(FoodCategory.FAST_FOOD, cat("Veg Thali"))
        assertEquals(FoodCategory.DAIRY, cat("Curd"))
        assertEquals(FoodCategory.FRUITS, cat("Mango"))
        assertEquals(FoodCategory.VEGETABLES, cat("Green salad"))
        assertEquals(FoodCategory.OTHER, cat("Xqzv blorp"))
    }

    @Test fun noFalseSubstringMatches() {
        assertEquals(FoodCategory.MEAT, cat("Steak"))   // "tea" inside "steak" must not make it a drink
        assertEquals(FoodCategory.OTHER, cat("Update"))  // "date" inside "update" is not a fruit
    }

    @Test fun nearlyAllTableFoodsGetAType() {
        val db = File("src/main/assets/foods.csv").inputStream().use { FoodDb.load(it) }
        val other = db.foods.filter { cat(it.name) == FoodCategory.OTHER }.map { it.name }
        assertTrue("Uncategorised table foods: $other", other.size <= db.foods.size / 20) // at most 5%
    }
}
