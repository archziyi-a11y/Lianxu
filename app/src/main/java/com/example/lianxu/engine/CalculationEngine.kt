package com.example.lianxu.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor

const val METHOD_VERSION = "fitness-policy-0.1"
val NUTRIENT_KEYS = listOf("energyKcal", "proteinG", "carbohydrateG", "fatG", "fiberG")

data class NutrientValues(val energyKcal: Double? = null, val proteinG: Double? = null,
    val carbohydrateG: Double? = null, val fatG: Double? = null, val fiberG: Double? = null) {
    operator fun get(key: String): Double? = when (key) {
        "energyKcal" -> energyKcal; "proteinG" -> proteinG; "carbohydrateG" -> carbohydrateG
        "fatG" -> fatG; "fiberG" -> fiberG; else -> null
    }
    fun scale(factor: Double) = from(NUTRIENT_KEYS.associateWith { this[it]?.times(factor) })
    companion object {
        fun from(values: Map<String, Double?>) = NutrientValues(values["energyKcal"], values["proteinG"],
            values["carbohydrateG"], values["fatG"], values["fiberG"])
        val ZERO = NutrientValues(0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

fun roundStep(value: Double, step: Double) = floor(value / step + 0.5) * step

data class NutritionPolicy(val version: String = METHOD_VERSION, val activityDefault: Double = 1.6,
    val gainFactor: Double = 1.10, val cutFactor: Double = 0.85, val maintainFactor: Double = 1.0,
    val proteinGainGPerKg: Double = 1.8, val proteinCutGPerKg: Double = 2.0,
    val proteinMaintainGPerKg: Double = 1.8, val fatEnergyFraction: Double = 0.25,
    val fiberGPer1000Kcal: Double = 14.0)

data class NutritionInput(val date: LocalDate, val weightKg: Double?, val heightCm: Double?,
    val birthDate: LocalDate?, val formulaSex: String?, val goalKind: String?, val activityFactor: Double? = null,
    val mode: String = "standard_adult", val manualTargets: NutrientValues? = null,
    val policy: NutritionPolicy = NutritionPolicy())

data class NutritionResult(val status: String, val targets: NutrientValues = NutrientValues(),
    val restingKcal: Double? = null, val maintenanceKcal: Double? = null, val carbsPerKg: Double? = null,
    val missingFields: List<String> = emptyList(), val notes: List<String> = emptyList(),
    val policyVersion: String = METHOD_VERSION, val manualEnergyDifferenceKcal: Double? = null)

object CalculationEngine {
    fun calculate(input: NutritionInput): NutritionResult {
        val policy = input.policy
        fun result(status: String, missing: List<String> = emptyList(), notes: List<String> = emptyList()) =
            NutritionResult(status, missingFields = missing, notes = notes, policyVersion = policy.version)
        if (input.mode == "manual") {
            val targets = input.manualTargets ?: return result("needs_input", listOf("manualTargets"))
            val missing = NUTRIENT_KEYS.take(4).filter { targets[it] == null }
            if (missing.isNotEmpty()) return result("needs_input", missing)
            if (NUTRIENT_KEYS.any { targets[it]?.let { n -> !n.isFinite() || n < 0 } == true } || targets.energyKcal!! <= 0)
                return result("out_of_scope", notes = listOf("手动目标必须是有效的非负数，能量须大于0"))
            val difference = targets.energyKcal - (targets.proteinG!! * 4 + targets.carbohydrateG!! * 4 + targets.fatG!! * 9)
            return NutritionResult("manual", targets, carbsPerKg = input.weightKg?.takeIf { it > 0 }?.let { targets.carbohydrateG / it },
                notes = listOf("采用手动目标；与4/4/9换算的能量差额为独立参考，不修改原值"),
                policyVersion = policy.version, manualEnergyDifferenceKcal = difference)
        }
        val missing = buildList {
            if (input.weightKg == null) add("weightKg")
            if (input.heightCm == null) add("heightCm")
            if (input.birthDate == null) add("birthDate")
            if (input.formulaSex == null) add("formulaSex")
            if (input.goalKind == null) add("goalKind")
        }
        if (missing.isNotEmpty()) return result("needs_input", missing)
        val weight = input.weightKg!!; val height = input.heightCm!!
        val age = ChronoUnit.YEARS.between(input.birthDate, input.date)
        val activity = input.activityFactor ?: policy.activityDefault
        if (!weight.isFinite() || weight <= 0 || !height.isFinite() || height <= 0 || age !in 19..78 ||
            input.formulaSex !in listOf("male", "female") || input.goalKind !in listOf("gain", "cut", "maintain") ||
            !activity.isFinite() || activity !in 1.4..2.5)
            return result("out_of_scope", notes = listOf("资料不在当前成人公式或参数范围内，记录仍保留"))
        val factor = when (input.goalKind) { "gain" -> policy.gainFactor; "cut" -> policy.cutFactor; else -> policy.maintainFactor }
        val proteinFactor = when (input.goalKind) { "gain" -> policy.proteinGainGPerKg; "cut" -> policy.proteinCutGPerKg; else -> policy.proteinMaintainGPerKg }
        if (listOf(factor, proteinFactor, policy.fiberGPer1000Kcal).any { !it.isFinite() || it < 0 } || factor <= 0 ||
            !policy.fatEnergyFraction.isFinite() || policy.fatEnergyFraction !in 0.0..1.0)
            return result("out_of_scope", notes = listOf("营养策略参数不适配"))
        val ree = 10 * weight + 6.25 * height - 5 * age + if (input.formulaSex == "male") 5 else -161
        val maintenance = ree * activity; val energy = maintenance * factor
        if (input.goalKind == "cut" && energy < 1200)
            return result("out_of_scope", notes = listOf("自动减脂规划低于本版本1200kcal的工程边界，可改用手动目标"))
        val protein = weight * proteinFactor; val fat = energy * policy.fatEnergyFraction / 9
        val carbs = (energy - protein * 4 - fat * 9) / 4
        if (energy <= 0 || carbs < 0 || protein * 4 > energy * 0.35)
            return result("out_of_scope", notes = listOf("当前参数分配不适配，未截断或更改原参数"))
        val notes = buildList {
            if (input.activityFactor == null) add("活动系数暂按${policy.activityDefault}，可在档案修改")
            add("公式与阶段倍率为初始估计；食物建议按实际份量继续补充")
        }
        return NutritionResult("ready", NutrientValues(energy, protein, carbs, fat, energy / 1000 * policy.fiberGPer1000Kcal),
            ree, maintenance, carbs / weight, notes = notes, policyVersion = policy.version)
    }
}
