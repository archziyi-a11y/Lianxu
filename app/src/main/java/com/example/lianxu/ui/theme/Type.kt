package com.example.lianxu.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
// System Noto CJK fallback and condensed Latin numerals. No remote font dependency.
val InstrumentNumbers=FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD)))
private fun type(size:Int,line:Int,weight:FontWeight=FontWeight.Normal,family:FontFamily=FontFamily.SansSerif)=TextStyle(fontFamily=family,fontWeight=weight,fontSize=size.sp,lineHeight=line.sp,letterSpacing=0.sp)
val Typography=Typography(
 displayLarge=type(64,68,FontWeight.Bold,InstrumentNumbers),displayMedium=type(48,52,FontWeight.Bold,InstrumentNumbers),displaySmall=type(40,46,FontWeight.Bold),
 headlineLarge=type(32,40,FontWeight.Bold,InstrumentNumbers),headlineMedium=type(28,34,FontWeight.Bold,InstrumentNumbers),headlineSmall=type(24,30,FontWeight.Bold,InstrumentNumbers),
 titleLarge=type(22,30,FontWeight.Bold),titleMedium=type(18,26,FontWeight.SemiBold),titleSmall=type(15,22,FontWeight.SemiBold),
 bodyLarge=type(16,26),bodyMedium=type(14,22),bodySmall=type(12,19),
 labelLarge=type(14,20,FontWeight.SemiBold),labelMedium=type(12,18,FontWeight.Medium),labelSmall=type(11,16,FontWeight.Medium))
