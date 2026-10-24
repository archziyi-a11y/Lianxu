package com.example.lianxu.engine

/** Planning chronology never fabricates an intake. Explicit pending overrides the inferred past. */
fun closedMealIds(meals: List<PlannedMeal>, recordedMealKeys: Set<String>, progress: Map<String, String> = emptyMap()): Set<String> {
    val latestRecorded = meals.indexOfLast { it.slotKey in recordedMealKeys }
    return meals.filterIndexed { index, meal ->
        meal.slotKey in recordedMealKeys || progress[meal.mealId] == "skipped" ||
            index < latestRecorded && progress[meal.mealId] != "pending"
    }.map { it.mealId }.toSet()
}
