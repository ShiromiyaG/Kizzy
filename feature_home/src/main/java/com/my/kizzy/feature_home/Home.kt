/*
 * ******************************************************************
 * * * Copyright (C) 2022
 * * * Home.kt is part of Kizzy
 * * * and can not be copied and/or distributed without the express
 * * * permission of yzziK(Vaibhav)
 * * *****************************************************************
 */

package com.my.kizzy.feature_home

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.my.kizzy.domain.model.toVersion
import com.my.kizzy.domain.model.user.User
import com.my.kizzy.feature_home.feature.Features
import com.my.kizzy.feature_home.feature.HomeFeature
import com.my.kizzy.feature_home.feature.ToolTipContent
import com.my.kizzy.feature_settings.SettingsDrawer
import com.my.kizzy.resources.R
import com.my.kizzy.ui.components.UpdateDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(
    state: HomeScreenState,
    checkForUpdates: () -> Unit,
    showBadge: Boolean,
    features: List<HomeFeature>,
    user: User?,
    navigateToProfile: () -> Unit,
    navigateToStyleAndAppearance: () -> Unit,
    navigateToLanguages: () -> Unit,
    navigateToRpcSettings: () -> Unit,
    navigateToLogsScreen: () -> Unit,
) {
    val ctx = LocalContext.current
    var homeItems by remember { mutableStateOf(features) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(features) {
        homeItems = features
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState(),
            canScroll = { true })
    val isCollapsed = scrollBehavior.state.collapsedFraction > 0.55f
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1000)
        isCheckingUpdate = true
        checkForUpdates()
        isCheckingUpdate = false
    }
    
    if (showUpdateDialog && state is HomeScreenState.LoadingCompleted) {
        val hasUpdate = state.release.toVersion().whetherNeedUpdate(BuildConfig.VERSION_NAME.toVersion())
        if (hasUpdate) {
            with(state.release) {
                UpdateDialog(
                    newVersionPublishDate = publishedAt ?: "",
                    newVersionSize = assets?.getOrNull(0)?.size ?: 0,
                    newVersionLog = body ?: "",
                    downloadUrl = assets?.firstOrNull { it?.name?.endsWith(".apk") == true }?.browserDownloadUrl
                        ?: "https://github.com/ShiromiyaG/Kizzy/releases/latest/download/app-release.apk",
                    onDismissRequest = { showUpdateDialog = false }
                )
            }
        } else {
            Toast.makeText(ctx, ctx.getString(R.string.update_no_updates_available), Toast.LENGTH_SHORT).show()
            showUpdateDialog = false
        }
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            if (drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
                ModalDrawerSheet(
                    modifier = Modifier.width(300.dp)
                ) {
                    SettingsDrawer(
                        user = user,
                        navigateToProfile = navigateToProfile,
                        navigateToStyleAndAppearance = navigateToStyleAndAppearance,
                        navigateToLanguages = navigateToLanguages,
                        navigateToRpcSettings = navigateToRpcSettings,
                        navigateToLogsScreen = navigateToLogsScreen
                    )
                }
            }
        }) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.welcome) + ", ${user?.globalName ?: user?.username ?: ""}",
                            style = if (isCollapsed) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                            maxLines = if (isCollapsed) 1 else Int.MAX_VALUE,
                            overflow = if (isCollapsed) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip,
                            modifier = Modifier.padding(end = if (isCollapsed) 0.dp else 12.dp)
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                        ) {
                            Icon(
                                Icons.Outlined.Menu, Icons.Outlined.Menu.name,
                            )
                        }
                    },
                    actions = {
                        UpdateIcon(
                            showBadge = showBadge,
                            isChecking = isCheckingUpdate,
                            onUpdateClick = {
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(R.string.update_check_for_update),
                                    Toast.LENGTH_SHORT
                                ).show()
                                isCheckingUpdate = true
                                checkForUpdates()
                                showUpdateDialog = true
                                isCheckingUpdate = false
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        UserAvatar(
                            user = user,
                            onClick = navigateToProfile
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = stringResource(id = R.string.features),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(start = 15.dp, top = 15.dp)
                    )
                }
                item {
                    Features(homeItems) { selectedIndex ->
                        homeItems = homeItems.mapIndexed { index, item ->
                            item.copy(isChecked = index == selectedIndex && !item.isChecked)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateIcon(
    showBadge: Boolean,
    isChecking: Boolean,
    onUpdateClick: () -> Unit
) {
    val updateDescription = stringResource(R.string.update)
    
    if (isChecking) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
    } else {
        val icon = @Composable {
            Icon(
                imageVector = Icons.Outlined.Update,
                contentDescription = updateDescription,
                modifier = Modifier
                    .clickable(onClick = onUpdateClick)
                    .semantics { contentDescription = updateDescription }
            )
        }
        
        if (showBadge) {
            BadgedBox(
                badge = {
                    Badge(
                        modifier = Modifier
                            .offset(8.dp, (-14).dp)
                            .size(8.dp)
                            .clip(CircleShape),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                }
            ) { icon() }
        } else {
            icon()
        }
    }
}

@Composable
private fun UserAvatar(
    user: User?,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        if (user != null) {
            AsyncImage(
                model = user.getAvatarImage(),
                modifier = Modifier
                    .size(52.dp)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.secondaryContainer,
                        CircleShape,
                    )
                    .clip(CircleShape),
                placeholder = painterResource(R.drawable.error_avatar),
                error = painterResource(R.drawable.error_avatar),
                contentDescription = stringResource(R.string.profile_picture, user.username ?: "")
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = stringResource(R.string.profile),
            )
        }
    }
}

@Preview
@Composable
fun HomeScreenPreview() {
    val fakeFeatures = listOf(
        HomeFeature(
            title = stringResource(R.string.main_customRpc),
            icon = R.drawable.ic_rpc_placeholder,
            shape = RoundedCornerShape(44.dp, 20.dp, 44.dp, 20.dp),
            tooltipText = ToolTipContent.CUSTOM_RPC_DOCS
        ),
        HomeFeature(
            title = stringResource(R.string.main_consoleRpc),
            icon = R.drawable.ic_console_games,
            shape = RoundedCornerShape(20.dp, 44.dp, 20.dp, 44.dp),
            tooltipText = ToolTipContent.CONSOLE_RPC_DOCS
        ),
        HomeFeature(
            title = stringResource(R.string.main_appsRpc),
            icon = R.drawable.ic_dev_rpc,
            shape = RoundedCornerShape(20.dp, 44.dp, 20.dp, 44.dp),
            tooltipText = ToolTipContent.EXPERIMENTAL_RPC_DOCS
        )
    )
    Home(
        state = HomeScreenState.Loading,
        checkForUpdates = {},
        showBadge = true,
        features = fakeFeatures,
        user = fakeUser,
        navigateToProfile = { },
        navigateToStyleAndAppearance = { },
        navigateToLanguages = { },
        navigateToRpcSettings = { },
        navigateToLogsScreen = { }
    )
}

val fakeUser = User(
    accentColor = null,
    avatar = null,
    avatarDecoration = null,
    badges = null,
    banner = null,
    bannerColor = null,
    discriminator = "3050",
    id = null,
    publicFlags = null,
    username = "yzziK",
    special = null,
    verified = false,
    nitro = true,
    bio = "Hello 👋"
)
