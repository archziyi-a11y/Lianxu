package com.example.lianxu.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.lianxu.data.repository.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Shared read-only chart renderer. Units and scope come from the bound calculation. */
@Composable internal fun ArchiveChart(block:ResolvedComponent){
 val points=block.points;if(points.isEmpty())return
 val color=MaterialTheme.colorScheme.primary;val faint=MaterialTheme.colorScheme.outlineVariant
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  if(block.label.isNotBlank())Text(block.label,style=MaterialTheme.typography.labelMedium)
  when(block.chartKind){
   "calendar"->{
    val parsed=points.mapNotNull { p->runCatching { LocalDate.parse(p.label) to p.value }.getOrNull() };val latest=parsed.maxOfOrNull { it.first }
    if(latest!=null){
     val first=maxOf(parsed.minOf { it.first },latest.minusDays(83));val aligned=first.minusDays(first.dayOfWeek.value.toLong()-1)
     val values=parsed.filter { it.first>=first }.groupBy { it.first }.mapValues { it.value.sumOf { x->x.second } }
     Text("一    二    三    四    五    六    日",style=MaterialTheme.typography.labelSmall)
     Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription=values.entries.joinToString { "${it.key}：${it.value.toInt()}次训练" } }){
      val rows=(ChronoUnit.DAYS.between(aligned,latest)/7+1).toInt();val cell=minOf(size.width/7,size.height/rows)
      var date=aligned
      while(date<=latest){val n=ChronoUnit.DAYS.between(aligned,date).toInt();val value=values[date] ?: 0.0
       drawRect(if(value>0)color.copy(alpha=(.4+value*.15).coerceAtMost(1.0).toFloat()) else faint,Offset((n%7)*cell+2,(n/7)*cell+2),androidx.compose.ui.geometry.Size(cell-4,cell-4));date=date.plusDays(1)}
     }
     Text("显示${first}至${latest} · 有色方格为已记录训练日",style=MaterialTheme.typography.labelSmall)
    }
   }
   "bars","groups"->{
    val max=points.maxOf { abs(it.value) }.coerceAtLeast(.001)
    points.take(12).forEach { p->
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(p.label,style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f));Text("${ComponentResolver.displayNumber(p.value,block.unit)} ${block.unit}",style=MaterialTheme.typography.labelSmall)}
     Canvas(Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription="${p.label}：${p.value}${block.unit}" }){
      val hasNegative=points.any { it.value<0 };val origin=if(hasNegative)size.width/2 else 0f;val length=(abs(p.value)/max).toFloat()*(if(hasNegative)size.width/2 else size.width)
      drawLine(faint,Offset(0f,size.height/2),Offset(size.width,size.height/2),3f)
      drawLine(color,Offset(origin,size.height/2),Offset(if(p.value<0)origin-length else origin+length,size.height/2),6f)
     }
    }
   }
   else->{
    val dates=points.map { runCatching { LocalDate.parse(it.label) }.getOrNull() };val minimum=dates.filterNotNull().minOrNull();val maximum=dates.filterNotNull().maxOrNull()
    Canvas(Modifier.fillMaxWidth().height(128.dp).semantics { contentDescription=points.joinToString { "${it.label}：${it.value}${block.unit}" } }){
     val low=points.minOf { it.value };val high=points.maxOf { it.value };val spread=(high-low).coerceAtLeast(.1)
     repeat(3) { tick->val y=6f+tick*(size.height-12)/2;drawLine(faint,Offset(0f,y),Offset(size.width,y),1.dp.toPx()) }
     val coordinates=points.mapIndexed { i,p->
      val x=if(minimum!=null && maximum!=null && dates[i]!=null && maximum>minimum)ChronoUnit.DAYS.between(minimum,dates[i]).toFloat()/ChronoUnit.DAYS.between(minimum,maximum) else if(points.size==1).5f else i.toFloat()/(points.size-1)
      Offset(4+x*(size.width-8),size.height-6-((p.value-low)/spread).toFloat()*(size.height-12)) }
     coordinates.zipWithNext().forEach { (a,b)->drawLine(color,a,b,2.dp.toPx()) };coordinates.forEach { drawCircle(color,3.dp.toPx(),it) }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(points.first().label,style=MaterialTheme.typography.labelSmall);Text(points.last().label,style=MaterialTheme.typography.labelSmall)}
    Text("${ComponentResolver.displayNumber(points.minOf { it.value },block.unit)} — ${ComponentResolver.displayNumber(points.maxOf { it.value },block.unit)} ${block.unit}",style=MaterialTheme.typography.labelSmall)
   }
  }
  if(block.caption.isNotBlank())Text(block.caption,style=MaterialTheme.typography.labelSmall)
  if(block.text.isNotBlank())Text(block.text,style=MaterialTheme.typography.bodyMedium)
 }
}
