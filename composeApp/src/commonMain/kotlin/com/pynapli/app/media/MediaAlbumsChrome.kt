package com.pynapli.app.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliHeaderActionButton
import com.pynapli.app.design.PynapliScreenHeader
import com.pynapli.app.design.PynapliShape

@Composable
internal fun MediaAlbumsHeader(onBack: () -> Unit) {
    // insets = false: the header is a spanned item inside the album grid, which already supplies the
    // horizontal inset via its contentPadding; this wrapper only adds the status-bar + vertical pad.
    PynapliScreenHeader(
        modifier =
            Modifier
                .statusBarsPadding()
                .padding(top = 8.dp, bottom = 12.dp),
        insets = false,
        leading = {
            PynapliHeaderActionButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
        },
        title = "Profile",
        subtitle = "Linked profile and media",
    )
}

@Composable
internal fun ErrorBand(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), PynapliShape.CardShape)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
