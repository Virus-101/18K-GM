package ma.goldmaroc18k.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import ma.goldmaroc18k.GoldApp
import ma.goldmaroc18k.R

private val LightGold = lightColorScheme(
    primary = Color(0xFF8A6A00), onPrimary = Color.White, primaryContainer = Color(0xFFFFE7A3), onPrimaryContainer = Color(0xFF2A1F00),
    secondaryContainer = Color(0xFFF5EBCB), background = Color(0xFFFFFBF2), surface = Color(0xFFFFFBF2),
)
private val DarkGold = darkColorScheme(
    primary = Color(0xFFE6C453), onPrimary = Color(0xFF3B2F00), primaryContainer = Color(0xFF4F3F00), onPrimaryContainer = Color(0xFFFFE7A3),
    secondaryContainer = Color(0xFF3A3220), background = Color(0xFF14120D), surface = Color(0xFF14120D),
)

@Composable
fun GoldTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkGold else LightGold, content = content)
}

class MainActivity : AppCompatActivity() {
    private val vm: GoldViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GoldApp
        setContent {
            GoldTheme {
                var tab by remember { mutableIntStateOf(0) }
                var notif by remember { mutableStateOf(app.prefs.notificationsEnabled) }
                var above by remember { mutableStateOf(app.prefs.alertAbove) }
                var below by remember { mutableStateOf(app.prefs.alertBelow) }
                var lang by remember { mutableStateOf(app.prefs.language) }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (!granted) { app.prefs.notificationsEnabled = false; notif = false }
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf(R.string.tab_home, R.string.tab_calc, R.string.tab_settings).forEachIndexed { i, res ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("◉", "∑", "⚙")[i]) }, label = { Text(stringResource(res)) })
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    androidx.compose.foundation.layout.Box(m) {
                        when (tab) {
                            0 -> HomeScreen(vm)
                            1 -> CalculatorScreen(vm)
                            else -> SettingsScreen(
                                notificationsEnabled = notif,
                                onNotifications = {
                                    notif = it; app.prefs.notificationsEnabled = it
                                    if (it && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                above = above, below = below,
                                onAlerts = { a, b ->
                                    above = a; below = b
                                    app.prefs.alertAbove = a; app.prefs.alertBelow = b
                                    app.prefs.aboveFired = false; app.prefs.belowFired = false
                                },
                                language = lang,
                                onLanguage = {
                                    lang = it; app.prefs.language = it
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
