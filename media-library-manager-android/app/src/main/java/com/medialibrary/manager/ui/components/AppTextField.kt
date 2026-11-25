package com.medialibrary.manager.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation

/** True on Google TV / Android TV. Provided once at the top of the app (MainActivity). */
val LocalIsTv = staticCompositionLocalOf { false }

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The app's text field: an OutlinedTextField that, on a TV, doesn't pop up the on-screen keyboard
 * just because the remote's D-pad moved onto it (which made it impossible to move *past* a field
 * without the keyboard covering the screen). The keyboard opens when OK/Enter is pressed on the
 * focused field instead. On phones it behaves exactly like OutlinedTextField.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val isTv = LocalIsTv.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (!isTv) modifier else modifier.onPreviewKeyEvent { event ->
            // Only the physical remote's OK button: an on-screen keyboard's own Enter must still
            // reach the field (e.g. to trigger "Done").
            val fromRemote = event.nativeKeyEvent.device?.isVirtual == false
            if (!fromRemote || event.key !in OK_KEYS) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp) keyboard?.show()
            true
        },
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = if (isTv) keyboardOptions.copy(showKeyboardOnFocus = false) else keyboardOptions,
        keyboardActions = keyboardActions
    )
}
