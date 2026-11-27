package com.medialibrary.manager.ui.components

import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
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
    if (!isTv) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions
        )
        return
    }
    // TV: the field is read-only (so it can't bring up the keyboard, however it got focus — e.g.
    // a popup focusing its first field) until OK is pressed on it; it goes back to read-only when
    // the keyboard's action key is used or focus moves away.
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            // Let the field leave read-only mode (and start taking input) before asking for the keyboard.
            kotlinx.coroutines.delay(80)
            keyboard?.show()
        }
    }
    fun done(scope: KeyboardActionScope, action: ImeAction, handler: (KeyboardActionScope.() -> Unit)?) {
        editing = false
        keyboard?.hide()
        if (handler != null) handler(scope) else scope.defaultKeyboardAction(action)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = !editing,
        modifier = modifier
            .onFocusChanged { if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { event ->
                // Only the physical remote's OK button: an on-screen keyboard's own Enter must still
                // reach the field (e.g. to trigger "Done").
                val fromRemote = event.nativeKeyEvent.device?.isVirtual == false
                if (editing || !fromRemote || event.key !in OK_KEYS) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) editing = true
                true
            },
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions.copy(showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(
            onDone = { done(this, ImeAction.Done, keyboardActions.onDone) },
            onGo = { done(this, ImeAction.Go, keyboardActions.onGo) },
            onNext = { done(this, ImeAction.Next, keyboardActions.onNext) },
            onPrevious = { done(this, ImeAction.Previous, keyboardActions.onPrevious) },
            onSearch = { done(this, ImeAction.Search, keyboardActions.onSearch) },
            onSend = { done(this, ImeAction.Send, keyboardActions.onSend) }
        )
    )
}
