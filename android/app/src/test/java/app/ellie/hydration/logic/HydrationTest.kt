package app.ellie.hydration.logic

import app.ellie.hydration.logic.Hydration.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class HydrationTest {
    @Test fun parsesPlainNumbersAndUnits() {
        assertEquals(300, Hydration.parseAmountMl("300"))
        assertEquals(300, Hydration.parseAmountMl(" 300ml "))
        assertEquals(500, Hydration.parseAmountMl("0.5 l"))
        assertEquals(500, Hydration.parseAmountMl("0,5L"))
        assertEquals(250, Hydration.parseAmountMl("1 glass"))
        assertEquals(500, Hydration.parseAmountMl("2 glasses"))
        assertEquals(500, Hydration.parseAmountMl("a bottle"))
        assertEquals(250, Hydration.parseAmountMl("glass"))
        assertEquals(750, Hydration.parseAmountMl("three cups"))
    }

    @Test fun rejectsGarbageAndOutOfRange() {
        assertNull(Hydration.parseAmountMl(null))
        assertNull(Hydration.parseAmountMl(""))
        assertNull(Hydration.parseAmountMl("lots"))
        assertNull(Hydration.parseAmountMl("0"))
        assertNull(Hydration.parseAmountMl("5000"))
        assertNull(Hydration.parseAmountMl("3 l"))
        assertNull(Hydration.parseAmountMl("-200"))
        assertNull(Hydration.parseAmountMl("1".repeat(40)))
    }

    private val wake = LocalTime.of(7, 0)
    private val sleep = LocalTime.of(22, 0)

    @Test fun proRatesAcrossTheDay() {
        assertEquals(0, Hydration.expectedByNow(3000, LocalTime.of(6, 0), wake, sleep))
        assertEquals(1500, Hydration.expectedByNow(3000, LocalTime.of(14, 30), wake, sleep))
        assertEquals(3000, Hydration.expectedByNow(3000, LocalTime.of(23, 0), wake, sleep))
    }

    @Test fun moodThresholds() {
        assertEquals(Mood.HAPPY, Hydration.mood(1000, 1000))
        assertEquals(Mood.NEUTRAL, Hydration.mood(800, 1000))
        assertEquals(Mood.THIRSTY, Hydration.mood(700, 1000))
        assertEquals(Mood.HAPPY, Hydration.mood(0, 0))
    }

    @Test fun remindsOnlyWhenBehindAwakeAndIntervalPassed() {
        val hour = 3_600_000L
        assertTrue(Hydration.shouldRemind(2 * hour, 0, 100, 3000, 1000, awake = true))
        assertFalse(Hydration.shouldRemind(2 * hour, 0, 100, 3000, 1000, awake = false))
        assertFalse(Hydration.shouldRemind(2 * hour, 0, 1000, 3000, 1000, awake = true)) // on track
        assertFalse(Hydration.shouldRemind(2 * hour, 0, 3000, 3000, 3000, awake = true)) // goal met
        assertFalse(Hydration.shouldRemind(hour / 2, 0, 100, 3000, 1000, awake = true)) // too soon
        // neutral (80%) waits 90 min
        assertFalse(Hydration.shouldRemind(80 * 60_000L, 0, 800, 3000, 1000, awake = true))
        assertTrue(Hydration.shouldRemind(91 * 60_000L, 0, 800, 3000, 1000, awake = true))
    }
}
