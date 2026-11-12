package io.embodit.chefboard.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.chefboard.domain.cook.Lang
import io.embodit.chefboard.platform.Graph
import java.util.Locale

/** design theme.colors: warm olive for buttons / the current step, amber for「到点」; UI on the PicoTheme window glass. */
object ChefColors {
    val Primary = Color(0xFF4F5A2E)
    val OnPrimary = Color(0xFFFFFFFF)
    val Amber = Color(0xFFD98A1C)
    val OnAmber = Color(0xFF1D1A12)
    /** Amber words inside the step sentence (times) on the window glass: a deep amber that keeps ≥ 4.5:1 on the light glass. */
    val TimeInk = Color(0xFF8A4B00)
    val Danger = Color(0xFFA33A1E)
    val OnDanger = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F24)
    val InkSecondary = Color(0xFF3A3F45)
    val CardFill = Color(0xFFF1EEE6)
    val CardInk = Color(0xFF1B1F24)
}

/**
 * PicoTheme with the app colour roles. The app language localises resources below this point through a ContextWrapper
 * that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun ChefTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.flow.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.lang) { base.localized(settings.lang) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        labelPrimaryLight = ChefColors.OnPrimary,
        labelPrimary = ChefColors.Ink,
        labelSecondary = ChefColors.InkSecondary,
        error = ChefColors.Danger,
        interaction = ChefColors.Primary,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Lang): Context {
    val locale = when (language) {
        Lang.SYSTEM -> return this
        Lang.ZH -> Locale.SIMPLIFIED_CHINESE
        Lang.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
