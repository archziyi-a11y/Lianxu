package com.example.lianxu

import com.example.lianxu.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalculationRulesTest {
    private val date = LocalDate.of(2026, 10, 24)
    private fun input(goal: String = "gain") = NutritionInput(date, 70.0, 175.0, LocalDate.of(1996, 1, 1), "male", goal, 1.6)
    private fun asset(name: String) = javaClass.classLoader!!.getResourceAsStream("food/$name")!!.bufferedReader().use { it.readText() }
    private fun catalog() = FoodCatalog.fromJson(asset("foods.v1.json"), asset("recipes.v1.json"))
    private fun food(id: String, n: NutrientValues) = CatalogFood(id, id, "cooked", n, id, "test", "https://example.invalid")

    @Test fun exactGainAndCutMatchSpecification() {
        val gain = CalculationEngine.calculate(input())
        assertEquals("ready", gain.status)
        assertEquals(1648.75, gain.restingKcal!!, 1e-6)
        assertEquals(2638.0, gain.maintenanceKcal!!, 1e-6)
        assertEquals(2901.8, gain.targets.energyKcal!!, 1e-6)
        assertEquals(126.0, gain.targets.proteinG!!, 1e-6)
        assertEquals(80.6055555556, gain.targets.fatG!!, 1e-6)
        assertEquals(418.0875, gain.targets.carbohydrateG!!, 1e-6)
        assertEquals(40.6252, gain.targets.fiberG!!, 1e-6)
        val cut = CalculationEngine.calculate(input("cut"))
        assertEquals(2242.3, cut.targets.energyKcal!!, 1e-6)
        assertEquals(140.0, cut.targets.proteinG!!, 1e-6)
        assertEquals(280.43125, cut.targets.carbohydrateG!!, 1e-6)
        assertEquals(31.3922, cut.targets.fiberG!!, 1e-6)
    }
    @Test fun displayRoundingDoesNotChangeInputs() {
        val result = CalculationEngine.calculate(input())
        assertEquals(2900.0, roundStep(result.targets.energyKcal!!, 10.0), 0.0)
        assertEquals(2901.8, result.targets.energyKcal!!, 1e-6)
        assertEquals(105.0, roundStep(102.5, 5.0), 0.0)
    }
    @Test fun missingFieldsAreNotGuessed() {
        val result = CalculationEngine.calculate(input().copy(heightCm = null, formulaSex = null))
        assertEquals("needs_input", result.status)
        assertEquals(setOf("heightCm", "formulaSex"), result.missingFields.toSet())
        assertNull(result.restingKcal); assertNull(result.targets.energyKcal)
    }
    @Test fun ageUsesExplicitCalculationDate() {
        val base = input().copy(birthDate = LocalDate.of(1996, 12, 1))
        val before = CalculationEngine.calculate(base)
        val after = CalculationEngine.calculate(base.copy(date = LocalDate.of(2026, 12, 1)))
        assertEquals(5.0, before.restingKcal!! - after.restingKcal!!, 1e-6)
    }
    @Test fun outOfScopeDoesNotGenerateAutomaticTargets() {
        assertEquals("out_of_scope", CalculationEngine.calculate(input().copy(birthDate = date.minusYears(18))).status)
        assertEquals("out_of_scope", CalculationEngine.calculate(input("cut").copy(weightKg = 40.0, heightCm = 140.0, formulaSex = "female", activityFactor = 1.4, birthDate = date.minusYears(70))).status)
        assertEquals("out_of_scope", CalculationEngine.calculate(input().copy(policy = NutritionPolicy(proteinGainGPerKg = 20.0))).status)
    }
    @Test fun manualTargetsPreserveMismatchAndUnknownFiber() {
        val manual = NutrientValues(2000.0, 120.0, 200.0, 60.0, null)
        val result = CalculationEngine.calculate(input().copy(mode = "manual", manualTargets = manual))
        assertEquals("manual", result.status); assertEquals(manual, result.targets)
        assertEquals(180.0, result.manualEnergyDifferenceKcal!!, 1e-6)
        assertNull(result.targets.fiberG)
    }
    @Test fun activityDefaultIsExplicit() {
        val result = CalculationEngine.calculate(input().copy(activityFactor = null))
        assertTrue(result.notes.any { it.contains("1.6") })
        assertEquals(2901.8, result.targets.energyKcal!!, 1e-6)
    }
    @Test fun foodTotalsPreserveUnknownFiber() {
        val c = FoodCatalog(listOf(food("a", NutrientValues(130.0, 2.5, 28.0, .3, .4)), food("b", NutrientValues(165.0, 31.0, 0.0, 3.6, null))), emptyList())
        val n = c.totals(listOf(FoodAmount("a", 200.0), FoodAmount("b", 150.0)))
        assertEquals(507.5, n.total.energyKcal!!, 1e-6); assertEquals(51.5, n.total.proteinG!!, 1e-6)
        assertEquals(56.0, n.total.carbohydrateG!!, 1e-6); assertEquals(6.0, n.total.fatG!!, 1e-6)
        assertNull(n.total.fiberG); assertEquals(.8, n.knownSubtotal.fiberG!!, 1e-6)
        assertEquals(listOf("b"), n.missingItemIds["fiberG"])
        assertEquals(200.0, c.equalAmount("carbohydrateG", 56.0, "a"), 1e-6)
        try { c.equalAmount("carbohydrateG", 56.0, "b"); fail("Zero denominator") } catch (_: IllegalArgumentException) { }
        assertNull(c.portion("a", "一碗"))
    }
    @Test fun intakeOverridesDoNotDoubleCountAndNullMetricsFallBack() {
        val entries = listOf(IntakeValue("lunch-item", "lunch", "item:1", NutrientValues(600.0, 30.0), 1),
            IntakeValue("lunch-total", "lunch", "meal:lunch", NutrientValues(700.0), 2),
            IntakeValue("dinner", "dinner", "item:2", NutrientValues(500.0, 20.0), 3))
        val n = effectiveIntake(entries)
        assertEquals(1200.0, n.total.energyKcal!!, 1e-6)
        assertEquals(50.0, n.total.proteinG!!, 1e-6)
        val daily = effectiveIntake(entries + IntakeValue("day", null, "daily_total", NutrientValues(2000.0), 4))
        assertEquals(2000.0, daily.total.energyKcal!!, 1e-6); assertEquals(50.0, daily.total.proteinG!!, 1e-6)
    }
    @Test fun energyOnlyTotalDoesNotInventZeroProtein() {
        val result = effectiveIntake(listOf(IntakeValue("daily", null, "daily_total", NutrientValues(2000.0), 1)))
        assertEquals(2000.0, result.total.energyKcal!!, 1e-6)
        assertNull(result.total.proteinG)
        assertEquals(listOf("daily"), result.missingItemIds["proteinG"])
    }
    @Test fun packagedCatalogAndBoundedSearchAreDeterministic() {
        val c = catalog(); assertEquals(31, c.foods.size); assertEquals(36, c.recipes.size)
        val planner = MealPlanner(c); val target = CalculationEngine.calculate(input()).targets
        val a = planner.search(target); val b = planner.search(target)
        assertEquals(a, b); assertTrue(a.closeToTarget)
        assertTrue(a.meals.flatMap { it.items }.all { kotlin.math.abs(it.grams % 5) < 1e-6 })
        assertTrue(planner.candidates(planner.defaultSlots[0], "home", emptyList()).size <= 36)
    }
    @Test fun singleMealReplacementPreservesOtherMealsAndLocks() {
        val planner = MealPlanner(catalog()); val target = CalculationEngine.calculate(input()).targets
        val original = planner.search(target).meals
        val lunch = original.single { it.slotKey == "lunch" }
        val replaced = planner.search(target, existing = original, onlyMealId = lunch.mealId, avoidRecipeId = lunch.recipeId).meals
        assertNotEquals(lunch.recipeId, replaced.single { it.mealId == lunch.mealId }.recipeId)
        assertEquals(original.filter { it.mealId != lunch.mealId }, replaced.filter { it.mealId != lunch.mealId })
        val locked = original.map { it.copy(locked = true) }
        assertEquals(locked, planner.search(target, existing = locked).meals)
    }
    @Test fun foodRestrictionsAreHardConstraints() {
        val c = catalog(); val planner = MealPlanner(c)
        val fishId = c.foods.first { it.nameZh.contains("鱼") }.id
        val meals = planner.search(CalculationEngine.calculate(input()).targets, excluded = listOf(fishId)).meals
        assertTrue(meals.flatMap { it.items }.none { it.foodId == fishId })
        val cooked = c.search("米", "cooked")
        assertTrue(cooked.all { it.state == "cooked" })
    }
    @Test fun trainingRangesAndLoadsKeepKnownBoundaries() {
        val range = summarizeExercise(RecordedExercise("e", "卧推", summarySets = NumberSpec("exact", 4.0), repsPerSet = NumberSpec("range", min = 8.0, max = 12.0)))
        assertEquals(32.0, range.repetitionsMin!!, 1e-6); assertEquals(48.0, range.repetitionsMax!!, 1e-6)
        assertTrue(range.missingSetIds.isNotEmpty())
        val known = summarizeExercise(RecordedExercise("e", "卧推", sets = listOf(RecordedSet("a", 8, 60.0, "total"), RecordedSet("b", 10, 60.0, "total"), RecordedSet("c"))))
        assertEquals(1080.0, known.knownVolumeKgReps, 1e-6); assertEquals(listOf("c"), known.missingSetIds)
        val both = summarizeExercise(RecordedExercise("d", "哑铃", sets = listOf(RecordedSet("s", 10, 20.0, "per_hand", "both"))))
        val one = summarizeExercise(RecordedExercise("d", "哑铃", sets = listOf(RecordedSet("s", 10, 20.0, "per_hand", "left"))))
        assertEquals(400.0, both.knownVolumeKgReps, 1e-6); assertEquals(200.0, one.knownVolumeKgReps, 1e-6)
    }
    @Test fun weightTrendUsesDailyMeansAndRequiresBothWindows() {
        val end = LocalDate.of(2026, 10, 14)
        val values = listOf(1 to 70.0, 3 to 70.2, 5 to 69.8, 8 to 70.4, 10 to 70.6, 14 to 70.5).map { WeightPoint(LocalDate.of(2026, 10, it.first), it.second) }
        val result = weightTrend(values, end)
        assertEquals(.5, result.weeklyChangeKg!!, 1e-6); assertEquals(.7142857143, result.weeklyChangePct!!, 1e-6)
        assertNull(weightTrend(values.dropLast(1), end).weeklyChangeKg)
        val daily = weightTrend(listOf(WeightPoint(end, 70.0), WeightPoint(end, 72.0)), end)
        assertEquals(71.0, daily.points.single().value, 1e-6)
    }
    @Test fun frequencyDoesNotConvertShortCoverageIntoAFullMonth() {
        val sessions = listOf(date to "resistance", date to "cardio", date.minusDays(1) to "rest")
        val result = trainingFrequency(sessions, date)
        assertEquals(1, result.trainingDays); assertEquals(2, result.sessionCount); assertNull(result.weeklyRecordedDays)
    }
}
