package com.example.lianxu.archive

import android.content.Context
import androidx.room.withTransaction
import com.example.lianxu.data.db.AppDatabase
import com.example.lianxu.data.entity.ArchiveMethodEntity
import org.json.*
import java.security.MessageDigest

fun fingerprint(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){ "%02x".format(it.toInt() and 255) }
fun JSONArray.rows()=(0 until length()).map { getJSONObject(it) }
fun JSONArray.texts()=(0 until length()).map { getString(it) }
fun JSONObject.fields(vararg allowed:String){ require(keys().asSequence().toSet().all { it in allowed }) { "未知字段；允许：${allowed.joinToString()}" } }

data class AnalysisMethod(val id:String,val version:Int,val title:String,val category:String,val question:String,val operator:String,val metric:String?,val windows:List<String>,val minPoints:Int,val minDays:Int,val minPerGroup:Int,val minEffect:Double,val rollingDays:Int,val guide:String,val raw:String)

class MethodRegistry(private val context:Context,private val db:AppDatabase){
 companion object {
  val operators=mapOf("series" to "测量最新值与序列", "rolling_mean" to "测量平滑趋势", "calendar" to "训练日历及频率", "distribution" to "规范动作与已识别部位的组数分布", "exercise_progress" to "同动作、器械、负重口径与次数的负重进展", "interval_compare" to "训练间隔与后续表现变化分组", "phase_compare" to "相邻目标阶段同口径比较", "training_weight" to "训练频率与体重变化配对", "intake_summary" to "已记录摄入与食物构成", "nutrition_history" to "已有营养目标沿革", "phase_summary" to "当前阶段实际记录摘要", "scene_compare" to "明确记录的饮食场景比较")
  val categories=listOf("身体","训练","营养","阶段","发现")
  val metrics=mapOf("weight" to "kg","body_fat" to "%","waist" to "cm","height" to "cm")
  fun parse(raw:String):AnalysisMethod {
   require(raw.toByteArray().size<=64*1024){"方法包超过64KB"}
   // Strict complete JSON, including rejection of trailing JSON or prose.
   val parsed=com.example.lianxu.engine.DomainJson.moshi.adapter(Any::class.java).fromJson(raw)
   require(parsed is Map<*,*>){"方法包必须是一个完整JSON对象"}
   val p=JSONObject(raw);p.fields("schemaVersion","manifest","guide")
   require(p.opt("schemaVersion") is Number && p.getDouble("schemaVersion")==1.0){"schemaVersion须为1"}
   val m=p.getJSONObject("manifest");m.fields("id","version","title","category","question","operator","metric","windows","eligibility","parameters","refresh")
   val id=m.getString("id");require(Regex("[a-z][a-z0-9_.-]{2,79}").matches(id)){"manifest.id格式不正确"}
   val version=m.getInt("version");require(m.opt("version") is Number && m.getDouble("version")==version.toDouble() && version>0){"manifest.version须为正整数"}
   val op=m.getString("operator");require(op in operators){"manifest.operator不支持：$op；可用：${operators.keys.joinToString()}"}
   val metric=m.optString("metric").takeIf { it.isNotBlank() };require(metric==null || metric in metrics){"manifest.metric不支持"}
   require(op !in listOf("series","rolling_mean") || metric!=null){"测量方法需要manifest.metric"}
   val w=m.getJSONArray("windows").texts();require(w.isNotEmpty() && w.distinct().size==w.size && w.all { it in listOf("accumulated","current_phase","7d","28d","90d") }){"manifest.windows不支持"}
   val e=m.getJSONObject("eligibility");e.fields("minPoints","minDays","minPerGroup","minEffect")
   for(key in listOf("minPoints","minDays"))require(e.opt(key) is Number && e.getDouble(key)==e.getInt(key).toDouble()){ "eligibility.${key}须为整数" }
   if(e.has("minPerGroup"))require(e.opt("minPerGroup") is Number && e.getDouble("minPerGroup")==e.getInt("minPerGroup").toDouble()){ "eligibility.minPerGroup须为整数" }
   if(e.has("minEffect"))require(e.opt("minEffect") is Number){"eligibility.minEffect须为数值"}
   val n=e.getInt("minPoints");val days=e.getInt("minDays");val group=e.optInt("minPerGroup",4);val effect=e.optDouble("minEffect",0.05)
   require(n in 1..10000 && days in 0..3650 && group in 3..1000 && effect.isFinite() && effect in 0.0..1.0){"eligibility门槛不在支持范围"}
   if(op in listOf("interval_compare","training_weight","scene_compare"))require(n>=8 && days>=14 && group>=4){"关联比较至少8个观测、14天覆盖、每组4个独立样本"}
   val a=m.getJSONObject("parameters");a.fields("rollingDays");if(a.has("rollingDays"))require(a.opt("rollingDays") is Number && a.getDouble("rollingDays")==a.getInt("rollingDays").toDouble()){"parameters.rollingDays须为整数"};val rolling=a.optInt("rollingDays",7);require(rolling in 2..90)
   require(m.getString("refresh")=="on_effective_change"){"refresh须为on_effective_change"}
   val title=m.getString("title");val category=m.getString("category");val question=m.getString("question");val guide=p.getString("guide")
   require(title.length in 1..80 && category in categories && question.length in 1..500 && guide.length in 1..8000){"方法名称、分类、问题或说明不正确"}
   return AnalysisMethod(id,version,title,category,question,op,metric,w,n,days,group,effect,rolling,guide,p.toString())
  }
 }
 suspend fun installBuiltins(){
  val existing=db.archiveDao().methods().associateBy { it.id }
  val root="analysis-methods"
  for(name in context.assets.list(root).orEmpty()){
   val manifest=JSONObject(context.assets.open("$root/$name/manifest.json").bufferedReader().use{it.readText()})
   val guide=context.assets.open("$root/$name/guide.md").bufferedReader().use{it.readText()}
   val p=parse(JSONObject().put("schemaVersion",1).put("manifest",manifest).put("guide",guide).toString())
   val old=existing[p.id];if(old==null || old.builtin && old.version<p.version)db.archiveDao().putMethod(ArchiveMethodEntity(p.id,p.version,p.raw,true,true,System.currentTimeMillis()))
  }
 }
 suspend fun importPackage(raw:String):AnalysisMethod = db.withTransaction {
  require(raw.toByteArray().size<=256*1024){"方法集合超过256KB"}
  val decoded=com.example.lianxu.engine.DomainJson.moshi.adapter(Any::class.java).fromJson(raw);require(decoded is Map<*,*>)
  val envelope=JSONObject(raw)
  val packages=if(envelope.has("packages")){
   envelope.fields("schemaVersion","packages");require(envelope.opt("schemaVersion") is Number && envelope.getDouble("schemaVersion")==1.0)
   val children=envelope.getJSONArray("packages");require(children.length() in 1..8){"一次导入1至8种方法"};children.rows().map { parse(it.toString()) }
  }else listOf(parse(raw))
  require(packages.map { it.id }.distinct().size==packages.size){"集合中的methodId重复"}
  val existing=db.archiveDao().methods().associateBy { it.id }
  packages.forEach { m->
   val old=existing[m.id];require(old?.builtin!=true){"此id属于内置方法，请为自己的方法使用user.前缀"}
   require(m.id.startsWith("user.")){"用户方法id必须以user.开头"}
   require(old==null || m.version>old.version){"同id升级需增加manifest.version"}
  }
  packages.forEach { m->db.archiveDao().putMethod(ArchiveMethodEntity(m.id,m.version,m.raw,false,true,System.currentTimeMillis())) }
  packages.first()
 }
 suspend fun methods()=db.archiveDao().methods().filter { it.enabled }.map { parse(it.packageJson) }
 suspend fun authoringPrompt():String {
  val example=db.archiveDao().methods().first { it.id=="builtin.weight-series" }.let { JSONObject(it.packageJson) }
  example.getJSONObject("manifest").put("id","user.my-weight-series").put("version",1)
  return """你帮助用户设计练序分析方法包。先与用户讨论要回答的问题，确定数据、可比口径和样本要求，再输出一个完整JSON对象，不加代码围栏或尾随文字。
应用schemaVersion=1。可用算子：${JSONObject(operators)}。测量字段与单位：${JSONObject(metrics)}。
时间范围：accumulated/current_phase/7d/28d/90d。分类：${categories.joinToString()}。
训练口径使用规范动作、器械、侧别、负重口径；缺重量不填零。饮食仅分析已记录部分。关联方法最低8观测、14天、每组4独立样本，并检查分时段稳定性，不解释为因果。
用户包id必须user.开头，升级增加version。eligibility字段minPoints/minDays/minPerGroup/minEffect；parameters仅支持rollingDays；refresh固定on_effective_change。仅使用上述算子与字段，不编造能力。guide交代依据、适用范围、反例及恰当解释。
需要组合多个观察角度时，输出集合格式 {"schemaVersion":1,"packages":[完整单方法包,...]}，最多8种，各自使用不同user.id。应用按每个方法的条件独立计算，档案助手可以组合其结果。
各算子输入：series/rolling_mean使用measurement.metric/value/unit/observed；calendar使用training.dateContext/kind；distribution使用training.exercises规范动作和已知组数；exercise_progress/interval_compare使用同动作、明确equipment、loadBasis、side、精确reps与loadKg；phase_compare使用相邻GoalPhase与体重；training_weight使用不重叠双周训练频率与周均体重；intake_summary使用IntakeEntry明细或手动营养及coverageKey，遵守每天／餐次覆盖；scene_compare还要求origin.scene明确home/canteen/takeout，不能从菜名猜测场景；nutrition_history读取已保存营养目标；phase_summary按窗口统计实际资料。
完整有效示例（严格沿用字段结构）：
${example.toString(2)}
不支持的问题说明欠缺哪种数据或算子，并提出已支持的替代方法，不伪造可执行包。收到应用报错后按字段位置修正，返回完整包。"""
 }
}
