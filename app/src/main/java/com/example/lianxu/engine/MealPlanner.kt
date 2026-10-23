package com.example.lianxu.engine

import kotlin.math.abs
import kotlin.math.max

data class MealSlot(val key: String, val label: String, val ratio: Double)
data class PlannedFood(val foodId: String, val grams: Double, val name: String, val state: String,
    val nutrientsPer100g: NutrientValues, val sourceId: String, val sourceVersion: String, val sourceUrl: String)
data class PlannedMeal(val mealId: String, val slotKey: String, val label: String, val scene: String,
    val recipeId: String, val title: String, val factor: Double, val items: List<PlannedFood>, val locked: Boolean = false,
    val note: String? = null)
data class MealsDocument(val meals: List<PlannedMeal>)
data class MealSearchResult(val meals: List<PlannedMeal>, val totals: NutrientAggregate,
    val differences: NutrientValues, val score: Double, val closeToTarget: Boolean, val notes: List<String>)

class MealPlanner(private val catalog: FoodCatalog) {
    val defaultSlots = listOf(MealSlot("breakfast", "早餐", .30), MealSlot("lunch", "午餐", .35), MealSlot("dinner", "晚餐", .35))
    fun meal(recipe: CatalogRecipe, slot: MealSlot, factor: Double, id: String = slot.key): PlannedMeal {
        require(factor in recipe.portionMinFactor..recipe.portionMaxFactor)
        return PlannedMeal(id, slot.key, slot.label, recipe.scene, recipe.id, recipe.name, factor,
            recipe.ingredients.map { amount ->
                val food = catalog.byId.getValue(amount.foodId)
                PlannedFood(food.id, roundStep(amount.grams * factor, 5.0), food.nameZh, food.state, food.nutrients,
                    food.sourceId, food.sourceVersion, food.sourceUrl)
            }, note = recipe.sceneNote)
    }
    fun totals(meals: List<PlannedMeal>): NutrientAggregate = aggregate(meals.flatMap { meal ->
        meal.items.mapIndexed { index, item -> "${meal.mealId}:$index" to item.nutrientsPer100g.scale(item.grams / 100) }
    })
    fun candidates(slot: MealSlot, scene: String, excluded: List<String>): List<PlannedMeal> {
        val exact = catalog.recipes.any { slot.key in it.mealKeys }
        val recipes = catalog.recipes.filter { it.scene == scene && (!exact || slot.key in it.mealKeys) && catalog.permits(it, excluded) }
            .sortedBy { it.id }.take(12)
        val candidates = recipes.flatMap { recipe -> listOf(.75, 1.0, 1.25).filter { it in recipe.portionMinFactor..recipe.portionMaxFactor }.map { meal(recipe, slot, it) } }
            .filter { totals(listOf(it)).total.let { n -> NUTRIENT_KEYS.take(4).all { n[it] != null } } }
        val complete = candidates.filter { totals(listOf(it)).total.fiberG != null }
        return if (complete.isNotEmpty()) complete else candidates
    }
    fun search(target: NutrientValues, slots: List<MealSlot> = defaultSlots, scene: String = "home",
        excluded: List<String> = emptyList(), existing: List<PlannedMeal> = emptyList(),
        onlyMealId: String? = null, avoidRecipeId: String? = null, eatenMealIds: Set<String> = emptySet(),
        intake: NutrientValues = NutrientValues.ZERO): MealSearchResult {
        require(slots.size in 1..5 && abs(slots.sumOf { it.ratio } - 1) < 1e-6)
        require(NUTRIENT_KEYS.take(4).all { target[it]?.let { n -> n >= 0 && n.isFinite() } == true })
        require(target.energyKcal!! > 0)
        if (onlyMealId != null) require(existing.any { it.mealId == onlyMealId })
        var beam = listOf(emptyList<PlannedMeal>())
        var proportion = 0.0
        for (slot in slots) {
            proportion += slot.ratio
            val old = existing.firstOrNull { it.slotKey == slot.key }
            val fixed = old != null && (old.locked || old.mealId in eatenMealIds || onlyMealId != null && old.mealId != onlyMealId)
            if (old?.locked == true && old.mealId !in eatenMealIds) require(catalog.recipes.firstOrNull { it.id == old.recipeId }?.let { catalog.permits(it, excluded) } == true) { "锁定餐与当前禁食限制冲突，请调整其中一项" }
            val choices = if (fixed) listOf(old!!) else candidates(slot, old?.scene ?: scene, excluded)
                .filter { it.recipeId != avoidRecipeId || old?.mealId != onlyMealId }
                .map { it.copy(mealId = old?.mealId ?: it.mealId) }
            require(choices.isNotEmpty()) { "NO_MEAL_CANDIDATE: ${slot.label}在当前场景与限制下没有候选" }
            beam = beam.flatMap { prefix -> choices.map { prefix + it } }
                .sortedWith(compareBy<List<PlannedMeal>> { score(effective(it, eatenMealIds, intake), target.scale(proportion)) }
                    .thenBy { meals -> meals.joinToString("|") { "${it.recipeId}:${it.factor}" } }).take(100)
        }
        val best = beam.minWith(compareBy<List<PlannedMeal>> { score(effective(it, eatenMealIds, intake), target) }
            .thenBy { it.joinToString("|") { meal -> "${meal.recipeId}:${meal.factor}" } })
        val total = effective(best, eatenMealIds, intake)
        val diff = NutrientValues.from(NUTRIENT_KEYS.associateWith { key -> total.total[key]?.let { it - (target[key] ?: return@let null) } })
        val close = abs(diff.energyKcal ?: Double.POSITIVE_INFINITY) <= target.energyKcal!! * .10 &&
            abs(diff.proteinG ?: Double.POSITIVE_INFINITY) <= target.proteinG!! * .15
        return MealSearchResult(best, total, diff, score(total, target), close,
            if (close) listOf("份量按5g步长核算；接近目标为本版本匹配标签") else listOf("当前可行组合仍有差额，可更换餐食或调整份量"))
    }
    private fun effective(meals: List<PlannedMeal>, eaten: Set<String>, intake: NutrientValues): NutrientAggregate {
        val future = totals(meals.filter { it.mealId !in eaten })
        return aggregate(listOf("planned" to future.total, "intake" to intake))
    }
    fun score(total: NutrientAggregate, target: NutrientValues): Double {
        val n = total.total
        if (NUTRIENT_KEYS.take(4).any { n[it] == null }) return Double.POSITIVE_INFINITY
        return 3 * abs(n.energyKcal!! - target.energyKcal!!) / max(target.energyKcal, 1.0) +
            3 * abs(n.proteinG!! - target.proteinG!!) / max(target.proteinG, 1.0) +
            abs(n.carbohydrateG!! - target.carbohydrateG!!) / max(target.carbohydrateG, 1.0) +
            abs(n.fatG!! - target.fatG!!) / max(target.fatG, 1.0) +
            (if (n.fiberG == null || target.fiberG == null) 1.0 else max(0.0, target.fiberG - n.fiberG) / max(target.fiberG, 1.0))
    }
}
