package com.example.lianxu.agent

/** Full recursive schemas guide ordinary tool calling; semantic checks still run locally. */
internal object ToolSchemas {
    private fun scalar(type: String) = mapOf<String, Any?>("type" to type)
    private val str = scalar("string"); private val number = scalar("number"); private val integer = scalar("integer"); private val bool = scalar("boolean")
    private fun arr(item: Map<String, Any?>) = mapOf<String, Any?>("type" to "array", "items" to item)
    private fun obj(required: List<String> = emptyList(), vararg props: Pair<String, Map<String, Any?>>): Map<String, Any?> =
        mapOf("type" to "object", "properties" to props.toMap(), "required" to required, "additionalProperties" to false)
    private fun enum(vararg values: String) = mapOf<String, Any?>("type" to "string", "enum" to values.toList())
    val ref = obj(listOf("type","id","revision"), "type" to str, "id" to str, "revision" to integer)
    private val numberSpec = obj(listOf("kind"), "kind" to enum("unknown","exact","range"), "value" to number, "min" to number, "max" to number, "unit" to str)
    private val recordedSet = obj(listOf("setId"), "setId" to str,"reps" to integer,"loadKg" to number,"loadBasis" to enum("unknown","total","per_hand","machine_stack","bodyweight"),"side" to enum("unspecified","both","left","right"),"isWarmup" to bool)
    private val exercise = obj(listOf("entryId","name"),"entryId" to str,"name" to str,"canonicalKey" to str,"equipment" to str,"summarySets" to numberSpec,"repsPerSet" to numberSpec,"loadKg" to numberSpec,"loadBasis" to enum("unknown","total","per_hand","machine_stack","bodyweight"),"sets" to arr(recordedSet))
    private val date = mapOf<String, Any?>("anyOf" to listOf(str,obj(listOf("date","zoneId","basis"),"date" to str,"zoneId" to str,"basis" to str)))
    private val nutrients = obj(emptyList(), *listOf("energyKcal","proteinG","carbohydrateG","fatG","fiberG").map { it to number }.toTypedArray())
    private val training = obj(listOf("kind","title"),"dateContext" to date,"kind" to enum("strength","cardio","mixed","rest","other"),"title" to str,"durationS" to integer,"exercises" to arr(exercise),"rawSummary" to str)
    private val intake = obj(listOf("mode"),"dateContext" to date,"mealKey" to str,"mode" to enum("items","nutrient_total","description"),"items" to arr(obj(listOf("foodRef"),"itemId" to str,"foodRef" to str,"grams" to number)),"manualNutrients" to nutrients,"rawDescription" to str,"completeness" to enum("partial","complete"))
    private val measurement = obj(listOf("metric","value","unit"),"metric" to enum("weight","body_fat","waist","height"),"value" to number,"unit" to enum("kg","%","cm"),"observed" to date,"measuredAt" to number,"note" to str)
    private val profile = obj(emptyList(),"heightCm" to number,"birthDate" to str,"formulaSex" to enum("male","female"),"activityFactor" to number,"preferredZoneId" to str,"mealSlots" to arr(obj(listOf("key","label","ratio"),"key" to str,"label" to str,"ratio" to number)),"defaultScene" to enum("home","canteen","takeout"),"excludedIngredients" to arr(str),"preferredFoods" to arr(str),"nutritionMode" to enum("standard_adult","manual"),"manualTargets" to nutrients)
    private val analysis = obj(listOf("summaryText"),"kind" to enum("reply","assessment"),"rangeStart" to str,"rangeEnd" to str,"summaryText" to str,"blocks" to arr(obj()),"calculationIds" to arr(str),"inputRefs" to arr(ref))
    private fun operation(kind: String, payload: Map<String, Any?>): Map<String, Any?> = obj(listOf("operationId","kind","payload","relatedAnnotationIds","reason"),
        "operationId" to str,"kind" to enum(kind),"targetRef" to ref,"clientRef" to str,"payload" to payload,"relatedAnnotationIds" to arr(str),"reason" to str)
    val operations: Map<String, Any?> = arr(mapOf("anyOf" to listOf(
        operation("create_training",training),operation("update_training",obj(listOf("set"),"set" to (training + ("required" to emptyList<String>())))),
        operation("create_intake",intake),operation("update_intake",obj(listOf("set"),"set" to (intake + ("required" to emptyList<String>())))),
        operation("create_measurement",measurement),operation("update_measurement",obj(listOf("set"),"set" to (measurement + ("required" to emptyList<String>())))),
        operation("update_profile",obj(listOf("set"),"set" to profile)),
        operation("set_goal",obj(listOf("kind","startsOn"),"kind" to enum("gain","cut","maintain"),"startsOn" to str,"note" to str)),
        operation("create_analysis",analysis),
        operation("upsert_knowledge",obj(listOf("kind","text"),"kind" to str,"text" to str,"effectiveFrom" to str,"effectiveUntil" to str,"status" to enum("current","superseded"),"expectedRevision" to integer)),
        operation("replace_plan_meal",obj(emptyList(),"mealId" to str,"meal" to mapOf("type" to "object"),"calculationId" to str,"meals" to arr(obj(listOf("mealId","meal","calculationId"),"mealId" to str,"meal" to mapOf("type" to "object"),"calculationId" to str)))),
        operation("create_day_plan",obj(listOf("dateContext","targetCalculationId","meals"),"dateContext" to date,"goalRef" to ref,"targetCalculationId" to str,"meals" to arr(mapOf("type" to "object")),"inputRefs" to arr(ref)))
    )))
    private val content = obj(emptyList(),"label" to str,"text" to str,"contentRef" to obj(listOf("entityRef","fieldPath"),"entityRef" to ref,"fieldPath" to str),"calculationId" to str,"resultPath" to str,"unit" to str,"entityRefs" to arr(ref),"mealId" to str,"options" to arr(obj(listOf("key","label"),"key" to str,"label" to str)),"selectedKey" to str,"attachmentId" to str)
    val blocks = arr(obj(listOf("componentId","type","content"),"componentId" to str,"type" to enum("text","image","metric","choice","list","trend"),"content" to content,"entityRef" to ref,"actions" to arr(obj(listOf("actionId","label","kind","targetRef"),"actionId" to str,"label" to str,"kind" to enum("replace_meal","scale_meal","mark_eaten","lock_meal","change_scene","open_source","skip_meal","reopen_meal"),"targetRef" to ref,"mealId" to str,"value" to str)),"order" to integer))
    val boardPatch = obj(emptyList(),"upsertCards" to arr(obj(listOf("title","span","blocks"),"cardId" to str,"clientRef" to str,"revision" to integer,"title" to str,"span" to mapOf("type" to "integer","enum" to listOf(2,3,6)),"blocks" to blocks,"entityRefs" to arr(ref),"order" to integer)),"removeCardIds" to arr(str),"orderedCardIds" to arr(str))
    private fun calculation(operation: String, input: Map<String, Any?>) = obj(listOf("operation","input"),"operation" to enum(operation),"input" to input,"stageId" to str)
    private val calculations = listOf(
        calculation("nutrition_target",obj(listOf("date"),"date" to str,"profileRef" to ref,"goalRef" to ref,"measurementRefs" to arr(ref))),
        calculation("food_totals",obj(emptyList(),"items" to arr(obj(listOf("foodId","grams"),"foodId" to str,"grams" to number)),"mealId" to str)),
        calculation("training_summary",obj(listOf("startDate","endDate"),"startDate" to str,"endDate" to str,"exerciseKey" to str)),
        calculation("intake_totals",obj(listOf("date"),"date" to str)),
        calculation("weight_trend",obj(listOf("endDate","windowDays"),"endDate" to str,"windowDays" to mapOf("type" to "integer","enum" to listOf(7,14,28)))),
        calculation("meal_candidates",obj(listOf("date","scope","scene"),"date" to str,"planRef" to ref,"scope" to enum("day","meal"),"mealKey" to str,"scene" to enum("home","canteen","takeout"),"constraints" to obj(emptyList(),"excludeIngredients" to arr(str))))
    )
    fun enrich(name: String, parameters: Map<String, Any?>): Map<String, Any?> {
        if (name == "calculate") return mapOf("type" to "object", "anyOf" to calculations)
        @Suppress("UNCHECKED_CAST") val props = (parameters["properties"] as Map<String, Any?>).toMutableMap()
        if (name in listOf("stage_records","finish_proposal")) props["operations"] = operations
        if (name == "finish_proposal") { props["blocks"] = blocks; props["boardPatch"] = boardPatch; props["baseRefs"] = arr(ref) }
        return parameters + ("properties" to props)
    }
}
