package com.dallas.cabletv.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import com.dallas.cabletv.ui.Tv
import com.dallas.cabletv.ui.TvType

/** A card with a title and a column of content, the building block of every phone screen. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Tv.Surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (title != null) Text(title, style = TvType.Section, color = Tv.Ink)
        content()
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tv.Accent,
            contentColor = Tv.FocusInk,
            disabledContainerColor = Tv.Surface3,
            disabledContentColor = Tv.Ink3
        )
    ) {
        Text(text, style = TvType.BodyStrong, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tv.Surface3,
            contentColor = Tv.Ink,
            disabledContainerColor = Tv.Surface2,
            disabledContentColor = Tv.Ink3
        )
    ) {
        Text(text, style = TvType.BodyStrong, maxLines = 1)
    }
}

/** A quiet text-only button for dialogs and small actions. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Tv.Accent, disabledContentColor = Tv.Ink3)
    ) {
        Text(text, style = TvType.BodyStrong)
    }
}

/** A text box in the app's colors. [secret] hides what is typed, with a Show button. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    placeholder: String? = null
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = TvType.Body,
        label = { Text(label) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = Tv.Ink3) }
        } else {
            null
        },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            capitalization = KeyboardCapitalization.None
        ),
        trailingIcon = if (secret) {
            { QuietButton(if (shown) "Hide" else "Show", onClick = { shown = !shown }) }
        } else {
            null
        },
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors()
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Tv.Ink,
    unfocusedTextColor = Tv.Ink,
    disabledTextColor = Tv.Ink3,
    focusedBorderColor = Tv.Accent,
    unfocusedBorderColor = Tv.Surface3,
    disabledBorderColor = Tv.Surface2,
    focusedLabelColor = Tv.Accent,
    unfocusedLabelColor = Tv.Ink2,
    disabledLabelColor = Tv.Ink3,
    cursorColor = Tv.Accent,
    focusedContainerColor = Tv.Surface2,
    unfocusedContainerColor = Tv.Surface2,
    disabledContainerColor = Tv.Surface
)

/** A dot that is green when a TV is on the network and grey when it is not. */
@Composable
fun OnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (online) Tv.Ok else Tv.Ink3)
            .semantics { contentDescription = if (online) "On the network" else "Not found" }
    )
}

/** A line of text under a form: calm grey for news, red for a problem. */
@Composable
fun MessageLine(text: String, error: Boolean, modifier: Modifier = Modifier) {
    Text(text, style = TvType.Small, color = if (error) Tv.Live else Tv.Ink2, modifier = modifier)
}

/** A light tap on the phone, for the remote's buttons. */
@Composable
fun rememberTap(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
}

/** A key on the remote: a rounded tile that taps lightly. Dimmed and inert when [enabled] is false. */
@Composable
fun KeyTile(
    description: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    accent: Boolean = false,
    content: @Composable () -> Unit
) {
    val tap = rememberTap()
    Box(
        modifier
            .heightIn(min = height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) Tv.Accent else Tv.Surface2)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button) {
                        tap()
                        onPress()
                    }
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** A [KeyTile] with a text label. */
@Composable
fun KeyLabel(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = label,
    height: Dp = 52.dp,
    accent: Boolean = false
) {
    KeyTile(description, onPress, modifier, enabled, height, accent) {
        Text(label, style = TvType.BodyStrong, color = if (accent) Tv.FocusInk else Tv.Ink, maxLines = 1)
    }
}

/**
 * A dialog for typing a short code or PIN. [length] is how many characters it takes, and [hex]
 * allows the letters A to F for the Android TV remote's code. The button turns on when the code is
 * complete.
 */
@Composable
fun CodeDialog(
    title: String,
    message: String,
    length: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hex: Boolean = false,
    secret: Boolean = false,
    error: String? = null,
    busy: Boolean = false,
    confirmLabel: String = "Continue"
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Tv.Surface2,
        titleContentColor = Tv.Ink,
        textContentColor = Tv.Ink2,
        title = { Text(title, style = TvType.Title) },
        text = {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = TvType.Body, color = Tv.Ink2)
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        val allowed = new.filter { if (hex) it.isDigit() || it.uppercaseChar() in 'A'..'F' else it.isDigit() }
                        text = (if (hex) allowed.uppercase() else allowed).take(length)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    singleLine = true,
                    textStyle = TvType.Title,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (hex) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        capitalization = if (hex) KeyboardCapitalization.Characters else KeyboardCapitalization.None
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors()
                )
                if (busy) MessageLine("Checking...", error = false)
                if (error != null) MessageLine(error, error = true)
            }
        },
        confirmButton = {
            QuietButton(confirmLabel, onClick = { onConfirm(text) }, enabled = text.length == length && !busy)
        },
        dismissButton = {
            QuietButton("Cancel", onClick = onDismiss)
        }
    )
}

/** A dialog with a message and one or two buttons. */
@Composable
fun MessageDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Close"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tv.Surface2,
        titleContentColor = Tv.Ink,
        textContentColor = Tv.Ink2,
        title = { Text(title, style = TvType.Title) },
        text = { Text(message, style = TvType.Body, color = Tv.Ink2) },
        confirmButton = { QuietButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { QuietButton(dismissLabel, onClick = onDismiss) }
    )
}

/** A row of text with a control at the end, such as a switch. */
@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = TvType.Body, color = Tv.Ink, modifier = Modifier.weight(1f))
        control()
    }
}
