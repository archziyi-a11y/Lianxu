package com.example.lianxu.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.lianxu.data.model.*
import com.example.lianxu.data.repository.*
import com.example.lianxu.ui.theme.Signal
import com.example.lianxu.ui.theme.Ink
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(modifier:Modifier=Modifier,viewModel:TodayViewModel=viewModel(),onManage:()->Unit={}) {
 val cards by viewModel.cards.collectAsState(); val resolved by viewModel.resolved.collectAsState()
 val processing by viewModel.isProcessing.collectAsState(); val progress by viewModel.taskProgress.collectAsState()
 val notice by viewModel.notice.collectAsState(); val boardDate by viewModel.boardDate.collectAsState()
 val importing by viewModel.isImporting.collectAsState(); val local by viewModel.demoMode.collectAsState()
 val source by viewModel.sourceDetails.collectAsState()
 var settings by rememberSaveable { mutableStateOf(false) };var timer by rememberSaveable { mutableStateOf(false) }
 var requestedInput by rememberSaveable { mutableStateOf<String?>(null) }
 var add by rememberSaveable { mutableStateOf(false) };var imageCard by rememberSaveable { mutableStateOf<String?>(null) }
 val focus=LocalFocusManager.current
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri->uri?.let { imageCard?.let { id->viewModel.updateAnnotationImage(id,it.toString()) } } }
 BackHandler(cards.any { it.isAnnotationMode } && !processing) { focus.clearFocus();viewModel.closeAnnotations() }
 if(settings) SettingsPanel(viewModel) { settings=false }
 if(timer)ModalBottomSheet(onDismissRequest={timer=false}) { Column(Modifier.padding(24.dp).navigationBarsPadding()) { Text("组间休息",style=MaterialTheme.typography.titleLarge);RestTimerControls() } }
 if(add)AlertDialog(onDismissRequest={add=false},title={Text("新建输入组件")},text={Column {
  listOf("training" to "运动记录","measurement" to "身体测量","intake" to "饮食补充","note" to "自由描述 / 图片").forEach { (kind,label)->TextButton(onClick={requestedInput=viewModel.addInput(kind);add=false},modifier=Modifier.fillMaxWidth()){Text(label)} }
 }},confirmButton={TextButton(onClick={add=false}){Text("返回")}})
 source?.let { AlertDialog(onDismissRequest=viewModel::dismissSource,title={Text("食物与计算依据")},text={Text(it,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick=viewModel::dismissSource){Text("关闭")}}) }
 val count=cards.count(ActiveInputRepository::hasInput)
 val gridState=rememberLazyGridState()
 // Room can publish the reply after the processing flag falls. Re-anchor on the new first card too.
 LaunchedEffect(processing,cards.firstOrNull()?.id){if(!processing)gridState.scrollToItem(0)}
 LaunchedEffect(requestedInput,cards.any { it.id==requestedInput }) { val index=cards.indexOfFirst { it.id==requestedInput };if(requestedInput!=null && index>=0){gridState.scrollToItem(index+if(notice!=null)1 else 0);requestedInput=null} }
 val shortViewport=LocalConfiguration.current.screenHeightDp<450
 val compactHeader=LocalConfiguration.current.screenWidthDp<350 || LocalDensity.current.fontScale>1.25f
 val keyboardOpen=WindowInsets.ime.getBottom(LocalDensity.current)>0
 var initialized by remember(boardDate) { mutableStateOf(false) }
 LaunchedEffect(boardDate,cards.isNotEmpty()){if(cards.isNotEmpty() && !initialized){gridState.scrollToItem(0);initialized=true}}
 Column(modifier.fillMaxSize().imePadding()) {
  Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    if(!keyboardOpen && !shortViewport)Text("练序  /  LIANXU",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(compactHeader && !keyboardOpen && !shortViewport) { Text("今日",style=MaterialTheme.typography.displaySmall);Text(boardDate.takeLast(5).replace('-','.'),style=MaterialTheme.typography.labelLarge,maxLines=1) }
    else Row(verticalAlignment=Alignment.Bottom) { Text("今日",style=if(keyboardOpen || shortViewport)MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall);Text(boardDate.takeLast(5).replace('-','.'),Modifier.padding(start=12.dp,bottom=4.dp),style=if(keyboardOpen || shortViewport)MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,maxLines=1) }
   }
   IconButton(onClick={add=true}){Icon(Icons.Default.Add,contentDescription="新建组件")}
   IconButton(onClick={timer=true}){Icon(Icons.Default.Timer,contentDescription="休息计时")}
   IconButton(onClick={settings=true}){Icon(Icons.Default.Tune,contentDescription="设置")}
  }
  if(!keyboardOpen && !shortViewport)FlowRow(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Text(if(count>0)"$count 张待发送" else "今天，由你开始。",style=MaterialTheme.typography.labelLarge)
   Text("长按卡片 · 原处补充",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
  HorizontalDivider(Modifier.padding(horizontal=20.dp),color=MaterialTheme.colorScheme.outlineVariant)
  Box(Modifier.weight(1f).fillMaxWidth()) {
   androidx.compose.animation.AnimatedVisibility(visible=!processing,enter=slideInVertically(tween(420,easing=FastOutSlowInEasing)){it}+fadeIn(tween(260)),exit=slideOutVertically(tween(260,easing=FastOutLinearInEasing)){-it}+fadeOut(tween(220)),modifier=Modifier.fillMaxSize()) {
    val configuration=LocalConfiguration.current
    val wideCards=configuration.screenWidthDp<350 || LocalDensity.current.fontScale>1.25f
    LazyVerticalGrid(state=gridState,columns=GridCells.Fixed(6),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxSize()) {
     if(notice!=null)item(span={GridItemSpan(6)}) { Surface(color=MaterialTheme.colorScheme.secondaryContainer) { Text(notice!!,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall) } }
     items(cards,key={it.id},span={GridItemSpan(if(wideCards)6 else it.span.span)}) { card->
      DashboardCardItem(card=card,resolved=resolved[card.id].orEmpty(),onLongPress={viewModel.toggleAnnotationMode(card.id)},onTap={},onSwipeUp={imageCard=card.id;picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
       onAction={a,v->viewModel.performAction(a,v)},onChoice={id,key->viewModel.selectAssistantChoice(card.id,id,key)},onText={viewModel.updateAnnotationText(card.id,it)},onInput={k,v->viewModel.updateInput(card.id,k,v)},onClear={viewModel.clearInput(card.id)},onRemoveImage={viewModel.removeImage(card.id)},onManage=onManage,
       onSkip={card.invitationId?.let { viewModel.skipQuestion(card.id,it) }},modifier=Modifier.animateItem())
     }
     item(span={GridItemSpan(6)}) { OutlinedButton(onClick={add=true},shape=RoundedCornerShape(2.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Icon(Icons.Default.Add,null);Text("新建组件",Modifier.padding(start=8.dp)) } }
     item(span={GridItemSpan(6)}) { Text(if(local)"离线记录可用 · 设置中连接 AI" else "不必填满。文字、图片和控件可以一起发送。",Modifier.padding(vertical=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
   }
   if(processing)Column(Modifier.align(Alignment.Center).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
    Icon(Icons.Default.North,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.primary)
    Text("正在整理本轮",style=MaterialTheme.typography.headlineMedium)
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(progress ?: "正在核对输入与依据",style=MaterialTheme.typography.bodyMedium)
    Text("完成后会回到这里。输入已经保留。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick=viewModel::cancelSubmission){Text("停止处理 · 保留输入")}
   }
  }
  if(count>0 && !processing)Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal=16.dp,vertical=6.dp)) {
   Button(onClick={focus.clearFocus();viewModel.submitAnnotations()},enabled=!importing,shape=RoundedCornerShape(2.dp),colors=ButtonDefaults.buttonColors(containerColor=Signal,contentColor=Ink),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {
    Text(if(importing)"正在保存图片" else if(local)"保存本轮 · $count 张" else "发送本轮 · $count 张",Modifier.weight(1f));Icon(Icons.Default.North,contentDescription="统一发送")
   }
   if(!local && !keyboardOpen)TextButton(onClick={focus.clearFocus();viewModel.submitAnnotations(localOnly=true)},modifier=Modifier.align(Alignment.CenterHorizontally)){Text("仅保存在本机",style=MaterialTheme.typography.labelMedium)}
  }
 }
}

@Composable internal fun SettingsPanel(viewModel:TodayViewModel,onClose:()->Unit) {
 var key by remember { mutableStateOf("") };LaunchedEffect(Unit){viewModel.getApiKey { key=it.orEmpty() }}
 AlertDialog(onDismissRequest=onClose,title={Text("连接与显示")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Text("连接后，AI 综合本轮内容组织回复。密钥仅保存在设备中。")
  OutlinedTextField(key,{key=it},label={Text("DeepSeek 密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
  Text("明暗与减少动态效果跟随 Android 系统设置。",style=MaterialTheme.typography.bodySmall)
 }},confirmButton={TextButton(onClick={viewModel.saveApiKey(key);onClose()}){Text("保存")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalLayoutApi::class)
@Composable
fun DashboardCardItem(card:DashboardCard,onLongPress:()->Unit,onTap:()->Unit,onSwipeUp:()->Unit,resolved:List<ResolvedComponent> = emptyList(),onAction:(CardAction,String?)->Unit={_,_->},onChoice:(String,String)->Unit={_,_->},modifier:Modifier=Modifier,
 onText:(String)->Unit={},onInput:(String,String)->Unit={_,_->},onClear:()->Unit={},onRemoveImage:()->Unit={},onManage:()->Unit={},onSkip:()->Unit={}) {
 var editor by rememberSaveable(card.id) { mutableStateOf(false) };var photo by rememberSaveable(card.id) { mutableStateOf(false) }
 val haptic=LocalHapticFeedback.current;val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current;val requester=remember { FocusRequester() }
 val textState=rememberTextFieldState(card.annotationText.orEmpty())
 val bringIntoView=remember { BringIntoViewRequester() }
 val imeHeight=WindowInsets.ime.getBottom(LocalDensity.current)
 LaunchedEffect(editor,imeHeight){if(editor && imeHeight>0){kotlinx.coroutines.delay(300);bringIntoView.bringIntoView()}}
 var inputEpoch by rememberSaveable(card.id) { mutableIntStateOf(0) }
 LaunchedEffect(card.annotationText,editor){if(!editor && textState.text.toString()!=card.annotationText.orEmpty())textState.edit { replace(0,length,card.annotationText.orEmpty()) }}
 LaunchedEffect(editor){if(editor)snapshotFlow { textState.text.toString() }.collect { onText(it) }}
 val active=card.isAnnotationMode;val pending=ActiveInputRepository.hasInput(card)
 LaunchedEffect(active) { if(!active){editor=false;photo=false} }
 val metricPanel=card.id.endsWith(":target:energyKcal")
 val color by animateColorAsState(if(active || metricPanel)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,label="card-state")
 val border=if(pending || active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
 val field=card.blocks.firstOrNull { it.type=="input" }?.content
 fun activate(){haptic.performHapticFeedback(HapticFeedbackType.LongPress);onLongPress()}
 Surface(modifier.testTag("card:${card.id}").fillMaxWidth().heightIn(min=116.dp).combinedClickable(onClick={if(active){editor=true;photo=false;onTap()}},onLongClick={activate()},onLongClickLabel="激活卡片补充"),color=color,shape=CutCornerShape(topEnd=12.dp),border=BorderStroke(if(active)2.dp else 1.dp,border)) {
  Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Row(Modifier.fillMaxWidth().background(if(field!=null)Ink else Color.Transparent).padding(if(field!=null)10.dp else 0.dp),verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { if(pending)Text("待发送",style=MaterialTheme.typography.labelSmall,color=if(field!=null)Signal else MaterialTheme.colorScheme.primary);Text(card.title,style=MaterialTheme.typography.titleSmall,color=if(field!=null)Color(0xFFF1F3E9) else MaterialTheme.colorScheme.onSurface) }
    if(active)IconButton(onClick={focus.clearFocus();onLongPress()},modifier=Modifier.size(48.dp)){Icon(Icons.Default.Close,"收起补充",tint=if(field!=null)Signal else MaterialTheme.colorScheme.onSurface)}
   }
   if(card.value.isNotBlank())Text(card.value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(active && !editor && !photo)Box(Modifier.fillMaxWidth().heightIn(min=72.dp).pointerInput(card.id) {
    var distance=0f
    detectVerticalDragGestures(onDragStart={distance=0f},onDragEnd={if(distance < -48.dp.toPx()){photo=true;haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)}},onVerticalDrag={change,amount->distance+=amount;change.consume()})
   }.clickable { editor=true;onTap() }.padding(vertical=12.dp)) { Text("点按写文字\n上划添加图片 ↑",style=MaterialTheme.typography.bodyLarge) }
   if(!active || !editor) {
    resolved.filter { it.type!="input" }.forEach { block->ComponentView(block,resolved.count { it.type=="metric" }==1,onAction){onChoice(block.id,it)} }
    if(field!=null && field.inputFields.isNotEmpty())key(inputEpoch){InputControls(field,card.inputValues,onInput)}
   }
   if(editor && active) {
    OutlinedTextField(state=textState,label={Text("补充 · ${card.title}")},placeholder={Text("描述今天的新情况…")},modifier=Modifier.fillMaxWidth().focusRequester(requester).bringIntoViewRequester(bringIntoView),lineLimits=TextFieldLineLimits.MultiLine(minHeightInLines=2,maxHeightInLines=4),trailingIcon={IconButton(onClick={editor=false;focus.clearFocus();keyboard?.hide()}){Icon(Icons.Default.Check,"收好这张 · 继续补充")}})
    LaunchedEffect(Unit){requester.requestFocus()}
   } else if(!card.annotationText.isNullOrBlank())Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.small) { Text(card.annotationText,Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.bodyMedium) }
   if(photo && active)Surface(color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.small) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
    Text("图片附在这张卡片",style=MaterialTheme.typography.labelLarge)
    Button(onClick=onSwipeUp){Icon(Icons.Default.AddPhotoAlternate,null);Text("选择图片")}
    TextButton(onClick={photo=false}){Text("返回文字")}
   } }
   card.annotationImageUri?.let { uri->AsyncImage(model=uri,contentDescription="${card.title}的待发送图片",modifier=Modifier.fillMaxWidth().heightIn(max=180.dp));TextButton(onClick=onRemoveImage){Text("撤下图片")} }
   if(!editor && (field!=null || active || pending))FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
    TextButton(onClick={if(!active)activate();editor=true;photo=false;onTap()}){Icon(Icons.Default.Edit,null,Modifier.size(16.dp));Text("文字",Modifier.padding(start=4.dp))}
    TextButton(onClick={if(!active)activate();photo=true;editor=false}){Icon(Icons.Default.AddPhotoAlternate,null,Modifier.size(16.dp));Text("图片",Modifier.padding(start=4.dp))}
    if(pending)TextButton(onClick={textState.edit { replace(0,length,"") };inputEpoch++;focus.clearFocus();onClear()}){Text("撤掉补充")}
   }
   if(card.followUpId!=null)TextButton(onClick=onManage){Text("在档案管理跟进 ↗")}
   if(card.invitationId!=null)TextButton(onClick=onSkip){Text("暂不补充")}
  }
 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun InputControls(c:ComponentContent,persisted:Map<String,String>,persist:(String,String)->Unit) {
 val pending=remember { mutableStateMapOf<String,String>() }
 val v=persisted.toMutableMap().apply { pending.forEach { (key,value)->if(value.isBlank())remove(key) else put(key,value) } }
 val change:(String,String)->Unit={key,value->pending[key]=value;persist(key,value)}
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  if(c.inputKind=="training") {
   if("name" in c.inputFields)OutlinedTextField(v["name"].orEmpty(),{change("name",it)},label={Text("动作名称")},placeholder={Text(c.suggestedName?.takeIf { it.isNotBlank() } ?: "填写本次动作")},singleLine=true,modifier=Modifier.fillMaxWidth())
   if(!c.suggestedName.isNullOrBlank() && v["name"].isNullOrBlank())TextButton(onClick={change("name",c.suggestedName)}){Text("本次记录 ${c.suggestedName}")}
   if("sets" in c.inputFields) {
    Row(verticalAlignment=Alignment.CenterVertically) { Text("组数",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge);Text(v["sets"]?.let { "$it 组" } ?: "未填写",style=MaterialTheme.typography.titleMedium);TextButton(onClick={change("sets",if(v.containsKey("sets"))"" else "1")}){Text(if(v.containsKey("sets"))"清空" else "填写")} }
    if(v.containsKey("sets"))Slider(value=(v["sets"]?.toFloatOrNull() ?: 1f).coerceIn(1f,12f),onValueChange={change("sets",it.roundToInt().toString())},valueRange=1f..12f,steps=10,modifier=Modifier.semantics { contentDescription="训练组数" })
   }
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    listOf("repsMin" to "每组次数","repsMax" to "范围上限").filter { it.first in c.inputFields }.forEach { (key,label)->OutlinedTextField(v[key].orEmpty(),{change(key,it)},label={Text(label)},placeholder={Text("可选")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f)) }
   }
   listOf("loadKg" to "负重 kg","equipment" to "器械").filter { it.first in c.inputFields }.forEach { (key,label)->OutlinedTextField(v[key].orEmpty(),{change(key,it)},label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth()) }
  } else if(c.inputKind=="measurement") {
   FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("weight" to "体重 kg","body_fat" to "体脂 %","waist" to "腰围 cm").forEach { (key,label)->FilterChip(selected=(v["metric"] ?: "weight")==key,onClick={change("metric",key)},label={Text(label)}) } }
   OutlinedTextField(v["value"].orEmpty(),{change("value",it)},label={Text("本次测量值")},placeholder={Text("未测量可留空")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
  }
 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComponentView(block: ResolvedComponent, largeMetric: Boolean, onAction: (CardAction, String?) -> Unit, onChoice: (String) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (block.type) {
            "metric" -> {
                if (block.label.isNotBlank() && !largeMetric) Text(block.label, style = MaterialTheme.typography.labelMedium)
                val value=block.text.substringBefore(' ')
                if(value.toDoubleOrNull()!=null) {
                    Text(value,style=if(largeMetric)MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text(block.text.substringAfter(' ',""),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(block.text,style=MaterialTheme.typography.bodyMedium)
            }
            "choice" -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.options.forEach { option ->
                        FilterChip(selected = block.selectedKey == option.key, enabled = true,
                            onClick = { block.actions.firstOrNull()?.let { onAction(it, option.key) } ?: onChoice(option.key) }, label = { Text(option.label) })
                    }
                }
            }
            "trend" -> { ArchiveChart(block) }
            "image" -> {
                block.attachmentPath?.let { AsyncImage(model = File(context.filesDir, it), contentDescription = block.text,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
                if (block.text.isNotBlank()) Text(block.text)
            }
            else -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (block.type != "choice") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            block.actions.forEach { action -> OutlinedButton(onClick = { onAction(action, null) }) { Text(action.label) } }
        }
    }
}
