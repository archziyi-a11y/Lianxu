package com.example.lianxu

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lianxu.agent.*
import com.example.lianxu.data.api.*
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.*
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentCollaborationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun scenario(block: suspend (AppDatabase, NutritionBoardRepository, Submission) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            AppRepository(db).saveArchive(175f, "1996-01-01", "male", 1.6f, 70f, "gain", LocalDate.of(2026,10,24))
            val store = BoardStore(db)
            val id = store.open(emptyList(), LocalDate.of(2026,10,24), ZoneId.of("Asia/Shanghai"))
            val repo = NutritionBoardRepository.create(context, db); repo.refresh(id)
            store.edit(id) { cards -> cards.map { when { it.id.endsWith(":training-input") -> it.copy(isAnnotationMode=true, annotationText="今天做了三组卧推，重量没记")
                it.id.endsWith(":intake-input") -> it.copy(isAnnotationMode=true, annotationText="午餐吃了200g熟米饭，调整剩余饮食")
                else -> it } } }
            block(db, repo, store.prepare(id))
        } finally { db.close() }
    }
    private fun operation(work: AgentWorkspace, id: String, kind: String, payload: JSONObject) = JSONObject().put("operationId",id).put("kind",kind).put("clientRef",id)
        .put("payload",payload).put("relatedAnnotationIds",JSONArray().put(work.annotations.getJSONObject(0).getString("annotationId"))).put("reason","综合本轮训练和饮食补充")
    private fun proposal(work: AgentWorkspace, operations: JSONArray) = JSONObject().put("contractVersion","2").put("turnId",work.submission.turn.id).put("taskId",work.submission.task.id)
        .put("stageId",work.stageId).put("operations",operations).put("summary","已整理本轮记录并更新参考")
    private fun training(work: AgentWorkspace) = operation(work,"training-1","create_training", JSONObject().put("kind","strength").put("title","卧推").put("exercises",JSONArray()).put("rawSummary","实际做了三组，重量未知"))
    private suspend fun expectFailure(block: suspend () -> Unit) { var failed=false; try { block() } catch (e: Exception) { failed=true }; assertTrue("expected rejection",failed) }

    @Test fun multiCardStageCalculatesThenCommitsOnceAndSurvivesRefresh() = runBlocking { scenario { db, repo, s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val rice = repo.catalog.search("米饭").first { it.state == "cooked" }
            val intake=operation(w,"lunch","create_intake",JSONObject().put("mode","items").put("mealKey","lunch").put("completeness","partial").put("rawDescription","午餐吃了200g熟米饭")
                .put("items",JSONArray().put(JSONObject().put("foodRef",rice.id).put("grams",200))))
            val ops=JSONArray().put(training(w)).put(intake)
            w.stage(ops)
            assertTrue(db.recordDao().allTrainings().isEmpty()); assertTrue(db.recordDao().allIntakes().isEmpty())
            val result=AgentTools(w).execute("calculate",JSONObject().put("operation","intake_totals").put("input",JSONObject().put("date",w.date)).put("stageId",w.stageId))
            assertTrue(result.getJSONObject("result").getJSONObject("knownSubtotal").getDouble("energyKcal")>0)
            val p=proposal(w,ops); val applier=ProposalApplier(w); val cards=applier.finish(p); applier.commit(cards,p); applier.commit(cards,p)
            assertEquals(1,db.recordDao().allTrainings().size); assertEquals(1,db.recordDao().allIntakes().size)
            assertEquals("completed", db.collaborationDao().turn(s.turn.id)!!.status)
            assertTrue(BoardStore(db).watchCards(s.task.boardId).first().none { it.annotationText != null })
            assertTrue(db.collaborationDao().calculation(result.getString("calculationId"))!!.inputSnapshot.contains("200"))
            repo.refresh(s.task.boardId)
            assertTrue(BoardStore(db).watchCards(s.task.boardId).first().any { it.title=="本轮建议" })
        }
    } }
    @Test fun invalidCardReferenceRollsBackWholeProposal() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val ops=JSONArray().put(training(w)); w.stage(ops)
            val p=proposal(w,ops).put("boardPatch",JSONObject().put("upsertCards",JSONArray().put(JSONObject().put("clientRef","bad").put("title","坏指标").put("span",2)
                .put("blocks",JSONArray().put(JSONObject().put("componentId","bad").put("type","metric").put("content",JSONObject().put("calculationId","invented").put("resultPath","energyKcal")))))))
            expectFailure { ProposalApplier(w).finish(p) }
            assertTrue(db.recordDao().allTrainings().isEmpty()); assertEquals("running",db.collaborationDao().task(s.task.id)!!.status)
            assertTrue(BoardStore(db).watchCards(s.task.boardId).first().any { it.annotationText!=null })
        }
    } }
    @Test fun concurrentProfileChangeRejectsAllWrites() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val ops=JSONArray().put(training(w)); w.stage(ops); val p=proposal(w,ops); val a=ProposalApplier(w); val cards=a.finish(p)
            val profile=db.profileDao().getProfile()!!; db.profileDao().insert(profile.copy(heightCm=180f,revision=profile.revision+1))
            expectFailure { a.commit(cards,p) }; assertTrue(db.recordDao().allTrainings().isEmpty()); assertEquals(180f,db.profileDao().getProfile()!!.heightCm)
        }
    } }
    @Test fun restagingInvalidatesCalculationsAndAlteredStageCannotFinish() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val ops=JSONArray().put(training(w)); w.stage(ops); val first=w.stageId
            val c=AgentTools(w).execute("calculate",JSONObject().put("operation","intake_totals").put("input",JSONObject().put("date",w.date)).put("stageId",first))
            val changed=JSONArray(ops.toString()); changed.getJSONObject(0).getJSONObject("payload").put("title","改过的卧推")
            expectFailure { ProposalApplier(w).finish(proposal(w,changed)) }
            w.stage(changed); assertNotEquals(first,w.stageId); assertNull(w.dao.calculation(c.getString("calculationId")))
            expectFailure { AgentTools(w).execute("calculate",JSONObject().put("operation","intake_totals").put("input",JSONObject().put("date",w.date)).put("stageId",first)) }
        }
    } }
    @Test fun unknownWeightAndRangesRemainUnknownAndRanges() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val op=training(w); op.getJSONObject("payload").put("exercises",JSONArray().put(JSONObject().put("entryId","bench").put("name","卧推")
                .put("summarySets",JSONObject().put("kind","exact").put("value",3)).put("repsPerSet",JSONObject().put("kind","range").put("min",8).put("max",12))))
            val ops=JSONArray().put(op); w.stage(ops); val p=proposal(w,ops); val a=ProposalApplier(w); a.commit(a.finish(p),p)
            val e=DomainJson.decode<RecordedExercise>(JSONArray(db.recordDao().allTrainings().single().exercises).getJSONObject(0).toString())
            assertNull(e.loadKg.value); assertEquals(24.0,summarizeExercise(e).repetitionsMin!!,0.0); assertEquals(36.0,summarizeExercise(e).repetitionsMax!!,0.0)
        }
    } }
    @Test fun estimatedFoodRetainsProvenanceAndSearchHonorsHardExclusions() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val tools=AgentTools(w)
            val estimated=tools.execute("stage_estimated_food",JSONObject().put("clientRef","outside").put("name","外卖炒饭").put("state","prepared").put("basisG",100)
                .put("nutrients",JSONObject().put("energyKcal",180).put("proteinG",5)).put("estimationNote","仅按用户描述估计，油量未知"))
            val intake=operation(w,"outside-intake","create_intake",JSONObject().put("mode","items").put("items",JSONArray().put(JSONObject().put("foodRef",estimated.getString("foodId")).put("grams",300))))
            val ops=JSONArray().put(intake); w.stage(ops)
            val totals=tools.execute("calculate",JSONObject().put("operation","food_totals").put("stageId",w.stageId).put("input",JSONObject().put("items",JSONArray().put(JSONObject().put("foodId",estimated.getString("foodId")).put("grams",300)))))
            val foodSource=JSONObject(w.dao.calculation(totals.getString("calculationId"))!!.inputSnapshot).getJSONArray("foodSources").getJSONObject(0)
            assertEquals("model_estimate",foodSource.getString("sourceId")); assertTrue(foodSource.getString("estimationNote").contains("油量未知"))
            val p=proposal(w,ops); val a=ProposalApplier(w); a.commit(a.finish(p),p)
            val snapshot=JSONArray(db.recordDao().allIntakes().single().items).getJSONObject(0).getJSONObject("foodSnapshot")
            assertEquals("model_estimate",snapshot.getString("sourceId")); assertTrue(snapshot.getString("estimationNote").contains("油量未知")); assertTrue(snapshot.getJSONObject("nutrients").isNull("fatG"))
        }
    } }
    @Test fun failurePreservesDraftAndRetryReusesSubmission() = runBlocking { scenario { db,repo,s ->
        val failing=AgentRunner(context,db,repo.catalog,AgentClient { _,_ -> error("network fixture") })
        expectFailure { failing.run(s) }; BoardStore(db).fail(s,"network fixture")
        val retry=BoardStore(db).prepare(s.task.boardId); assertEquals(s.turn.submissionId,retry.turn.submissionId); assertEquals(s.turn.id,retry.turn.id)
        assertTrue(db.collaborationDao().input(s.turn.inputBundleId)!!.annotationsJson.contains("三组"))
        AgentRunner(context,db,repo.catalog,null).run(retry)
        assertTrue(db.recordDao().allTrainings().isEmpty()); assertEquals("completed",db.collaborationDao().turn(s.turn.id)!!.status)
    } }
    @Test fun scriptedModelReceivesAnchorsAndCanAddCompositeCard() = runBlocking { scenario { db,repo,s ->
        var seen=false
        val client=AgentClient { messages,_ ->
            val input=messages[1].content.toString(); seen=input.contains("cardRevision") && input.contains("三组") && input.contains("午餐")
            val annotations=JSONObject(db.collaborationDao().input(s.turn.inputBundleId)!!.let { "{\"annotations\":${it.annotationsJson}}" }).getJSONArray("annotations")
            val op=JSONObject().put("operationId","analysis").put("kind","create_analysis").put("clientRef","answer").put("relatedAnnotationIds",JSONArray().put(annotations.getJSONObject(0).getString("annotationId")))
                .put("reason","解释本轮补充").put("payload",JSONObject().put("kind","reply").put("summaryText","已结合训练与午餐信息，保留未记录的负重。"))
            val p=JSONObject().put("contractVersion","2").put("turnId",s.turn.id).put("taskId",s.task.id).put("operations",JSONArray().put(op)).put("summary","综合整理")
                .put("boardPatch",JSONObject().put("upsertCards",JSONArray().put(JSONObject().put("clientRef","custom").put("title","训练与饮食一起看").put("span",6)
                    .put("blocks",JSONArray().put(JSONObject().put("componentId","a").put("type","text").put("content",JSONObject().put("contentRef",JSONObject().put("entityRef",JSONObject().put("type","analysis").put("id","answer").put("revision",1)).put("fieldPath","summaryText"))))))))
            ResponseMessage("assistant",null,listOf(ToolCall("finish","function",ToolCallFunction("finish_proposal",p.toString()))))
        }
        AgentRunner(context,db,repo.catalog,client).run(s)
        assertTrue(seen); val card=BoardStore(db).watchCards(s.task.boardId).first().single { it.title=="训练与饮食一起看" }
        assertTrue(ComponentResolver(db).resolve(card).single().text.contains("未记录的负重"))
        repo.refresh(s.task.boardId); assertTrue(BoardStore(db).watchCards(s.task.boardId).first().any { it.id==card.id })
    } }
    @Test fun malformedToolArgumentsDoNotApplyValidJsonPrefix() = runBlocking { scenario { db,repo,s ->
        val client=AgentClient { _,_ ->
            val raw=JSONObject().put("contractVersion","2").put("turnId",s.turn.id).put("taskId",s.task.id).put("operations",JSONArray()).put("summary","不应该被应用").toString()+" trailing-garbage"
            ResponseMessage("assistant",null,listOf(ToolCall("bad","function",ToolCallFunction("finish_proposal",raw))))
        }
        val runner=AgentRunner(context,db,repo.catalog,client)
        expectFailure { runner.run(s) }
        assertEquals(2,runner.requestCount); assertEquals("running",db.collaborationDao().task(s.task.id)!!.status)
        assertNull(db.collaborationDao().receipt(s.turn.submissionId,"apply-board"))
    } }
    @Test fun cancelledNetworkDoesNotCommitAndInputSurvives() = runBlocking { scenario { db,repo,s ->
        val started=CompletableDeferred<Unit>()
        val job=launch { AgentRunner(context,db,repo.catalog,AgentClient { _,_ -> started.complete(Unit); delay(60_000); error("unreachable") }).run(s) }
        started.await(); job.cancelAndJoin()
        assertTrue(db.recordDao().allTrainings().isEmpty()); assertNull(db.collaborationDao().receipt(s.turn.submissionId,"apply-board"))
        assertTrue(BoardStore(db).watchCards(s.task.boardId).first().any { !it.annotationText.isNullOrBlank() })
    } }
    @Test fun lockedMealCannotBeReplacedByModelAndReservedReceiptsAreRejected() = runBlocking { scenario { db,repo,s ->
        val plan=db.collaborationDao().planForDate(s.turn.dateContext)!!
        val meals=DomainJson.decode<MealsDocument>(plan.meals).meals
        db.collaborationDao().putPlan(plan.copy(meals=DomainJson.encode(MealsDocument(meals.mapIndexed { i,m -> if(i==0)m.copy(locked=true) else m }))))
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val op=training(w).put("operationId","apply-board")
            expectFailure { w.stage(JSONArray().put(op)) }
            val before=DomainJson.decode<MealsDocument>(w.dao.plan(plan.id)!!.meals).meals.first()
            val result=AgentTools(w).execute("calculate",JSONObject().put("operation","meal_candidates").put("input",JSONObject().put("date",w.date).put("scope","day").put("scene","home")))
            val candidate=DomainJson.decode<PlannedMeal>(result.getJSONObject("result").getJSONArray("meals").getJSONObject(0).toString())
            assertEquals(before,candidate)
            val replace=operation(w,"bad-replace","replace_plan_meal",JSONObject().put("mealId",before.mealId).put("meal",JSONObject(DomainJson.encode(candidate))).put("calculationId",result.getString("calculationId")))
                .put("targetRef",JSONObject(DomainJson.encode(EntityRef("plan",plan.id,plan.revision))))
            expectFailure { w.applyOperations(JSONArray().put(replace)) }
        }
    } }

    @Test fun assistantCompositionOfExistingCardSurvivesAutomaticRefresh() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val guidance=s.cards.single { it.id.endsWith(":guidance") }
            val analysis=operation(w,"explanation","create_analysis",JSONObject().put("summaryText","保留自定义的卡片组合"))
            val p=proposal(w,JSONArray().put(analysis)).put("boardPatch",JSONObject().put("upsertCards",JSONArray().put(JSONObject().put("cardId",guidance.id).put("revision",guidance.revision)
                .put("title","训练和餐食的共同解释").put("span",3).put("blocks",JSONArray().put(JSONObject().put("componentId","explanation").put("type","text")
                    .put("content",JSONObject().put("contentRef",JSONObject().put("entityRef",JSONObject().put("type","analysis").put("id","explanation").put("revision",1)).put("fieldPath","summaryText"))))))))
            val a=ProposalApplier(w); a.commit(a.finish(p),p)
            repo.refresh(s.task.boardId)
            val card=BoardStore(db).watchCards(s.task.boardId).first().single { it.id==guidance.id }
            assertEquals("训练和餐食的共同解释",card.title); assertEquals(CardSpan.MEDIUM,card.span)
            assertEquals("保留自定义的卡片组合",ComponentResolver(db).resolve(card).single().text)
        }
    } }
    @Test fun stageTransportShorthandExpandsFrozenOperationsExactly() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val op=training(w); w.stage(JSONArray().put(op))
            val p=proposal(w,JSONArray()); val a=ProposalApplier(w); a.commit(a.finish(p),p)
            assertEquals(1,db.recordDao().allTrainings().size)
            assertEquals(op.getJSONObject("payload").getString("rawSummary"),db.recordDao().allTrainings().single().rawSummary)
            assertEquals(1,p.getJSONArray("operations").length())
        }
    } }

    @Test fun dependenciesAreOrderedAndCyclesRejectedBeforeSaving() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            val first=operation(w,"first","create_analysis",JSONObject().put("summaryText","第一条").put("inputRefs",JSONArray().put(JSONObject().put("type","analysis").put("id","second").put("revision",1))))
            val second=operation(w,"second","create_analysis",JSONObject().put("summaryText","第二条"))
            w.applyOperations(JSONArray().put(first).put(second))
            assertNotNull(w.dao.analysis(w.mapping["first"]!!))
            second.getJSONObject("payload").put("inputRefs",JSONArray().put(JSONObject().put("type","analysis").put("id","first").put("revision",1)))
            expectFailure { w.stage(JSONArray().put(first).put(second)) }
            assertTrue(db.collaborationDao().allAnalyses().none { it.summaryText in listOf("第一条","第二条") })
        }
    } }

    @Test fun cardRevisionFromStagedPreviewRemainsValidAfterFinalMealCalculation() = runBlocking { scenario { db,repo,s ->
        AgentWorkspace.open(context,db,s,repo.catalog).use { w ->
            w.stage(JSONArray().put(training(w)))
            val preview=w.dao.cards(s.task.boardId).map { DomainJson.decode<DashboardCard>(it.data) }.single { it.id.endsWith(":meal:dinner") }
            val plan=w.dao.planForDate(w.dateContext)!!
            val candidate=AgentTools(w).execute("calculate",JSONObject().put("operation","meal_candidates").put("stageId",w.stageId)
                .put("input",JSONObject().put("date",w.date).put("scope","meal").put("mealKey","dinner").put("scene","home")))
            val meals=candidate.getJSONObject("result").getJSONArray("meals")
            val dinner=(0 until meals.length()).map { meals.getJSONObject(it) }.single { it.getString("mealId")=="dinner" }
            val op=operation(w,"replace-dinner","replace_plan_meal",JSONObject().put("mealId","dinner").put("meal",dinner).put("calculationId",candidate.getString("calculationId")))
                .put("targetRef",JSONObject().put("type","plan").put("id",plan.id).put("revision",plan.revision))
            val patch=JSONObject().put("cardId",preview.id).put("revision",preview.revision).put("title","调整后的晚餐").put("span",6)
                .put("blocks",JSONArray().put(JSONObject().put("componentId","meal").put("type","list").put("content",JSONObject().put("entityRefs",JSONArray().put(JSONObject(DomainJson.encode(EntityRef("plan",plan.id,plan.revision))))).put("mealId","dinner"))))
            val p=proposal(w,JSONArray().put(op)).put("boardPatch",JSONObject().put("upsertCards",JSONArray().put(patch)).put("orderedCardIds",JSONArray().put(preview.id)))
            val a=ProposalApplier(w)
            val bad=JSONObject(p.toString());bad.getJSONObject("boardPatch").put("orderedCardIds",JSONArray().put("nonexistent-card"))
            expectFailure { a.finish(bad) }
            val cards=a.finish(p);a.commit(cards,p)
            assertEquals(preview.id,cards.first().id);assertTrue(cards.size>=s.cards.size)
            val final=cards.single { it.id==preview.id }
            assertTrue(final.revision>preview.revision)
            assertEquals(db.collaborationDao().plan(plan.id)!!.revision,final.blocks.single().content.entityRefs.single().revision)
        }
    } }

    @Test fun recordedLegacyProposalCannotReplaceAPastMeal() = runBlocking { scenario { db,repo,s ->
        val fixture=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("flash-round-trip.json").bufferedReader().use { it.readText() })
        val trace=fixture.getJSONArray("trace")
        val replacements=mutableMapOf<String,String>()
        val sourceCards=fixture.getJSONArray("cards")
        for(i in 0 until sourceCards.length()) {
            val original=DomainJson.decode<DashboardCard>(sourceCards.getJSONObject(i).toString())
            val current=s.cards.firstOrNull { it.id==original.id } ?: continue
            original.blocks.forEach { block ->
                val match=current.blocks.firstOrNull { it.componentId==block.componentId } ?: return@forEach
                val oldCalc=block.content.calculationId; val newCalc=match.content.calculationId
                if(oldCalc!=null && newCalc!=null) replacements[oldCalc]=newCalc
                val oldAnalysis=block.content.contentRef?.entityRef?.id; val newAnalysis=match.content.contentRef?.entityRef?.id
                if(oldAnalysis!=null && newAnalysis!=null) replacements[oldAnalysis]=newAnalysis
            }
        }
        val annotations=JSONArray(db.collaborationDao().input(s.turn.inputBundleId)!!.annotationsJson)
        val originalOps=JSONObject(trace.getJSONObject(0).getString("arguments")).getJSONArray("operations")
        for(i in 0 until originalOps.length()) {
            val op=originalOps.getJSONObject(i)
            val index=if(op.getString("kind")=="create_training")0 else 1
            val related=op.getJSONArray("relatedAnnotationIds")
            for(j in 0 until related.length()) replacements[related.getString(j)]=annotations.getJSONObject(index).getString("annotationId")
        }
        fun replace(value: Any?): Any? = when(value) {
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> out.put(key,replace(value.get(key))) } }
            is JSONArray -> JSONArray().also { out -> for(i in 0 until value.length())out.put(replace(value.get(i))) }
            is String -> replacements[value] ?: value
            else -> value
        }
        var step=0
        val client=AgentClient { messages,_ ->
            if(step==0) {
                step++; ResponseMessage("assistant",null,listOf(ToolCall("stage","function",ToolCallFunction("stage_records",replace(JSONObject(trace.getJSONObject(0).getString("arguments"))).toString()))))
            } else if(step==1) {
                step++
                val result=JSONObject(messages.last().content as String).getJSONObject("data")
                replacements[JSONObject(trace.getJSONObject(1).getString("arguments")).getString("stageId")]=result.getString("stageId")
                val calls=(1..2).map { n -> ToolCall("calc$n","function",ToolCallFunction("calculate",replace(JSONObject(trace.getJSONObject(n).getString("arguments"))).toString())) }
                ResponseMessage("assistant",null,calls)
            } else {
                val results=messages.filter { it.role=="tool" && it.tool_call_id?.startsWith("calc")==true }.map { JSONObject(it.content as String).getJSONObject("data") }
                for(i in results.indices) {
                    val old=trace.getJSONObject(i+1).getJSONObject("result").getJSONObject("data")
                    replacements[old.getString("calculationId")]=results[i].getString("calculationId")
                    val oldRefs=old.getJSONArray("inputRefs"); val current=results[i].getJSONArray("inputRefs")
                    for(j in 0 until oldRefs.length()) {
                        val before=oldRefs.getJSONObject(j)
                        val after=(0 until current.length()).map { current.getJSONObject(it) }.first { it.getString("type")==before.getString("type") }
                        replacements[before.getString("id")]=after.getString("id")
                    }
                }
                val p=replace(JSONObject(trace.getJSONObject(3).getString("arguments"))) as JSONObject
                p.put("turnId",s.turn.id).put("taskId",s.task.id)
                step++; ResponseMessage("assistant",null,listOf(ToolCall("finish","function",ToolCallFunction("finish_proposal",p.toString()))))
            }
        }
        expectFailure { AgentRunner(context,db,repo.catalog,client).run(s) }
        assertTrue(db.recordDao().allTrainings().isEmpty()); assertTrue(db.recordDao().allIntakes().isEmpty())
        assertNull(db.collaborationDao().receipt(s.turn.submissionId,"apply-board"))
        assertTrue(BoardStore(db).watchCards(s.task.boardId).first().any { it.annotationText!=null })
    } }

}
