package com.example.lianxu.engine

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object DomainJson {
    val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    inline fun <reified T> encode(value: T): String = moshi.adapter(T::class.java).toJson(value)
    inline fun <reified T> decode(value: String): T = requireNotNull(moshi.adapter(T::class.java).fromJson(value))
}

data class FoodPortion(val label: String, val grams: Double, val origin: String, val sourceRef: String? = null)
data class CatalogFood(val id: String, val nameZh: String, val state: String, val nutrients: NutrientValues,
    val sourceId: String, val sourceVersion: String, val sourceUrl: String, val nameOriginal: String = "",
    val aliases: List<String> = emptyList(), val ingredients: List<String> = emptyList(),
    val allergens: List<String> = emptyList(), val allergenStatus: String = "unknown",
    val carbohydrateBasis: String = "unknown", val portions: List<FoodPortion> = emptyList())
data class FoodAmount(val foodId: String, val grams: Double)
data class CatalogRecipe(val id: String, val revision: Int, val name: String, val scene: String,
    val mealKeys: List<String>, val ingredients: List<FoodAmount>, val baseServings: Double,
    val portionMinFactor: Double, val portionMaxFactor: Double, val preparation: String? = null,
    val sceneNote: String? = null)
data class FoodFile(val foods: List<CatalogFood>)
data class RecipeFile(val recipes: List<CatalogRecipe>)
data class NutrientAggregate(val knownSubtotal: NutrientValues, val total: NutrientValues,
    val missingItemIds: Map<String, List<String>>) {
    val complete: Boolean get() = missingItemIds.values.all { it.isEmpty() }
}

class FoodCatalog(val foods: List<CatalogFood>, val recipes: List<CatalogRecipe>) {
    val byId = foods.associateBy { it.id }
    init {
        require(byId.size == foods.size && recipes.map { it.id }.distinct().size == recipes.size)
        require(foods.all { f -> f.sourceId.isNotBlank() && NUTRIENT_KEYS.all { k -> f.nutrients[k]?.let { it.isFinite() && it >= 0 } != false } })
        require(recipes.all { r -> r.ingredients.isNotEmpty() && r.ingredients.all { it.foodId in byId && it.grams > 0 && it.grams.isFinite() } })
    }
    fun search(query: String, state: String? = null) = foods.filter {
        (state == null || it.state == state) && listOf(it.nameZh, it.id, it.nameOriginal, *it.aliases.toTypedArray()).any { s -> s.contains(query, ignoreCase = true) }
    }.take(20)
    fun totals(items: List<FoodAmount>): NutrientAggregate = aggregate(items.map { amount ->
        require(amount.grams.isFinite() && amount.grams >= 0)
        amount.foodId to requireNotNull(byId[amount.foodId]) { "UNKNOWN_FOOD: ${amount.foodId}" }.nutrients.scale(amount.grams / 100)
    })
    fun equalAmount(nutrient: String, targetGrams: Double, foodId: String): Double {
        require(nutrient in NUTRIENT_KEYS && targetGrams.isFinite() && targetGrams >= 0)
        val per100 = requireNotNull(byId[foodId]?.nutrients?.get(nutrient))
        require(per100 > 0) { "该食物不能按此营养指标等量替换" }
        return targetGrams / per100 * 100
    }
    fun portion(foodId: String, label: String): Double? = byId[foodId]?.portions?.firstOrNull { it.label == label }?.grams
    fun permits(recipe: CatalogRecipe, excluded: List<String>): Boolean = recipe.ingredients.all { amount ->
        val food = byId.getValue(amount.foodId)
        val terms = listOf(food.id, food.nameZh, food.nameOriginal, *food.aliases.toTypedArray(), *food.ingredients.toTypedArray(), *food.allergens.toTypedArray()).filter(String::isNotBlank)
        val dairy = setOf("牛奶", "乳制品", "奶类", "milk")
        excluded.none { term -> terms.any { it.contains(term, true) || term.contains(it, true) } || term in dairy && "milk" in food.allergens || term == "鸡肉" && terms.any { it.contains("鸡胸") || it.contains("鸡腿") } } &&
            (excluded.isEmpty() || food.allergenStatus == "known")
    }
    companion object {
        fun fromJson(foods: String, recipes: String) = FoodCatalog(DomainJson.decode<FoodFile>(foods).foods, DomainJson.decode<RecipeFile>(recipes).recipes)
    }
}

fun aggregate(values: List<Pair<String, NutrientValues>>): NutrientAggregate {
    val missing = NUTRIENT_KEYS.associateWith { key -> values.filter { it.second[key] == null }.map { it.first } }
    val known = NUTRIENT_KEYS.associateWith { key -> values.sumOf { it.second[key] ?: 0.0 } }
    return NutrientAggregate(NutrientValues.from(known),
        NutrientValues.from(known.mapValues { (key, n) -> if (missing.getValue(key).isEmpty()) n else null }), missing)
}

data class IntakeValue(val id: String, val mealKey: String?, val coverageKey: String,
    val values: NutrientValues, val createdAt: Long, val completeness: String = "partial")

fun effectiveIntake(entries: List<IntakeValue>): NutrientAggregate {
    val values = mutableMapOf<String, Double?>(); val known = mutableMapOf<String, Double?>()
    val missing = mutableMapOf<String, List<String>>()
    val sorted = entries.sortedWith(compareByDescending<IntakeValue> { it.createdAt }.thenBy { it.id })
    for (key in NUTRIENT_KEYS) {
        val daily = sorted.firstOrNull { it.coverageKey == "daily_total" && it.values[key] != null }
        if (daily != null) { values[key] = daily.values[key]; known[key] = daily.values[key]; missing[key] = emptyList(); continue }
        val selected = mutableListOf<IntakeValue>()
        for ((meal, group) in sorted.filter { it.coverageKey != "daily_total" }.groupBy { it.mealKey }) {
            val total = group.firstOrNull { meal != null && it.coverageKey == "meal:$meal" && it.values[key] != null }
            if (total != null) selected.add(total) else {
                val fallback = group.filter { !it.coverageKey.startsWith("meal:") }
                selected.addAll(if (fallback.isEmpty()) group else fallback)
            }
        }
        if (selected.isEmpty() && sorted.isNotEmpty()) selected.addAll(sorted.filter { it.values[key] == null })
        val absent = selected.filter { it.values[key] == null }.map { it.id }
        val sum = selected.sumOf { it.values[key] ?: 0.0 }
        known[key] = sum; values[key] = if (absent.isEmpty()) sum else null; missing[key] = absent
    }
    return NutrientAggregate(NutrientValues.from(known), NutrientValues.from(values), missing)
}
