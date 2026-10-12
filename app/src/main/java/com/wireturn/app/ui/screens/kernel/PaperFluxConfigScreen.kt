@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.wireturn.app.ui.screens.kernel

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wireturn.app.R
import com.wireturn.app.data.kernel.PaperFluxConfig
import com.wireturn.app.ui.AppDropdownMenu
import com.wireturn.app.ui.AppTopAppBar
import com.wireturn.app.ui.ExpandableSection
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LargeLeadingIcon
import com.wireturn.app.ui.QrCodeDialog
import com.wireturn.app.ui.RowLabel
import com.wireturn.app.ui.ScreenSubtitle
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.SelectionDialog
import com.wireturn.app.ui.ShareDropdownMenu
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.SupportingText
import com.wireturn.app.ui.TextFieldRow
import com.wireturn.app.ui.noFlingExpandConnection
import com.wireturn.app.ui.redact
import com.wireturn.app.ui.screens.QrScannerDialog
import com.wireturn.app.ui.showExclusiveToast
import kotlinx.coroutines.launch

@Composable
fun PaperFluxConfigScreen(
    isEditMode: Boolean = false,
    initialConfig: PaperFluxConfig = PaperFluxConfig(),
    profileName: String? = null,
    privacyMode: Boolean = false,
    onBack: () -> Unit,
    onSave: (PaperFluxConfig) -> Unit
) {
    val isPrivacyActive = privacyMode && isEditMode

    var config by remember(initialConfig) { mutableStateOf(initialConfig) }
    // The documents as typed (one per line) - parsed into the config, never reformatted while typing.
    var documentsText by remember(initialConfig) { mutableStateOf(initialConfig.documentUrls.joinToString("\n")) }
    val current = config.copy(documentUrls = PaperFluxConfig.documents(documentsText))
    val isModified = current != initialConfig

    val showExitDialog = remember { mutableStateOf(false) }
    val showQrDialog = remember { mutableStateOf(false) }
    val showQrScanner = remember { mutableStateOf(false) }
    val showMenu = remember { mutableStateOf(false) }
    val showTransportDialog = remember { mutableStateOf(false) }

    val handleBack = {
        if (isEditMode && isModified) showExitDialog.value = true else onBack()
    }
    BackHandler(enabled = isEditMode && isModified, onBack = handleBack)

    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = topAppBarState,
        flingAnimationSpec = null
    )

    val importSuccessMessage = stringResource(R.string.import_success)
    val importErrorMessage = stringResource(R.string.import_error)

    fun importText(text: String, silentIfBlank: Boolean = false) {
        val parsed = PaperFluxConfig.parse(text)
        if (parsed != null) {
            config = parsed
            documentsText = parsed.documentUrls.joinToString("\n")
            context.showExclusiveToast(importSuccessMessage)
        } else if (!silentIfBlank || text.isNotBlank()) {
            context.showExclusiveToast(importErrorMessage)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        importText(input.bufferedReader().use { r -> r.readText() })
                    }
                } catch (_: Exception) {
                    context.showExclusiveToast(importErrorMessage)
                }
            }
        }
    )

    if (showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { showExitDialog.value = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                    showExitDialog.value = false
                    onSave(current)
                }) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExitDialog.value = false
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_discard))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.noFlingExpandConnection()),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.kernel_paperflux),
                onBack = handleBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showQrScanner.value = true }) {
                        Icon(
                            painter = painterResource(R.drawable.qr_code_24px),
                            contentDescription = stringResource(R.string.qr_import)
                        )
                    }

                    var showImportMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showImportMenu = true }) {
                        Icon(
                            painter = painterResource(R.drawable.note_add_24px),
                            contentDescription = stringResource(R.string.profile_import_group)
                        )
                        AppDropdownMenu(
                            expanded = showImportMenu,
                            onDismissRequest = { showImportMenu = false },
                            title = stringResource(R.string.profile_import_group)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_clipboard)) },
                                leadingIcon = { Icon(painterResource(R.drawable.content_paste_24px), null) },
                                onClick = {
                                    showImportMenu = false
                                    scope.launch {
                                        val clipEntry = clipboard.getClipEntry()
                                        val text = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                                        importText(text, silentIfBlank = true)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_file)) },
                                leadingIcon = { Icon(painterResource(R.drawable.file_open_24px), null) },
                                onClick = {
                                    showImportMenu = false
                                    filePickerLauncher.launch("*/*")
                                }
                            )
                        }
                    }

                    if (isEditMode) {
                        Box {
                            IconButton(onClick = { showMenu.value = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.share_24px),
                                    contentDescription = stringResource(R.string.share)
                                )
                            }
                            ShareDropdownMenu(
                                expanded = showMenu.value,
                                onDismissRequest = { showMenu.value = false },
                                textToShare = current.toUri(profileName),
                                onShowQr = { showQrDialog.value = true }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = (!isEditMode || isModified) && current.isValid(),
                enter = scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = scaleOut(
                    targetScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.navigationBarsPadding(),
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onSave(current)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = {
                        Icon(
                            painter = painterResource(
                                if (isEditMode) R.drawable.save_24px else R.drawable.arrow_forward_ios_24px
                            ),
                            contentDescription = null
                        )
                    },
                    text = { Text(stringResource(if (isEditMode) R.string.btn_save else R.string.btn_next)) }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)
        ) {
            if (isEditMode && profileName != null) {
                ScreenSubtitle(profileName)
            }

            // The server's own profile: what its exit node was started with.
            SectionGroup(title = stringResource(R.string.paperflux_profile_title)) {
                SectionItem(position = ItemPosition.Top) {
                    TextFieldRow(
                        label = stringResource(R.string.paperflux_server_label),
                        value = config.server.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(server = it.trim()) },
                        readOnly = isPrivacyActive,
                        isModified = isEditMode && config.server != initialConfig.server,
                        privacyMode = isPrivacyActive,
                        supportingText = stringResource(R.string.paperflux_server_desc)
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.paperflux_profile_id_label),
                        value = config.profileId,
                        onValueChange = { config = config.copy(profileId = it.filter(Char::isDigit).take(10)) },
                        isError = config.profileIdNumber == null,
                        isModified = isEditMode && config.profileId != initialConfig.profileId,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                SectionItem {
                    TextFieldRow(
                        label = stringResource(R.string.paperflux_token_label),
                        value = config.token.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) config = config.copy(token = it.trim()) },
                        readOnly = isPrivacyActive,
                        isError = config.token.length !in 32..128,
                        isModified = isEditMode && config.token != initialConfig.token,
                        privacyMode = isPrivacyActive,
                        supportingText = stringResource(R.string.paperflux_token_desc),
                        isSecret = true
                    )
                }
                SectionItem(position = ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(R.string.paperflux_client_ip_label),
                        value = config.clientIp,
                        onValueChange = { config = config.copy(clientIp = it.trim()) },
                        placeholder = PaperFluxConfig.DEFAULT_CLIENT_IP,
                        isModified = isEditMode && config.clientIp != initialConfig.clientIp,
                        supportingText = stringResource(R.string.paperflux_client_ip_desc),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
            }

            SectionGroup(title = stringResource(R.string.connection_details)) {
                SectionItem(
                    position = ItemPosition.Top,
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        showTransportDialog.value = true
                    }
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LargeLeadingIcon {
                            Icon(
                                painter = painterResource(paperFluxTransportIcon(config.transport)),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            RowLabel(
                                text = stringResource(R.string.openflux_platform_label),
                                isModified = isEditMode && config.transport != initialConfig.transport
                            )
                            Spacer(Modifier.height(2.dp))
                            SupportingText(config.platformDisplayName)
                        }
                    }
                }
                val hasVolga = config.transport == "yandex"
                SectionItem(position = if (hasVolga) ItemPosition.Middle else ItemPosition.Bottom) {
                    TextFieldRow(
                        label = stringResource(
                            if (config.transport == "cupsonline") R.string.openflux_cups_url_label
                            else R.string.paperflux_documents_label
                        ),
                        value = documentsText.redact(isPrivacyActive),
                        onValueChange = { if (!isPrivacyActive) documentsText = it },
                        readOnly = isPrivacyActive,
                        isError = current.usableDocuments.isEmpty(),
                        isModified = isEditMode && current.documentUrls != initialConfig.documentUrls,
                        privacyMode = isPrivacyActive,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5,
                        supportingText = stringResource(
                            if (hasVolga) R.string.paperflux_documents_desc_yandex else R.string.paperflux_documents_desc
                        )
                    )
                }
                ExpandableSection(visible = hasVolga) {
                    SectionItem(position = ItemPosition.Bottom) {
                        TextFieldRow(
                            label = stringResource(R.string.paperflux_volga_label),
                            value = config.volgaUrl.redact(isPrivacyActive),
                            onValueChange = { if (!isPrivacyActive) config = config.copy(volgaUrl = it.trim()) },
                            readOnly = isPrivacyActive,
                            isModified = isEditMode && config.volgaUrl != initialConfig.volgaUrl,
                            privacyMode = isPrivacyActive,
                            supportingText = stringResource(R.string.paperflux_volga_desc)
                        )
                    }
                }
            }
        }
    }

    if (showTransportDialog.value) {
        SelectionDialog(
            title = stringResource(R.string.openflux_platform_label),
            items = PaperFluxConfig.TRANSPORTS,
            isSelected = { it == config.transport },
            onSelect = {
                config = config.copy(transport = it)
                showTransportDialog.value = false
            },
            onDismiss = { showTransportDialog.value = false }
        ) { value, _ ->
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StandardLeadingIcon {
                    Icon(painter = painterResource(paperFluxTransportIcon(value)), contentDescription = null)
                }
                Text(
                    text = PaperFluxConfig(transport = value).platformDisplayName,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showQrDialog.value) {
        QrCodeDialog(
            text = current.toUri(profileName),
            onDismiss = { showQrDialog.value = false }
        )
    }

    if (showQrScanner.value) {
        QrScannerDialog(
            title = stringResource(R.string.qr_import),
            message = stringResource(R.string.qr_scan_desc),
            onDismiss = { showQrScanner.value = false },
            onResult = { result: String -> importText(result) }
        )
    }
}

private fun paperFluxTransportIcon(transport: String): Int = when (transport) {
    "mailru" -> R.drawable.ic_mailru
    "cupsonline" -> R.drawable.ic_cupsonline
    "boards" -> R.drawable.ic_yandex_boards
    "mtslink" -> R.drawable.ic_mts_link
    else -> R.drawable.ic_yandex_docs
}
