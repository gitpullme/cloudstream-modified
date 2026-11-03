package com.lagradost.cloudstream3.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.profileImages
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream4.compose.Screen
import com.lagradost.cloudstream4.compose.TV
import com.lagradost.cloudstream4.compose.WhiteButton
import com.lagradost.cloudstream4.compose.circleBorder
import com.lagradost.cloudstream4.compose.focusOutline
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.mihon.material.padding
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.SettingSearchResults
import com.mihon.presentation.settings.SettingsData
import com.mihon.presentation.settings.widget.TextPreferenceWidget
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SettingsFragmentScreen : Screen {
    val screens = persistentListOf(
        SettingsNavigation(
            title = R.string.category_general,
            navigation = R.id.action_navigation_global_to_navigation_settings_general,
            screen = SettingsGeneralScreen,
            icon = R.drawable.build_24px,
            subtitle = persistentListOf(
                R.string.extension_language,
                R.string.title_downloads,
                R.string.pref_category_bypass,
                R.string.pref_category_links
            )
        ),
        SettingsNavigation(
            title = R.string.category_player,
            navigation = R.id.action_navigation_global_to_navigation_settings_player,
            screen = SettingsPlayerScreen,
            icon = R.drawable.play_arrow_24px,
            subtitle = persistentListOf(
                R.string.pref_category_subtitles,
                R.string.pref_category_player_features,
                R.string.pref_category_gestures,
                R.string.pref_category_player_layout,
                R.string.pref_category_cache
            )
        ),
        /*SettingsNavigation(
            title = R.string.category_providers,
            navigation = R.id.action_navigation_global_to_navigation_settings_providers,
            screen = SettingsProvidersScreen,
            icon = R.drawable.build_24px,
        ),*/
        SettingsNavigation(
            title = R.string.category_ui,
            navigation = R.id.action_navigation_global_to_navigation_settings_ui,
            screen = SettingsUIScreen,
            icon = R.drawable.format_paint_24px,
            subtitle = persistentListOf(
                R.string.pref_category_looks,
                R.string.pref_category_ui_features,
                R.string.search_poster_img_des,
                R.string.poster_ui_settings
            )
        ),
        SettingsNavigation(
            title = R.string.category_updates,
            navigation = R.id.action_navigation_global_to_navigation_settings_updates,
            screen = SettingsUpdatesScreen,
            icon = R.drawable.mobile_arrow_down_24px,
            subtitle = persistentListOf(
                R.string.pref_category_app_updates,
                R.string.pref_category_backup,
                R.string.pref_category_extensions,
                R.string.pref_category_actions
            )
        ),
        SettingsNavigation(
            title = R.string.category_account,
            navigation = R.id.action_navigation_global_to_navigation_settings_account,
            screen = SettingsAccountScreen,
            icon = R.drawable.encrypted_24px,
            subtitle = persistentListOf(
                R.string.pref_category_accounts,
                R.string.pref_category_security
            )
        ),
        SettingsNavigation(
            title = R.string.pref_category_extensions,
            navigation = R.id.action_navigation_global_to_navigation_settings_extensions,
            screen = null,
            icon = R.drawable.extension_24px,
            subtitle = persistentListOf(R.string.add_repository)
        ),
    )

    @Immutable
    data class SettingsNavigation(
        val title: Int,
        val navigation: Int,
        val screen: SearchableSettings?,
        val icon: Int,
        val subtitle: PersistentList<Int>,
    )


    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun Content() {
        val textFieldState = rememberTextFieldState()

        val outerListState = rememberScrollState()

        val parentFirstScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val delta = available.y
                    return if (delta < 0 && outerListState.canScrollForward) {
                        val consumed = outerListState.dispatchRawDelta(-delta)
                        Offset(0f, -consumed)
                    } else {
                        Offset.Zero
                    }
                }
            }
        }

        Scaffold { _ ->
            Column(modifier = Modifier.verticalScroll(outerListState)) {
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

                val default = DataStoreHelper.getDefaultAccount(
                    LocalContext.current
                )
                val flow by DataStoreHelper.selectedAccountNumberFlow.collectAsState()
                val account = remember(flow) {
                    DataStoreHelper.getCurrentAccount() ?: default
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusOutline()
                        .clickable {
                            activity.navigate(
                                R.id.accountSelectActivity,
                                Bundle().apply { putBoolean("isFromMainActivity", true) }
                            )
                        }
                        .padding(
                            vertical = MaterialTheme.padding.large,
                            horizontal = MaterialTheme.padding.medium
                        )
                ) {
                    val image =
                        account.customImage ?: profileImages.getOrNull(account.defaultImageIndex)
                        ?: profileImages.first()

                    AsyncImage(
                        contentScale = ContentScale.Crop,
                        model = image,
                        modifier = Modifier.circleBorder(50.dp),
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(MaterialTheme.padding.medium))
                    Column {
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.title_settings),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                SettingsSearch(textFieldState = textFieldState)
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))

                SettingSearchResults(
                    nestedScrollConnection = parentFirstScrollConnection,
                    searchKey = textFieldState.text.toString(),
                    deferredItems = ::generateSearchItems,
                    onItemClick = { item ->
                        SearchableSettings.highlightKey = item.highlightKey
                        activity?.navigate(item.navigation)
                    }, empty = {
                        Column(
                            modifier = Modifier
                                .nestedScroll(parentFirstScrollConnection)
                        ) {
                            screens.forEach { settingsTab ->
                                SettingsTab(settingsTab)
                            }
                            BuildStamp()
                        }
                    })

            }
        }
    }

    @Composable
    @NonRestartableComposable
    fun generateSearchItems() =
        screens.mapNotNull { item ->
            val contents = item.screen?.getPreferences() ?: return@mapNotNull null
            SettingsData(
                title = stringResource(item.title),
                navigation = item.navigation,
                contents = contents
            )
        }.toPersistentList()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun SettingsSearch(textFieldState: TextFieldState) {
        var hasFocus by remember { mutableStateOf(false) }
        var isWritable by remember { mutableStateOf(false) }
        val focusProgress by animateFloatAsState(targetValue = if (hasFocus) 1.0f else 0.0f)
        val focusManager = LocalFocusManager.current
        val focusRequester = remember { FocusRequester() }
        val isTv = isLayout(TV)
        TextField(
            state = textFieldState,
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Search,
                /**
                 * We do not want to pull up the keyboard when navigating to the item on TV, therefore
                 * we listen to when the TV user presses some key to "extra" focus it
                 *
                 * showKeyboardOnFocus is somewhat bugged, so need to change this to true when you
                 * press it, as otherwise no keyboard will show even with keyboardController
                 * */
                showKeyboardOnFocus = if (isTv) isWritable else true
            ),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp - 12.dp * focusProgress)
                .focusOutline(enabled = isTv, CircleShape)
                .onFocusChanged { newFocus ->
                    hasFocus = newFocus.hasFocus
                    /** Loss of focus -> readonly */
                    if (!newFocus.hasFocus) {
                        isWritable = false
                    }
                }
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    if (isWritable || !isTv || !hasFocus || keyEvent.type != KeyEventType.KeyUp) {
                        return@onPreviewKeyEvent false
                    }

                    /** Focus + press once -> writable */
                    return@onPreviewKeyEvent when (keyEvent.key) {
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter, Key.Spacebar -> {
                            isWritable = true
                            true
                        }

                        else -> false
                    }
                },
            placeholder = {
                Text(text = stringResource(R.string.search_hint))
            },
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
                focusedTrailingIconColor = MaterialTheme.colorScheme.onBackground,
                focusedLeadingIconColor = MaterialTheme.colorScheme.onBackground,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.onBackground,
            ),
            leadingIcon = {
                Crossfade(
                    targetState = hasFocus,
                    label = "leftsearch",
                ) { value ->
                    if (value) {
                        IconButton(onClick = {
                            textFieldState.edit { replace(0, length, "") }
                            focusManager.clearFocus()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.keyboard_arrow_left_24px),
                                contentDescription = null
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            focusRequester.requestFocus()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.search_icon),
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            trailingIcon = {
                Crossfade(
                    targetState = hasFocus,
                    label = "rightsearch",
                ) { value ->
                    if (value) {
                        IconButton(onClick = {
                            textFieldState.edit { replace(0, length, "") }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = null
                            )
                        }
                    }
                }
            },
        )

        val keyboardController = LocalSoftwareKeyboardController.current
        DisposableEffect(Unit) {
            onDispose {
                keyboardController?.hide()
            }
        }
    }

    @Composable
    fun SettingsTab(settingsTab: SettingsNavigation) {
        TextPreferenceWidget(
            title = stringResource(settingsTab.title),
            icon = painterResource(settingsTab.icon),
            // This can not be converted to joinToString due to stringResource being composable
            subtitle =
                @Suppress("SimplifiableCallChain")
                settingsTab.subtitle.map { stringResource(it) }.joinToString(),
            // Clear it if we have already set it but navigated back instantly
            onPreferenceClick = {
                SearchableSettings.highlightKey = null
                activity?.navigate(settingsTab.navigation)
            })
    }

    @Composable
    fun BuildStamp() {
        var showEasterEgg by remember { mutableStateOf(false) }
        val (commitHash, buildTimestamp) = remember {
            val commitHash = activity?.currentCommitHash() ?: ""
            val buildTimestamp = SimpleDateFormat.getDateTimeInstance(
                DateFormat.LONG, DateFormat.MEDIUM,
                Locale.getDefault()
            ).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")
            commitHash to buildTimestamp
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .focusOutline()
                    .clickable {
                        clipboardHelper(
                            txt(R.string.extension_version),
                            "${BuildConfig.VERSION_NAME} $commitHash $buildTimestamp"
                        )
                    },
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    Text(text = BuildConfig.VERSION_NAME)
                    if (commitHash != "") {
                        Text("•")
                        Text(text = commitHash)
                    }
                    Text("•")
                    Text(text = buildTimestamp)
                }
            }

            DeveloperSignature(
                onClick = { showEasterEgg = true }
            )
        }

        if (showEasterEgg) {
            DeveloperEasterEggDialog(
                onDismiss = { showEasterEgg = false }
            )
        }
    }

    @Composable
    fun DeveloperSignature(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val cardShape = RoundedCornerShape(14.dp)
        Surface(
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            ),
            modifier = modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .focusOutline(shape = cardShape)
                .clip(cardShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Developer signature",
                    onClick = onClick,
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                // Monogram container
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .semantics { contentDescription = "DA Monogram" }
                ) {
                    Text(
                        text = "DA",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 1.sp,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Signature text hierarchy
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "A PERSONAL TOUCH",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            painter = painterResource(R.drawable.kid_star_24px),
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        )
                    }

                    Spacer(modifier = Modifier.height(1.dp))

                    Text(
                        text = "built by dikshant",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = "almighty :)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Subtle sparkle detail on the trailing side
                Icon(
                    painter = painterResource(R.drawable.kid_star_24px),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
        }
    }

    @Composable
    fun DeveloperEasterEggDialog(
        onDismiss: () -> Unit
    ) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp)
                        )
                        .semantics { contentDescription = "DA Monogram" }
                ) {
                    Text(
                        text = "DA",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 1.sp,
                    )
                }
            },
            title = {
                Text(
                    text = "built by dikshant almighty :)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    text = "this was modified by dikshant for his people, hope you like it",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                WhiteButton(
                    text = stringResource(R.string.ok),
                    modifier = Modifier.focusRequester(focusRequester),
                    onClick = onDismiss
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(20.dp),
        )
    }
}


@PreviewLightDark
@Composable
fun Preview() {
    CloudStreamPreviewTheme {
        SettingsFragmentScreen.Content()
    }
}