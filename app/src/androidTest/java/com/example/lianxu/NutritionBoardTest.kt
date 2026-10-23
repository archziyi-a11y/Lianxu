package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NutritionBoardTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 24)
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
    private suspend fun board(db: AppDatabase) = BoardStore(db).open(emptyList(), date, ZoneId.of("Asia/Shanghai"))
    private suspend fun seed(db: AppDatabase) { AppRepository(db).saveArchive(175f, "1996-01-01", "male", 1.6f, 70f, "gain", date) }
    private suspend fun plan(db: AppDatabase, boardId: String) = db.collaborationDao().planForDate(db.collaborationDao().board(boardId)!!.dateContext)!!
    private fun meals(plan: DayPlanEntity) = DomainJson.decode<MealsDocument>(plan.meals).meals
    private fun action(plan: DayPlanEntity, meal: PlannedMeal, kind: String, value: String? = null) =
        CardAction("test-action", kind, kind, EntityRef("plan", plan.id, plan.revision), meal.mealId, value)

    @Test fun missingDataShowsNoFakeMetricsAndManualNeedsNoBodyForm() = runBlocking {
        val name = "nutrition-missing-${UUID.randomUUID()}"; val db = open(name)
        try {
            val id = board(db); val service = NutritionBoardRepository.create(context, db)
            service.refresh(id)
            val cards = BoardStore(db).watchCards(id).first()
            assertTrue(cards.flatMap { it.blocks }.none { it.type == "metric" || it.type == "trend" })
            assertTrue(ComponentResolver(db).resolve(cards.first { it.title == "今日参考" }).single().text.contains("补充"))
            AppRepository(db).saveArchive(null, null, null, null, null, "maintain", date,
                nutritionMode = "manual", manualTargets = NutrientValues(2200.0, 120.0, 280.0, 65.0, null))
            service.refresh(id)
            val target = DomainJson.decode<NutritionResult>(db.collaborationDao().calculation(plan(db, id).targetCalculationId)!!.result)
            assertEquals("manual", target.status); assertNull(target.targets.fiberG)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun actualAssetsProduceReferencedCardsAndRefreshIsStable() = runBlocking {
        val name = "nutrition-ready-${UUID.randomUUID()}"; val db = open(name)
        try {
            seed(db); val id = board(db); val service = NutritionBoardRepository.create(context, db)
            service.refresh(id)
            val plan = plan(db, id)
            val calc = db.collaborationDao().calculation(plan.targetCalculationId)!!
            assertEquals(2901.8, DomainJson.decode<NutritionResult>(calc.result).targets.energyKcal!!, 1e-6)
            assertEquals(3, meals(plan).size)
            val cards = BoardStore(db).watchCards(id).first()
            cards.flatMap { it.blocks }.filter { it.type == "metric" }.forEach {
                val bound = db.collaborationDao().calculation(it.content.calculationId!!)!!
                if (bound.type == "food_totals") {
                    assertTrue(JSONObject(bound.inputSnapshot).has("intakes"))
                    assertTrue(JSONObject(bound.inputSnapshot).has("targetCalculationId"))
                    assertTrue(bound.inputRefs.contains(plan.id))
                }
            }
            val revision = db.collaborationDao().board(id)!!.revision
            service.refresh(id)
            assertEquals(plan.revision, plan(db, id).revision)
            assertEquals(revision, db.collaborationDao().board(id)!!.revision)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun replacingScalingAndSceneChoiceOnlyChangeOneMeal() = runBlocking {
        val name = "nutrition-replace-${UUID.randomUUID()}"; val db = open(name)
        try {
            seed(db); val id = board(db); val service = NutritionBoardRepository.create(context, db); service.refresh(id)
            val original = plan(db, id); val lunch = meals(original).single { it.slotKey == "lunch" }
            service.act(action(original, lunch, "replace_meal")); service.refresh(id)
            val replaced = plan(db, id)
            assertEquals(meals(original).filter { it.mealId != lunch.mealId }, meals(replaced).filter { it.mealId != lunch.mealId })
            assertNotEquals(lunch.recipeId, meals(replaced).single { it.mealId == lunch.mealId }.recipeId)
            assertNotNull(db.collaborationDao().entitySnapshot("plan", original.id, original.revision))
            val meal = meals(replaced).single { it.mealId == lunch.mealId }
            val delta = if (meal.factor >= 1.25) "-0.25" else "0.25"
            service.act(action(replaced, meal, "scale_meal", delta)); service.refresh(id)
            val scaled = plan(db, id)
            assertNotEquals(meal.items, meals(scaled).single { it.mealId == meal.mealId }.items)
            assertEquals(meals(replaced).filter { it.mealId != meal.mealId }, meals(scaled).filter { it.mealId != meal.mealId })
            service.act(action(scaled, meals(scaled).single { it.mealId == meal.mealId }, "change_scene"), "canteen"); service.refresh(id)
            val changed = plan(db, id)
            assertEquals("canteen", meals(changed).single { it.mealId == meal.mealId }.scene)
            assertEquals(meals(scaled).filter { it.mealId != meal.mealId }, meals(changed).filter { it.mealId != meal.mealId })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun eatingIsIdempotentAndLaterPlanningCannotRewriteIntake() = runBlocking {
        val name = "nutrition-eaten-${UUID.randomUUID()}"; val db = open(name)
        try {
            seed(db); val id = board(db); val service = NutritionBoardRepository.create(context, db); service.refresh(id)
            val original = plan(db, id); val lunch = meals(original).single { it.slotKey == "lunch" }
            val eat = action(original, lunch, "mark_eaten")
            service.act(eat); service.act(eat); service.refresh(id)
            val before = db.recordDao().allIntakes().single()
            assertTrue(before.items.contains("sourceId"))
            AppRepository(db).saveArchive(175f, "1996-01-01", "male", 1.6f, 75f, "gain", date)
            service.refresh(id)
            assertEquals(before, db.recordDao().allIntakes().single())
            assertEquals(lunch, meals(plan(db, id)).single { it.mealId == lunch.mealId })
            assertNotNull(db.collaborationDao().calculation(original.targetCalculationId))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun profileCorrectionRefreshesMetricsWithoutLosingOriginalAnnotationAnchor() = runBlocking {
        val name = "nutrition-anchor-${UUID.randomUUID()}"; val db = open(name)
        try {
            seed(db); val id = board(db); val service = NutritionBoardRepository.create(context, db); service.refresh(id)
            val store = BoardStore(db)
            val protein = store.watchCards(id).first().first { it.title == "蛋白质" }
            store.edit(id) { cards -> cards.map { if (it.id == protein.id) it.copy(isAnnotationMode = true, annotationText = "为什么这样安排") else it } }
            val anchor = store.watchCards(id).first().first { it.id == protein.id }.annotationAnchor!!
            assertTrue(anchor.readableContent.contains("126"))
            val oldProfile = db.profileDao().getProfile()!!
            AppRepository(db).saveArchive(175f, "1996-01-01", "male", 1.6f, 75f, "gain", date)
            service.refresh(id)
            val current = store.watchCards(id).first().first { it.id == protein.id }
            assertTrue(ComponentResolver(db).readable(current).contains("135"))
            assertEquals(anchor, current.annotationAnchor)
            val turn = store.prepare(id)
            val input = db.collaborationDao().input(turn.turn.inputBundleId)!!
            assertTrue(input.annotationsJson.contains("126")); assertTrue(input.annotationsJson.contains("为什么这样安排"))
            assertNotNull(db.collaborationDao().entitySnapshot("profile", oldProfile.id, oldProfile.revision))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun futureWeightsAreExcludedAndReopenUsesSamePlan() = runBlocking {
        val name = "nutrition-date-${UUID.randomUUID()}"; var db = open(name)
        try {
            seed(db)
            db.measurementDao().insert(Measurement("future", "weight", "{\"date\":\"2027-01-01\"}", 999999,
                100f, "kg", "user", null, 1, 999999, 999999))
            val id = board(db); val service = NutritionBoardRepository.create(context, db); service.refresh(id)
            val before = plan(db, id)
            assertEquals(126.0, DomainJson.decode<NutritionResult>(db.collaborationDao().calculation(before.targetCalculationId)!!.result).targets.proteinG!!, 1e-6)
            db.close(); db = open(name)
            NutritionBoardRepository.create(context, db).refresh(id)
            assertEquals(before, plan(db, id))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun descriptionOnlyIntakeIsNeverDisplayedAsZeroNutrients() = runBlocking {
        val name = "nutrition-unknown-${UUID.randomUUID()}"; val db = open(name)
        try {
            seed(db); val id = board(db); val service = NutritionBoardRepository.create(context, db); service.refresh(id)
            val context = db.collaborationDao().board(id)!!.dateContext
            AppRepository(db).recordIntake(IntakeEntry("description", context, "lunch", "item:description", "description", "[]", null,
                "鸡肉饭，分量不清楚", "partial", "user", 1, 1, 1))
            service.refresh(id)
            val card = BoardStore(db).watchCards(id).first().single { it.title == "已记录与目标差额" }
            val rendered = ComponentResolver(db).resolve(card)
            assertTrue(rendered.filter { it.label.startsWith("已核算") }.all { it.text == "尚未核算" })
            val guide = BoardStore(db).watchCards(id).first().first { it.title == "今日参考" }
            assertTrue(ComponentResolver(db).readable(guide).contains("缺项保留"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
