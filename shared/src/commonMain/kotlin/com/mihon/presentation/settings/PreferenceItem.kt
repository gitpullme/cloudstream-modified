package com.mihon.presentation.settings

import com.mihon.presentation.settings.widget.ColorPreferenceWidget
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.mihon.common.preference.PreferenceData
import com.mihon.material.BaseSliderItem
import com.mihon.presentation.settings.widget.EditTextPreferenceWidget
import com.mihon.presentation.settings.widget.InfoWidget
import com.mihon.presentation.settings.widget.ListPreferenceWidget
import com.mihon.presentation.settings.widget.MultiSelectListPreferenceWidget
import com.mihon.presentation.settings.widget.PrefsHorizontalPadding
import com.mihon.presentation.settings.widget.PrefsVerticalPadding
import com.mihon.presentation.settings.widget.SwitchPreferenceWidget
import com.mihon.presentation.settings.widget.TextPreferenceWidget
import com.mihon.presentation.settings.widget.TitleFontSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalPreferenceHighlighted = compositionLocalOf(structuralEqualityPolicy()) { false }
val LocalPreferenceMinHeight = compositionLocalOf(structuralEqualityPolicy()) { 56.dp }

@Composable
fun StatusWrapper(
    item: Preference.PreferenceItem<*, *>,
    highlightKey: String?,
    content: @Composable () -> Unit,
) {
    if (!item.enabled) return

    // AnimatedVisibility used to wrap every row here. That forced a custom layout,
    // a Transition and a graphics layer for each preference even though almost all
    // preferences are permanently enabled. Only provide the extra composition local
    // when the row actually needs to be highlighted.
    if (item.title == highlightKey) {
        CompositionLocalProvider(
            LocalPreferenceHighlighted provides true,
            content = content,
        )
    } else {
        content()
    }
}

/**
 * Keeps the displayed preference value in the row itself instead of starting a cold
 * SharedPreferences listener flow for every row that scrolls into view. Settings values are
 * changed by their own rows; if a callback rejects/rewrites the value the stored value is
 * re-read so the UI still reflects the preference store.
 */
@Composable
private fun <T> rememberPreferenceValue(preference: PreferenceData<T>): MutableState<T> {
    return remember(preference) { mutableStateOf(preference.get()) }
}

@Composable
internal fun PreferenceItem(
    item: Preference.PreferenceItem<*, *>,
    highlightKey: String?,
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    StatusWrapper(
        item = item,
        highlightKey = highlightKey,
    ) {
        when (item) {
            is Preference.PreferenceItem.SwitchPreference -> {
                val value = rememberPreferenceValue(item.preference)
                SwitchPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    checked = value.value,
                    onCheckedChanged = { newValue ->
                        value.value = newValue
                        scope.launch {
                            if (item.onValueChanged(newValue)) {
                                item.preference.set(newValue)
                            } else {
                                value.value = item.preference.get()
                            }
                        }
                    },
                )
            }

            is Preference.PreferenceItem.ColorPreference -> {
                val color = rememberPreferenceValue(item.preference)
                ColorPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    color = Color(color.value),
                    onValueChange = { newValue ->
                        val argb = newValue.toArgb()
                        color.value = argb
                        scope.launch {
                            if (item.onValueChanged(argb)) {
                                item.preference.set(argb)
                            } else {
                                color.value = item.preference.get()
                            }
                        }
                    },
                )
            }

            is Preference.PreferenceItem.ListPreference<*> -> {
                @Suppress("UNCHECKED_CAST")
                val value = rememberPreferenceValue(item.preference) as MutableState<Any?>
                ListPreferenceWidget(
                    value = value.value,
                    title = item.title,
                    subtitle = item.internalSubtitleProvider(value.value, item.entries),
                    icon = item.icon,
                    entries = item.entries,
                    iconProvider = item::InternalIconProvider,
                    onValueChange = { newValue ->
                        value.value = newValue
                        scope.launch {
                            if (item.internalOnValueChanged(newValue)) {
                                item.internalSet(newValue)
                            } else {
                                value.value = item.preference.get()
                            }
                        }
                    },
                )
            }

            is Preference.PreferenceItem.MultiSelectListPreference<*> -> {
                @Suppress("UNCHECKED_CAST")
                val values = rememberPreferenceValue(item.preference) as MutableState<Set<Any?>>
                MultiSelectListPreferenceWidget(
                    values = values.value,
                    title = item.title,
                    subtitle = item.internalSubtitleProvider(values.value, item.entries),
                    icon = item.icon,
                    entries = item.entries,
                    onValuesChange = { newValues ->
                        values.value = newValues
                        scope.launch {
                            if (item.internalOnValueChanged(newValues)) {
                                item.internalSet(newValues)
                            } else {
                                values.value = item.preference.get()
                            }
                        }
                    },
                )
            }

            is Preference.PreferenceItem.TextPreference -> {
                TextPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    widget = item.widget,
                    onPreferenceClick = item.onClick,
                )
            }

            is Preference.PreferenceItem.EditTextPreference -> {
                val value = rememberPreferenceValue(item.preference)
                EditTextPreferenceWidget(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    value = value.value,
                    onConfirm = {
                        val accepted = item.onValueChanged(it)
                        if (accepted) {
                            item.preference.set(it)
                            value.value = it
                        }
                        accepted
                    },
                )
            }
            is Preference.PreferenceItem.InfoPreference -> {
                InfoWidget(text = item.title)
            }

            is Preference.PreferenceItem.CustomPreference -> {
                item.content()
            }

            is Preference.PreferenceItem.SliderPreference -> {
                val state = rememberPreferenceValue(item.preference)
                BaseSliderItem(
                    value = state.value,
                    valueRange = item.valueRange,
                    steps = item.steps,
                    title = item.title,
                    subtitle = item.subtitle,
                    valueString = item.valueString.takeUnless { it.isNullOrEmpty() }
                        ?: state.value.toString(),
                    onChange = { newValue ->
                        state.value = newValue
                        scope.launch {
                            if (item.onValueChanged(newValue)) {
                                item.preference.set(newValue)
                            } else {
                                state.value = item.preference.get()
                            }
                        }
                    },
                    titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = TitleFontSize),
                    modifier = Modifier.padding(
                        horizontal = PrefsHorizontalPadding,
                        vertical = PrefsVerticalPadding,
                    ),
                    icon = item.icon
                )
            }
        }
    }
}
