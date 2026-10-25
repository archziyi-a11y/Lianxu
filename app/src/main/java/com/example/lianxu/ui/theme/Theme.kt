package com.example.lianxu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Signal = Color(0xFFDFFF00)
val Ink = Color(0xFF171B19)
private val Light = lightColorScheme(
 primary=Color(0xFF485900), onPrimary=Color.White, primaryContainer=Signal, onPrimaryContainer=Ink,
 secondary=Color(0xFF006C75), onSecondary=Color.White, secondaryContainer=Color(0xFFD2EEF0), onSecondaryContainer=Ink,
 tertiary=Color(0xFF9A441E), tertiaryContainer=Color(0xFFFFDCC9), onTertiaryContainer=Ink,
 background=Color(0xFFEDEFE8), onBackground=Ink, surface=Color(0xFFFAFBF6), onSurface=Ink,
 surfaceVariant=Color(0xFFE1E5DA), onSurfaceVariant=Color(0xFF4F584F), outline=Color(0xFF778273), outlineVariant=Color(0xFFCBD1C3),
 surfaceContainer=Color(0xFFE5E9DF), surfaceContainerHigh=Color(0xFFDDE3D7), surfaceContainerHighest=Color(0xFFD3DBCC),
 error=Color(0xFFAD3524), onError=Color.White
)
private val Dark = darkColorScheme(
 primary=Signal, onPrimary=Ink, primaryContainer=Color(0xFF35400D), onPrimaryContainer=Signal,
 secondary=Color(0xFF88D8E0), onSecondary=Ink, secondaryContainer=Color(0xFF183D41), onSecondaryContainer=Color(0xFFB4EBEE),
 tertiary=Color(0xFFFFAD82), tertiaryContainer=Color(0xFF4D3022), onTertiaryContainer=Color(0xFFFFDCC9),
 background=Color(0xFF131816), onBackground=Color(0xFFF1F3E9), surface=Color(0xFF202723), onSurface=Color(0xFFF1F3E9),
 surfaceVariant=Color(0xFF2D3730), onSurfaceVariant=Color(0xFFBAC5B8), outline=Color(0xFF889886), outlineVariant=Color(0xFF435044),
 surfaceContainer=Color(0xFF252E28), surfaceContainerHigh=Color(0xFF303C32), surfaceContainerHighest=Color(0xFF3B483D),
 error=Color(0xFFFFB4A5), onError=Color(0xFF630F00)
)
@Composable fun LianxuTheme(darkTheme:Boolean=isSystemInDarkTheme(),dynamicColor:Boolean=false,content:@Composable ()->Unit) {
 val view=androidx.compose.ui.platform.LocalView.current
 if(!view.isInEditMode)androidx.compose.runtime.SideEffect {
  val activity=generateSequence(view.context){(it as? android.content.ContextWrapper)?.baseContext}.filterIsInstance<android.app.Activity>().firstOrNull()
  activity?.let { androidx.core.view.WindowCompat.getInsetsController(it.window,view).apply {
   isAppearanceLightStatusBars=!darkTheme;isAppearanceLightNavigationBars=!darkTheme
  } }
 }
 MaterialTheme(colorScheme=if(darkTheme)Dark else Light, typography=Typography,
 shapes=Shapes(extraSmall=RoundedCornerShape(2.dp),small=RoundedCornerShape(4.dp),medium=RoundedCornerShape(6.dp),large=RoundedCornerShape(10.dp),extraLarge=RoundedCornerShape(12.dp)),content=content)
}
