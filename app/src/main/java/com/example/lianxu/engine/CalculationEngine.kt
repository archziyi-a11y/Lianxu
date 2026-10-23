package com.example.lianxu.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class NutritionResult(
    val energyKcal: Int,
    val proteinG: Int,
    val carbohydrateG: Int,
    val fatG: Int,
    val fiberG: Int,
    val restingKcal: Int?,
    val maintenanceKcal: Int?
)

object CalculationEngine {
    
    fun calculate(
        weightKg: Float,
        heightCm: Float,
        birthDate: LocalDate,
        formulaSex: String, // "male" or "female"
        activityFactor: Float, // default 1.6
        goalKind: String // "gain", "cut", "maintain"
    ): NutritionResult {
        val age = ChronoUnit.YEARS.between(birthDate, LocalDate.now()).toInt()
        
        val s = when (formulaSex.lowercase()) {
            "male" -> 5
            "female" -> -161
            else -> 0 // fallback if needed, but standard requires one
        }
        
        // REE = 10 × 体重kg + 6.25 × 身高cm − 5 × 年龄岁 + s
        val ree = 10f * weightKg + 6.25f * heightCm - 5f * age + s
        val maintenanceKcal = ree * activityFactor
        
        val targetEnergyMultiplier = when (goalKind.lowercase()) {
            "gain" -> 1.10f
            "cut" -> 0.85f
            "maintain" -> 1.00f
            else -> 1.00f
        }
        
        val targetEnergyKcal = maintenanceKcal * targetEnergyMultiplier
        
        val proteinMultiplier = when (goalKind.lowercase()) {
            "cut" -> 2.0f
            else -> 1.8f
        }
        
        val proteinG = weightKg * proteinMultiplier
        val fatG = targetEnergyKcal * 0.25f / 9f
        val carbohydrateG = (targetEnergyKcal - proteinG * 4f - fatG * 9f) / 4f
        val fiberG = targetEnergyKcal / 1000f * 14f
        
        // 舍入要求：能量到最近10 kcal，日营养素到1g
        val roundedEnergy = ((targetEnergyKcal / 10f).roundToInt() * 10)
        
        return NutritionResult(
            energyKcal = roundedEnergy,
            proteinG = proteinG.roundToInt(),
            carbohydrateG = carbohydrateG.roundToInt(),
            fatG = fatG.roundToInt(),
            fiberG = fiberG.roundToInt(),
            restingKcal = ree.roundToInt(),
            maintenanceKcal = maintenanceKcal.roundToInt()
        )
    }
}
