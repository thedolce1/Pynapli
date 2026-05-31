package com.pynapli.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
internal fun ProfileHero(
    displayName: String,
    online: Boolean,
    distanceLabel: String?,
    profilePhotoUrl: String?,
    onDismiss: () -> Unit,
    onHideClick: () -> Unit,
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2B2622), Color(0xFF6D5D4F), Color(0xFF1C1B1A)),
                    ),
                ),
    ) {
        profilePhotoUrl?.let { photoUrl ->
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(210.dp),
            )
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileOverflowMenu(
                onHideClick = onHideClick,
                onBlockClick = onBlockClick,
                onReportClick = onReportClick,
            )

            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close profile",
                    tint = Color.White,
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xD9000000)),
                        ),
                    ).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (online) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF13E58A))
                            .border(1.dp, Color.Black, CircleShape),
                )
            }

            Text(
                text = displayName,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            distanceLabel?.let {
                Text(
                    text = it,
                    color = Color(0xFFE5E5E5),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProfileOverflowMenu(
    onHideClick: () -> Unit,
    onBlockClick: () -> Unit,
    onReportClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier =
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = Color.White,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Hide") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    expanded = false
                    onHideClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Block") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onBlockClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Report") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                },
                onClick = {
                    expanded = false
                    onReportClick()
                },
            )
        }
    }
}
