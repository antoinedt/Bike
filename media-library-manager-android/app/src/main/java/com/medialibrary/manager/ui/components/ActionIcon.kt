package com.medialibrary.manager.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** An icon-only button; [label] is what TalkBack reads. Used where the icon's meaning is obvious. */
@Composable
fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = label, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
    }
}
