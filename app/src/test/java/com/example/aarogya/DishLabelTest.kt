package com.example.aarogya

import com.example.aarogya.data.FoodDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Labels the AIY food_V1 photo model can emit must land on a row in foods.csv. */
class DishLabelTest {
    private val db = File("src/main/assets/foods.csv").inputStream().use { FoodDb.load(it) }

    private val indian = listOf(
        "Mutton curry", "Mughlai paratha", "Idli", "Thalipeeth", "Chole bhature", "Kosambari", "Samosa", "Chicken curry",
        "Rogan josh", "Pilaf", "Sambar", "Butter chicken", "Dal makhani", "Tandoori chicken", "Rajma", "Shahi paneer",
        "Kulfi", "Pakora", "Upma", "Aloo gobi", "Gulab jamun", "Raita", "Dahi vada", "Jalebi", "Chhena jalebi",
        "Chhena kheeri", "Sarson da saag", "Kheer", "Falooda", "Rasgulla", "Shrikhand", "Puri", "Hyderabadi biryani",
        "Dhokla", "Barfi", "Palak paneer", "Kofta", "Chana masala", "Idiyappam", "Puttu", "Dal bhat", "Dum Aloo",
        "Shami kebab", "Bhaji", "Aloo paratha", "Khichdi", "Paneer tikka",
    )
    private val mexican = listOf(
        "Breakfast burrito", "Churro", "Burrito", "Fajita", "Chorizo", "Nachos", "Flan", "Huevos motuleños", "Quesadilla",
        "Empanada", "Elote", "Sope", "Frijoles charros", "Pozole", "Menudo", "Chile relleno", "Chimichanga", "Huarache",
        "Tres leches cake", "Carne asada", "Cochinita pibil", "Chalupa", "Barbacoa", "Gordita", "Huevos rancheros", "Torta",
        "Huevos divorciados", "Mission burrito", "Taco", "Al pastor", "Taquito", "Arroz con pollo", "Carnitas", "Tlayuda",
        "Birria", "Enchilada", "Pambazo", "Queso flameado", "Carne asada fries", "Tamale", "Ceviche", "Pico de gallo",
        "Tostada", "Chilaquiles", "Pan dulce",
    )

    @Test fun everyIndianAndMexicanPhotoLabelHasCalories() {
        val missing = (indian + mexican).filter { db.match(it) == null }
        assertTrue("No calorie row for: $missing", missing.isEmpty())
    }

    @Test fun lookalikeLabelsPickTheSpecificDish() {
        mapOf(
            "Dahi vada" to "dahi vada", "Dal bhat" to "dal bhat", "Chhena jalebi" to "jalebi", "Kofta" to "malai kofta",
            "Pilaf" to "veg biryani", "Hyderabadi biryani" to "chicken biryani", "Breakfast burrito" to "breakfast burrito",
            "Mission burrito" to "burrito", "Huevos motuleños" to "huevos rancheros", "Al pastor" to "tacos al pastor",
            "Carne asada fries" to "carne asada fries", "Queso flameado" to "queso dip", "Frijoles charros" to "charro beans",
        ).forEach { (label, food) -> assertEquals(label, food, db.match(label)?.name) }
    }

    @Test fun noNameOrAliasBelongsToTwoFoods() {
        val owners = db.foods.flatMap { f -> (listOf(f.name) + f.aliases).map { FoodDb.normalize(it) to f.name } }
            .groupBy({ it.first }, { it.second }).filterValues { it.toSet().size > 1 }
        assertTrue("Ambiguous keys: $owners", owners.isEmpty())
    }
}
