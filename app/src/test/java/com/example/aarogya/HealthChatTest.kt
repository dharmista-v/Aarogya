package com.example.aarogya

import com.example.aarogya.data.DayTotals
import com.example.aarogya.data.FoodCategory
import com.example.aarogya.data.FoodDb
import com.example.aarogya.data.FoodEntry
import com.example.aarogya.data.HealthChat
import com.example.aarogya.data.HealthSnapshot
import com.example.aarogya.data.Meal
import com.example.aarogya.data.Store
import com.example.aarogya.data.WeightEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class HealthChatTest {
    private val today = LocalDate.of(2026, 11, 1)
    private val table = File("src/main/assets/foods.csv").inputStream().use { FoodDb.load(it) }.foods
    private fun food(name: String, kcal: Double, meal: Meal) = FoodEntry(day = today, time = 0, meal = meal, name = name, grams = null, kcal = kcal, photo = null, source = "manual")

    private fun snapshot(weights: List<WeightEntry>? = listOf(WeightEntry(0, today.minusDays(2), 72.4, "Scale"), WeightEntry(0, today.minusDays(35), 73.2, "Scale"))) = HealthSnapshot(
        today = today, goal = 2250,
        todayFoods = listOf(food("Poha", 300.0, Meal.BREAKFAST), food("Dal", 232.0, Meal.LUNCH), food("Roti", 238.0, Meal.LUNCH)),
        todayIn = 770.0, todayOut = 2100.0, burnSource = "WHOOP",
        recent = (6 downTo 0).map { DayTotals(today.minusDays(it.toLong()), 1950.0, 0.0, whoopKcal = 2250.0) },
        restingBurn = 0, overall = null,
        topFoods = listOf(Store.FoodStat("Roti", 12, 1420.0), Store.FoodStat("Dal", 5, 1160.0), Store.FoodStat("Chai", 12, 900.0)),
        byCategory = listOf(FoodCategory.GRAINS to 3800.0, FoodCategory.LEGUMES to 1800.0, FoodCategory.DRINKS to 1100.0),
        weights = weights, foodTable = table,
    )

    @Test fun answersCommonQuestionsExactly() {
        val s = snapshot()
        assertEquals(
            "Today you've eaten 770 kcal of your 2,250 kcal goal (1,480 left). You've burned 2,100 kcal (WHOOP), so you're at a deficit of 1,330 kcal.",
            HealthChat.answer("How am I doing today?", s),
        )
        assertTrue(HealthChat.answer("Am I in a calorie deficit this week?", s)!!.contains("deficit of 300 kcal/day"))
        assertTrue(HealthChat.answer("What is my biggest source of calories?", s)!!.startsWith("Your biggest calorie sources overall are Roti (1,420 kcal over 12 times)"))
        assertEquals("Your latest weight is 72.4 kg (30 Oct). You're down 0.8 kg since 27 Sep.", HealthChat.answer("How much weight have I lost?", s))
        assertTrue(HealthChat.answer("how many calories do I have left?", s)!!.startsWith("You have 1,480 kcal left"))
        assertTrue(HealthChat.answer("what did I eat today", s)!!.contains("Lunch — Dal 232 kcal, Roti 238 kcal"))
    }

    @Test fun dinnerIdeasComeFromUsualFoodsThatFit() {
        val a = HealthChat.answer("What should I eat for dinner?", snapshot())!!
        assertEquals("You have 1,480 kcal left today. From foods you usually eat, these fit: 1 roti (~119 kcal), 1 bowl of dal (~232 kcal), 1 cup of chai (~75 kcal).", a)
    }

    @Test fun weightRespectsThePin() {
        assertEquals("Your weight is locked. Unlock the Weight tab with your PIN and ask again.", HealthChat.answer("what's my weight", snapshot(weights = null)))
        assertTrue(HealthChat.context(snapshot(weights = null)).contains("Weight: locked by the user."))
        assertTrue(!HealthChat.context(snapshot(weights = null)).contains("72.4"))
    }

    @Test fun openQuestionsGoToTheLlm() {
        assertNull(HealthChat.answer("Is it okay to skip breakfast?", snapshot()))
        assertNull(HealthChat.answer("Tell me a fun fact", snapshot()))
    }
}
