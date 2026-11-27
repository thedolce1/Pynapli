package com.pynapli.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliSocialButton
import com.pynapli.app.design.PynapliSocialButtonKind
import com.pynapli.shared.auth.SocialProvider

/**
 * The "Continue with Google / Apple" group (DESIGN_SPEC, "Sign-in provider buttons"). On the welcome
 * landing it sits in the slot above Create account, then an "or" rule; on the sign-in form the rule
 * comes first ([dividerFirst]) because the password form leads. Nothing at all when [providers] is
 * empty, which is the default until the server switches a provider on.
 */
@Composable
internal fun SocialSignInButtons(
    providers: List<SocialProvider>,
    inProgress: SocialProvider?,
    enabled: Boolean,
    onContinue: (SocialProvider) -> Unit,
    dividerFirst: Boolean = false,
) {
    // No button for a provider until its official logo file is in (SocialButtonGuard).
    val shown = visibleSocialProviders(providers)
    if (shown.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        if (dividerFirst) {
            OrRule()
            Spacer(modifier = Modifier.height(16.dp))
        }
        shown.forEachIndexed { index, provider ->
            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            PynapliSocialButton(
                kind =
                    when (provider) {
                        SocialProvider.Google -> PynapliSocialButtonKind.Google
                        SocialProvider.Apple -> PynapliSocialButtonKind.Apple
                    },
                onClick = { onContinue(provider) },
                enabled = enabled,
                loading = inProgress == provider,
            )
        }
        if (!dividerFirst) {
            Spacer(modifier = Modifier.height(16.dp))
            OrRule()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OrRule() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "or",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
