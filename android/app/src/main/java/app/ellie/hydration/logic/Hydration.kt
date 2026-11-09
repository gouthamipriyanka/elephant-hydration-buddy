package app.ellie.hydration.logic

import java.time.LocalDate
import java.time.LocalTime

/** Pure hydration logic: no Android dependencies, fully unit-tested. */
object Hydration {
    const val MAX_SINGLE_ENTRY_ML = 2000
    const val GLASS_ML = 250
    const val BOTTLE_ML = 500
    const val FALLBACK_TARGET_ML = 2600

    enum class Mood { HAPPY, NEUTRAL, THIRSTY }

    private val numberWithUnit = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(ml|l|litres?|liters?|glass(?:es)?|cups?|bottles?)?\s*$""")
    private val wordUnit = Regex("""^\s*(a|one|two|three)?\s*(glass(?:es)?|cups?|bottles?)\s*$""")

    /**
     * Parses what he types into the notification. Accepts "300", "300ml", "0.5 l",
     * "1 glass", "2 glasses", "a bottle". Returns null for anything unclear or
     * outside 1..2000 ml (prevents fat-finger "5000").
     */
    fun parseAmountMl(input: String?): Int? {
        if (input.isNullOrBlank() || input.length > 30) return null
        val s = input.trim().lowercase()

        wordUnit.matchEntire(s)?.let { m ->
            val count = when (m.groupValues[1]) { "", "a", "one" -> 1; "two" -> 2; "three" -> 3; else -> return null }
            return validate(count * unitMl(m.groupValues[2]))
        }

        val m = numberWithUnit.matchEntire(s) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val ml = when {
            unit.isEmpty() || unit == "ml" -> n
            unit == "l" || unit.startsWith("lit") -> n * 1000
            else -> n * unitMl(unit)
        }
        return validate(Math.round(ml).toInt())
    }

    private fun unitMl(unit: String): Int = if (unit.startsWith("bottle")) BOTTLE_ML else GLASS_ML

    private fun validate(ml: Int): Int? = if (ml in 1..MAX_SINGLE_ENTRY_ML) ml else null

    /** Linear pro-rating of the daily target across waking hours. */
    fun expectedByNow(targetMl: Int, now: LocalTime, wake: LocalTime, sleep: LocalTime): Int {
        if (!sleep.isAfter(wake)) return targetMl // misconfigured: don't nag on a broken schedule
        if (!now.isAfter(wake)) return 0
        if (!now.isBefore(sleep)) return targetMl
        val total = (sleep.toSecondOfDay() - wake.toSecondOfDay()).toDouble()
        val done = (now.toSecondOfDay() - wake.toSecondOfDay()).toDouble()
        return (targetMl * (done / total)).toInt()
    }

    fun mood(consumedMl: Int, expectedMl: Int): Mood = when {
        expectedMl <= 0 || consumedMl >= expectedMl -> Mood.HAPPY
        consumedMl < expectedMl * 0.75 -> Mood.THIRSTY
        else -> Mood.NEUTRAL
    }

    fun isAwake(now: LocalTime, wake: LocalTime, sleep: LocalTime): Boolean =
        !now.isBefore(wake) && now.isBefore(sleep)

    /** Minutes between reminders: more frequent when he is further behind. */
    fun reminderIntervalMinutes(mood: Mood): Long = when (mood) {
        Mood.THIRSTY -> 60
        Mood.NEUTRAL -> 90
        Mood.HAPPY -> Long.MAX_VALUE
    }

    fun shouldRemind(
        nowMillis: Long,
        lastReminderMillis: Long,
        consumedMl: Int,
        targetMl: Int,
        expectedMl: Int,
        awake: Boolean,
    ): Boolean {
        if (!awake || consumedMl >= targetMl) return false
        val m = mood(consumedMl, expectedMl)
        if (m == Mood.HAPPY) return false
        val gap = reminderIntervalMinutes(m) * 60_000L
        return nowMillis - lastReminderMillis >= gap
    }

    fun dayKey(date: LocalDate): String = date.toString()
}
