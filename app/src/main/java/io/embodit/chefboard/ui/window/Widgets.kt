package io.embodit.chefboard.ui.window

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.chefboard.R
import io.embodit.chefboard.platform.TAG
import io.embodit.chefboard.ui.theme.ChefColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun RichTxt(text: AnnotatedString, tag: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 2, align: TextAlign = TextAlign.Start) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = true, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow || it.lineCount > maxLines) Log.w(TAG, "OVERFLOW $tag \"$text\"") else Log.i(TAG, "LINES $tag ${it.lineCount}") },
    )
}

enum class Kind { PRIMARY, SECONDARY, DANGER, AMBER }

/**
 * One big window button (design theme.touchTargets: main 96–128 dp, others ≥ 64 dp): an optional icon above or left of
 * one or two words. Colours are role colours with their on-colour (contrast table in ui-audit).
 */
@Composable
internal fun BigButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    kind: Kind = Kind.SECONDARY,
    enabled: Boolean = true,
    height: Dp = 96.dp,
    stacked: Boolean = false,
    textStyle: TextStyle = PicoTheme.typography.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
) {
    val (bg, fg) = when (kind) {
        Kind.PRIMARY -> ChefColors.Primary to ChefColors.OnPrimary
        Kind.SECONDARY -> PicoTheme.colorScheme.fillSecondary to PicoTheme.colorScheme.labelPrimary
        Kind.DANGER -> ChefColors.Danger to ChefColors.OnDanger
        Kind.AMBER -> ChefColors.Amber to ChefColors.OnAmber
    }
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = height, minWidth = 96.dp),
        shape = RoundedCornerShape(if (height > 112.dp) 40.dp else height / 2),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
    ) {
        if (stacked && icon != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(44.dp))
                // design-style: inherited-content-color Button
                Txt(text, "$tag/button", style = textStyle, color = fg, align = TextAlign.Center, maxLines = 2)
            }
        } else {
            if (icon != null) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
            }
            // design-style: inherited-content-color Button
            Txt(text, "$tag/button", style = textStyle, color = fg, align = TextAlign.Center)
        }
    }
}

/** D15 / B31「静音常显」: the mute key top-right on every page (not counted in the 7). Icon = current state, text = what a tap does. */
@Composable
internal fun MuteKey(muted: Boolean, onClick: () -> Unit) {
    val fg = if (muted) ChefColors.OnPrimary else PicoTheme.colorScheme.labelPrimary
    val label = stringResource(if (muted) R.string.unmute else R.string.mute)
    Button(
        onClick = onClick,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 64.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (muted) ChefColors.Primary else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Icon(painter = painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up), contentDescription = label, tint = fg, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(label, "window/mute", style = PicoTheme.typography.labelLarge.copy(fontSize = 22.sp), color = fg)
    }
}
