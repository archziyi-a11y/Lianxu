package com.example.lianxu.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lianxu.archive.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class,ExperimentalLayoutApi::class)
@Composable fun ArchiveScreen(modifier:Modifier=Modifier,viewModel:ArchiveViewModel=viewModel(),onRetry:(String)->Unit={},onShowToday:()->Unit={},initialSection:String="首页",onSectionConsumed:()->Unit={},onSettings:()->Unit={},todayModel:TodayViewModel?=null){
 val state by viewModel.dashboard.collectAsState()
 val shortViewport=LocalConfiguration.current.screenHeightDp<450
 var page by rememberSaveable { mutableStateOf("首页") }
 var category by rememberSaveable { mutableStateOf<String?>(null) }
 var options by remember { mutableStateOf<ArchiveDisplay?>(null) }
 var detail by remember { mutableStateOf<ArchiveDisplay?>(null) }
 var importMethod by remember { mutableStateOf(false) }
 var packageText by rememberSaveable { mutableStateOf("") }
 val message by viewModel.message.collectAsState();val busy by viewModel.busy.collectAsState()
 val clipboard=LocalClipboardManager.current
 BackHandler(page!="首页" || category!=null){page="首页";category=null}
 LaunchedEffect(initialSection){if(initialSection!="首页"){page=initialSection;onSectionConsumed()}}
 if(page=="安排"){ArrangementScreen(viewModel){page="首页"};return}
 if(page=="设置" && todayModel!=null)SettingsPanel(todayModel){page="首页"}
 val management=page in listOf("记录","阶段","历日","资料","备份")
 if(management){ArchiveManagementScreen(modifier,viewModel,onRetry,onShowToday,initialSection=page,onBack={page="首页"});return}
 if(importMethod)AlertDialog(onDismissRequest={if(!busy)importMethod=false},title={Text("导入分析方法")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("粘贴与聊天助手制作的方法包文本")
  OutlinedTextField(packageText,{packageText=it},label={Text("方法包 JSON")},modifier=Modifier.heightIn(min=160.dp,max=300.dp))
  message?.let { error->Text(error,style=MaterialTheme.typography.bodySmall);TextButton(onClick={clipboard.setText(AnnotatedString(error))}){Text("复制错误给助手")} }
 }},confirmButton={TextButton(enabled=!busy,onClick={viewModel.importMethod(packageText){packageText="";importMethod=false}}){Text("导入")}},dismissButton={TextButton(onClick={importMethod=false}){Text("取消")}})
 options?.let { item->AlertDialog(onDismissRequest={options=null},title={Text("展示选项")},text={Column{
  item.topics.forEach { key->val topic=state.topics.find { it.id==key }
   TextButton(onClick={viewModel.place(key,if(item.priority)"archive" else "priority");options=null}){Text(if(item.priority)"归档 ${topic?.title.orEmpty()}" else "优先展示 ${topic?.title.orEmpty()}")}
  }
 }},confirmButton={TextButton(onClick={options=null}){Text("关闭")}}) }
 detail?.let { item->AlertDialog(onDismissRequest={detail=null},title={Text(item.card.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  state.resolved[item.card.id].orEmpty().forEach { block->ComponentView(block,false,{_,_->},{}) }
  Text("计算依据",style=MaterialTheme.typography.titleSmall)
  item.topics.forEach { key->Text(state.sources[key].orEmpty(),style=MaterialTheme.typography.bodySmall) }
 }},confirmButton={TextButton(onClick={detail=null}){Text("关闭")}}) }
 Scaffold(topBar={
  if(page!="首页" || category!=null)TopAppBar(title={Text(if(page=="方法")"分析方法" else category ?: "档案")},navigationIcon={TextButton(onClick={page="首页";category=null}){Text("返回")}})
  else Column(Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
   if(!shortViewport)Text("练序  /  ARCHIVE",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text("档案",Modifier.weight(1f),style=if(shortViewport)MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall);TextButton(onClick={page="资料"}){Text("资料与目标 ↗")} }
   if(!shortViewport)Text("从积累中，看见变化。",Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.labelLarge)
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
  }
 }) { padding->
  val wideCards=LocalConfiguration.current.screenWidthDp<350 || LocalDensity.current.fontScale>1.25f
  LazyVerticalGrid(columns=GridCells.Fixed(6),modifier=modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   if(page=="方法"){
    item(span={GridItemSpan(6)}){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text("与你的聊天助手讨论要观察的问题，再将生成的方法包带回来。")
     Button(onClick={viewModel.copyMethodPrompt { clipboard.setText(AnnotatedString(it)) }}){Text("复制制作提示词")}
     OutlinedButton(onClick={viewModel.clearMessage();importMethod=true}){Text("粘贴导入方法包")}
     message?.let{Text(it)}
    }}
    items(state.methods,key={it.id},span={GridItemSpan(6)}){m->Card{Column(Modifier.padding(16.dp)){
     val definition=remember(m.packageJson){MethodRegistry.parse(m.packageJson)}
     Text(definition.title,style=MaterialTheme.typography.titleMedium);Text("${definition.category} · v${m.version} · ${if(m.builtin)"内置" else "我的方法"}",style=MaterialTheme.typography.labelSmall)
     Text(definition.question);TextButton(onClick={viewModel.enableMethod(m.id,!m.enabled)}){Text(if(m.enabled)"停用" else "启用")}
    }}}
   }else{
    if(category==null)item(span={GridItemSpan(6)}) { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
     OutlinedButton(onClick={page="记录"}){Text("全部记录")};OutlinedButton(onClick={page="安排"}){Text("习惯与跟进")}
    } }
    item(span={GridItemSpan(6)}){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
     if(state.overview.isNotBlank())Text(state.overview,style=MaterialTheme.typography.titleMedium)
     if(state.updatedAt>0)Text("更新于 ${DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(state.updatedAt).atZone(ZoneId.systemDefault()))}",style=MaterialTheme.typography.labelSmall)
     if(state.scopes.size>1)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){state.scopes.forEach { scope->FilterChip(selected=state.scope==scope,onClick={viewModel.selectScope(scope)},label={Text(scopeLabel(scope))})}}
     message?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
    }}
    val displays=if(category==null)state.cards else state.catalog.filter { display->display.topics.any { key->state.topics.any { it.id==key && it.category==category } } }
    if(displays.isEmpty() && category==null)item(span={GridItemSpan(6)}){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("从已有记录开始，逐渐看见变化。",style=MaterialTheme.typography.bodyLarge);TextButton(onClick=onShowToday){Text("记录今天 →")};TextButton(onClick={page="资料"}){Text("完善资料与目标")}}}
    items(displays,key={it.card.id},span={GridItemSpan(if(wideCards)6 else it.card.span.span)}){display->
     Card(modifier=Modifier.animateItem().fillMaxWidth().combinedClickable(onClick={detail=display},onLongClick={options=display},onLongClickLabel="展示选项"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
      Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
       if(display.priority)Text("优先展示",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
       Text(display.card.title,style=MaterialTheme.typography.titleSmall)
       val blocks=state.resolved[display.card.id].orEmpty()
       blocks.filterNot { it.type=="text" && blocks.any { chart->chart.type=="trend" && chart.text==it.text } }.forEach { block->ComponentView(block,display.card.span.span==2,{_,_->},{}) }
      }
     }
    }
    if(category==null)item(span={GridItemSpan(6)}){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     HorizontalDivider();Text("长期资料",style=MaterialTheme.typography.titleSmall)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){MethodRegistry.categories.forEach { name->TextButton(onClick={category=name}){Text(name)}}}
     FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("分析历史" to "阶段","历日方案" to "历日","分析方法" to "方法","备份恢复" to "备份","连接与显示" to "设置").forEach { (label,target)->TextButton(onClick={page=target}){Text(label)}}}
    }}
   }
   item(span={GridItemSpan(6)}){Spacer(Modifier.height(24.dp))}
  }
 }
}
internal fun scopeLabel(scope:String)=when(scope){"current_phase"->"当前阶段";"7d"->"近一周";"28d"->"近四周";"90d"->"近三个月";else->"当前积累"}
