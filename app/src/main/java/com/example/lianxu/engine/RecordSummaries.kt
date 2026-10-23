package com.example.lianxu.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class NumberSpec(val kind: String = "unknown", val value: Double? = null, val min: Double? = null,
    val max: Double? = null, val unit: String = "count") {
    fun bounds(): Pair<Double, Double>? = when(kind) {
        "exact" -> value?.let { it to it }
        "range" -> if (min != null && max != null && min <= max) min to max else null
        else -> null
    }
}
data class RecordedSet(val setId: String, val reps: Int? = null, val loadKg: Double? = null,
    val loadBasis: String = "unknown", val side: String = "unspecified", val isWarmup: Boolean? = null)
data class RecordedExercise(val entryId: String, val name: String, val canonicalKey: String? = null,
    val equipment: String? = null, val summarySets: NumberSpec = NumberSpec(),
    val repsPerSet: NumberSpec = NumberSpec(), val loadKg: NumberSpec = NumberSpec(),
    val loadBasis: String = "unknown", val sets: List<RecordedSet> = emptyList())
data class ExerciseTotals(val entryId: String, val repetitionsMin: Double?, val repetitionsMax: Double?,
    val knownVolumeKgReps: Double, val missingSetIds: List<String>, val machineVolumeKgReps: Double,
    val comparisonKey: String, val warmupSetIds: List<String>, val unclassifiedSetIds: List<String>)

fun summarizeExercise(e: RecordedExercise): ExerciseTotals {
    var volume = 0.0; var machine = 0.0
    val missing = mutableListOf<String>()
    var repMin: Double? = null; var repMax: Double? = null
    if (e.sets.isNotEmpty()) {
        val knownReps = e.sets.mapNotNull { it.reps }
        if (knownReps.size == e.sets.size) { repMin = knownReps.sum().toDouble(); repMax = repMin }
        e.sets.forEach { set ->
            val multiplier = when (set.loadBasis) { "total" -> 1; "per_hand" -> when(set.side) { "both" -> 2; "left", "right" -> 1; else -> null }; else -> null }
            if (set.reps != null && set.loadKg != null && set.loadBasis == "machine_stack") machine += set.reps * set.loadKg
            else if (set.reps != null && set.loadKg != null && multiplier != null) volume += set.reps * set.loadKg * multiplier
            else missing.add(set.setId)
        }
    } else {
        val count = e.summarySets.bounds(); val reps = e.repsPerSet.bounds(); val load = e.loadKg.bounds()
        if (count != null && reps != null) { repMin = count.first * reps.first; repMax = count.second * reps.second }
        // An interval is not converted into a fabricated exact volume.
        if (repMin != null && repMin == repMax && load != null && load.first == load.second && e.loadBasis == "total") volume = repMin * load.first
        else missing.add(e.entryId)
    }
    return ExerciseTotals(e.entryId, repMin, repMax, volume, missing, machine,
        "${e.canonicalKey ?: e.name}|${e.equipment}|${e.loadBasis}|${e.sets.map { it.side }.distinct().sorted()}",
        e.sets.filter { it.isWarmup == true }.map { it.setId }, e.sets.filter { it.isWarmup == null }.map { it.setId })
}

data class WeightPoint(val date: LocalDate, val value: Double)
data class WeightTrend(val points: List<WeightPoint>, val recentMean: Double?, val previousMean: Double?,
    val weeklyChangeKg: Double?, val weeklyChangePct: Double?)
fun weightTrend(values: List<WeightPoint>, endDate: LocalDate): WeightTrend {
    val daily = values.filter { !it.date.isAfter(endDate) && it.value > 0 && it.value.isFinite() }
        .groupBy { it.date }.map { (date, points) -> WeightPoint(date, points.map { it.value }.average()) }.sortedBy { it.date }
    val recent = daily.filter { !it.date.isBefore(endDate.minusDays(6)) }
    val previous = daily.filter { !it.date.isBefore(endDate.minusDays(13)) && it.date.isBefore(endDate.minusDays(6)) }
    val enough = recent.size >= 3 && previous.size >= 3
    val a = recent.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
    val b = previous.takeIf { it.isNotEmpty() }?.map { it.value }?.average()
    return WeightTrend(daily, a, b, if (enough) a!! - b!! else null, if (enough) (a!! - b!!) / b * 100 else null)
}

data class FrequencySummary(val trainingDays: Int, val sessionCount: Int, val observedDays: Long, val weeklyRecordedDays: Double?)
fun trainingFrequency(sessions: List<Pair<LocalDate, String>>, endDate: LocalDate): FrequencySummary {
    val recent = sessions.filter { !it.first.isAfter(endDate) && !it.first.isBefore(endDate.minusDays(27)) }
    val actual = recent.filter { it.second != "rest" }
    val days = actual.map { it.first }.distinct().size
    val coverage = recent.minOfOrNull { it.first }?.let { ChronoUnit.DAYS.between(it, endDate) + 1 } ?: 0
    return FrequencySummary(days, actual.size, coverage, if (coverage >= 28) days / 4.0 else null)
}
