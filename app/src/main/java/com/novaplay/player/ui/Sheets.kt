package com.novaplay.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.novaplay.player.Accent
import com.novaplay.player.NavyHigh
import com.novaplay.player.NavySurface

val SecondaryText = Color(0xFF8E8E96)
val Destructive = Color(0xFFFF453A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 560.dp,
        containerColor = NavySurface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        scrimColor = Color(0x66000000),
    ) {
        Column(
            Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) { content() }
    }
}

@Composable
fun SheetHeader(text: String) =
    Text(text, style = Type.title, color = Color.White, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = Type.caption, color = SecondaryText, modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))

@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NavyHigh)) { content() }

@Composable
fun CardDivider(start: Int = 16) =
    HorizontalDivider(Modifier.padding(start = start.dp), thickness = 0.5.dp, color = Color(0x22FFFFFF))

@Composable
fun SheetAction(text: String, icon: ImageVector? = null, color: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.body, color = color, modifier = Modifier.weight(1f))
        if (icon != null) {
            Spacer(Modifier.width(12.dp))
            Icon(icon, null, tint = if (color == Color.White) Accent else color, modifier = Modifier.size(20.dp))
        }
    }
}

/** Simple text prompt (rename, new playlist). */
@Composable
fun TextPrompt(
    title: String,
    initial: String = "",
    confirm: String = "Save",
    placeholder: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NavySurface,
        shape = RoundedCornerShape(18.dp),
        title = { Text(title, style = Type.headline) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = placeholder?.let { p -> { Text(p, style = Type.body, color = Color(0x66FFFFFF)) } },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm, color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Accent) } },
    )
}

/** 4+ digit PIN entry. When [creating], asks twice. */
@Composable
fun PinPrompt(creating: Boolean, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = when {
        !creating -> "Enter PIN"
        first == null -> "Create a PIN"
        else -> "Confirm PIN"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NavySurface,
        shape = RoundedCornerShape(18.dp),
        title = { Text(title, style = Type.headline) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> if (v.length <= 8 && v.all(Char::isDigit)) pin = v; error = null },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                )
                error?.let { Text(it, color = Destructive, style = Type.footnote, modifier = Modifier.padding(top = 6.dp)) }
                if (creating) Text(
                    "Private videos are hidden inside NovaPlay only. Other apps can still see the files.",
                    style = Type.footnote, color = SecondaryText, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = {
                when {
                    !creating -> if (!onPin(pin)) { error = "Wrong PIN"; pin = "" }
                    first == null -> { first = pin; pin = "" }
                    first == pin -> onPin(pin)
                    else -> { error = "PINs don't match"; first = null; pin = "" }
                }
            }) { Text("OK", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Accent) } },
    )
}

/** One action in a [TileGrid]. */
data class Tile(
    val icon: ImageVector,
    val label: String,
    val active: Boolean = false,
    val tint: Color? = null,
    val onClick: () -> Unit,
)

/** Compact grid of round icon buttons with small labels, four per row. */
@Composable
fun TileGrid(tiles: List<Tile>, perRow: Int = 4) {
    Column(Modifier.fillMaxWidth()) {
        tiles.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { t -> ActionTile(t, Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ActionTile(t: Tile, modifier: Modifier) {
    val color = t.tint ?: if (t.active) Accent else Color.White
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).pressable(pressedScale = 0.92f, onClick = t.onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (t.active) Accent.copy(alpha = 0.2f) else if (t.tint != null) t.tint.copy(alpha = 0.14f) else NavyHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(t.icon, null, tint = color, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(t.label, style = Type.caption, color = if (t.tint != null || t.active) color else Color(0xCCFFFFFF),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}
