package com.pynapli.app.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pynapli.app.resources.Res
import com.pynapli.shared.core.result.runCatchingCancellable
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToSvgPainter

/**
 * Provider colours from each provider's own button rules, not the Pynapli palette, and therefore
 * kept apart from [PynapliColors] (DESIGN_SPEC, "Sign-in provider buttons").
 *
 * Google (developers.google.com/identity/branding-guidelines), dark theme on our dark background:
 * fill #131314, 1px inside stroke #8E918F, text #E3E3E3. Apple (Human Interface Guidelines, "Sign
 * in with Apple"): on a dark background the white style, title black. The marks themselves are
 * never drawn here: they are the providers' own files ([GOOGLE_MARK_ASSET], [APPLE_MARK_ASSET]).
 */
object PynapliProviderColors {
    val GoogleFill = Color(0xFF131314)
    val GoogleStroke = Color(0xFF8E918F)
    val GoogleText = Color(0xFFE3E3E3)
    val AppleFill = Color(0xFFFFFFFF)
    val AppleContent = Color(0xFF000000)
}

/** Which provider a [PynapliSocialButton] is for; decides the artwork, colours and title. */
enum class PynapliSocialButtonKind { Google, Apple }

/**
 * The providers' own logo files, which the founder downloads and drops in (PATH_TO_TESTERS A10);
 * none is committed, and no lookalike is drawn in code. Paths are Compose resources:
 * `composeApp/src/commonMain/composeResources/<path>`.
 *
 * Google: the standard colour "G" SVG from the Sign in with Google branding page
 * (developers.google.com/identity/branding-guidelines, "Download" assets), the logo alone.
 */
const val GOOGLE_MARK_ASSET: String = "files/brand/google-g.svg"

/**
 * Apple: the logo-only SVG, black, from Sign in with Apple's resources in Apple Design Resources
 * (developer.apple.com/design/resources/, "Sign in with Apple"). Drawn at the button's height,
 * uncropped, as Apple's rules ask.
 */
const val APPLE_MARK_ASSET: String = "files/brand/apple-logo-black.svg"

/**
 * Launch guards. Each stays false until that provider's official file is at its path above; while
 * false, no button for that provider shows anywhere (welcome, sign-in form, Settings > Sign-in
 * methods "Add"), whatever the server flag says. Flip only after dropping the file in.
 */
const val GOOGLE_MARK_IS_OFFICIAL_ARTWORK: Boolean = false
const val APPLE_MARK_IS_OFFICIAL_ARTWORK: Boolean = false

/**
 * "Continue with Google" / "Continue with Apple", drawn to each provider's rules: their allowed
 * title, their colours ([PynapliProviderColors]), their own mark file, the same pill shape and full
 * width as the other sign-in buttons (Apple: "no smaller than other sign-in buttons"). 48dp tall:
 * Apple's title is 43% of that (20sp), Google's is its 14sp. While [loading] the title stays (so the
 * width never jumps) and a spinner takes the mark's place.
 */
@Composable
fun PynapliSocialButton(
    kind: PynapliSocialButtonKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val google = kind == PynapliSocialButtonKind.Google
    val fill = if (google) PynapliProviderColors.GoogleFill else PynapliProviderColors.AppleFill
    val content = if (google) PynapliProviderColors.GoogleText else PynapliProviderColors.AppleContent
    val title = if (google) "Continue with Google" else "Continue with Apple"
    val mark = rememberProviderMark(if (google) GOOGLE_MARK_ASSET else APPLE_MARK_ASSET)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(PynapliShape.PillShape)
                .background(fill)
                .then(if (google) Modifier.border(1.dp, PynapliProviderColors.GoogleStroke, PynapliShape.PillShape) else Modifier)
                .handCursor(enabled = enabled)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick)
                .alpha(if (enabled || loading) 1f else 0.6f)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                loading ->
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }

                mark == null -> Unit

                // Google: the G at its own proportions, 20dp, never recoloured.
                google -> Image(painter = mark, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(20.dp))

                // Apple: the logo file matches the button height, no extra vertical padding.
                else -> Image(painter = mark, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(48.dp))
            }
            Text(
                text = title,
                color = content,
                style = MaterialTheme.typography.labelLarge,
                fontSize = if (google) 14.sp else 20.sp,
                fontWeight = if (google) FontWeight.Medium else FontWeight.SemiBold,
            )
        }
    }
}

/** The provider's own SVG from Compose resources, or null while loading or if it is missing. */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun rememberProviderMark(path: String): Painter? {
    val density = LocalDensity.current
    val painter by produceState<Painter?>(initialValue = null, path, density) {
        value = runCatchingCancellable { Res.readBytes(path).decodeToSvgPainter(density) }.getOrNull()
    }
    return painter
}
