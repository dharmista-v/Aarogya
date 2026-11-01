package com.example.aarogya

import com.example.aarogya.data.ActivityLevel
import com.example.aarogya.data.Maintenance
import org.junit.Assert.assertEquals
import org.junit.Test

class MaintenanceTest {
    @Test fun poundsTimesActivityFactor() {
        assertEquals(2250, Maintenance.kcal(150.0, inKg = false, ActivityLevel.MODERATE)) // 150 lb × 15
        assertEquals(2100, Maintenance.kcal(150.0, inKg = false, ActivityLevel.SEDENTARY)) // × 14
        assertEquals(2700, Maintenance.kcal(150.0, inKg = false, ActivityLevel.VERY_ACTIVE)) // × 18
    }

    @Test fun convertsKilograms() {
        assertEquals(2315, Maintenance.kcal(70.0, inKg = true, ActivityLevel.MODERATE)) // 70 kg ≈ 154.3 lb × 15
    }
}
