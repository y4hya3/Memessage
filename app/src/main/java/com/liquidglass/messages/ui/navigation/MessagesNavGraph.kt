package com.liquidglass.messages.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.liquidglass.messages.ui.chat.ChatScreen
import com.liquidglass.messages.ui.components.EmptyState
import com.liquidglass.messages.ui.contactinfo.ContactInfoScreen
import com.liquidglass.messages.ui.conversations.ConversationListScreen
import com.liquidglass.messages.ui.deleted.RecentlyDeletedScreen
import com.liquidglass.messages.ui.settings.SettingsScreen
import com.liquidglass.messages.ui.theme.LiquidTheme

/** SavedState key: message the chat should jump to when shown again. */
private const val JUMP_TO = "jumpToMessage"

/** Sentinel thread id for a chat opened before its Telephony thread exists. */
private const val NO_THREAD_ID: Long = -1L

/** Tablet split-view right-pane destination. */
private sealed interface TabletDetail {
    data object Empty : TabletDetail
    data class Chat(val threadId: Long, val address: String, val jumpToMessageId: Long? = null) : TabletDetail
    data class ContactInfo(val threadId: Long, val address: String) : TabletDetail
    data class Settings(val returnTo: TabletDetail) : TabletDetail
    data class RecentlyDeleted(val returnTo: TabletDetail) : TabletDetail
}

/**
 * The app's single navigation graph: conversation list -> chat / new-message
 * on handheld screens, and a two-pane List-Detail layout on expanded tablets
 * (such as the Samsung Galaxy Tab A7 LTE in landscape).
 */
@Composable
fun MessagesNavGraph(
    navController: NavHostController = rememberNavController(),
    startWithRecipient: String? = null,
    startWithBody: String? = null,
    /** Changes with every new deep link (e.g. a notification tap) so it re-navigates. */
    deepLinkNonce: Int = 0,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isExpandedTablet = maxWidth >= 840.dp
        if (!isExpandedTablet) {
            SinglePaneNavHost(
                navController = navController,
                startWithRecipient = startWithRecipient,
                startWithBody = startWithBody,
                deepLinkNonce = deepLinkNonce,
            )
        } else {
            TabletSplitLayout(
                startWithRecipient = startWithRecipient,
                startWithBody = startWithBody,
                deepLinkNonce = deepLinkNonce,
            )
        }
    }
}

@Composable
private fun SinglePaneNavHost(
    navController: NavHostController,
    startWithRecipient: String?,
    startWithBody: String?,
    deepLinkNonce: Int,
) {
    // Deep-link handling: when launched via SENDTO/SEND we jump to the chat for
    // the supplied recipient. Keyed on recipient+body so a fresh intent with new
    // values re-triggers, but recomposition alone does not double-navigate.
    if (!startWithRecipient.isNullOrBlank()) {
        LaunchedEffect(startWithRecipient, startWithBody, deepLinkNonce) {
            // Already looking at this person's chat: nothing to do.
            val top = navController.currentBackStackEntry
            val onSameChat = top?.destination?.route == Routes.chatPattern &&
                Routes.decodeAddress(top.arguments?.getString(Routes.argAddress)) == startWithRecipient
            if (!onSameChat) {
                navController.navigate(Routes.chat(NO_THREAD_ID, startWithRecipient)) {
                    // Drop whatever chat / info screen is open so the new person's chat
                    // is really shown (single-top alone kept the previous conversation).
                    // The conversation list stays beneath so Back returns to it.
                    popUpTo(Routes.conversations) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    // iOS navigation-stack motion: the new page slides in from the right edge
    // while the old one drifts a third of the way left (and back on pop).
    val push = tween<IntOffset>(durationMillis = 380, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f))
    NavHost(
        navController = navController,
        startDestination = Routes.conversations,
        enterTransition = { slideInHorizontally(push) { it } },
        exitTransition = { slideOutHorizontally(push) { -it / 3 } },
        popEnterTransition = { slideInHorizontally(push) { -it / 3 } },
        popExitTransition = { slideOutHorizontally(push) { it } },
    ) {
        // ---- Conversation list ----
        composable(Routes.conversations) {
            ConversationListScreen(
                onConversationClick = { threadId, address ->
                    navController.navigate(Routes.chat(threadId, address))
                },
                onOpenSettings = { navController.navigate(Routes.settings) },
                onOpenRecentlyDeleted = { navController.navigate(Routes.recentlyDeleted) },
            )
        }

        // ---- Chat (single thread) ----
        composable(
            route = Routes.chatPattern,
            arguments = listOf(
                navArgument(Routes.argThreadId) {
                    type = NavType.LongType
                    defaultValue = NO_THREAD_ID
                },
                navArgument(Routes.argAddress) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val threadId = args?.getLong(Routes.argThreadId) ?: NO_THREAD_ID
            val address = Routes.decodeAddress(args?.getString(Routes.argAddress))

            val jump by backStackEntry.savedStateHandle.getStateFlow<Long?>(JUMP_TO, null).collectAsState()
            ChatScreen(
                threadId = threadId,
                address = address,
                jumpToMessageId = jump,
                onJumpHandled = { backStackEntry.savedStateHandle[JUMP_TO] = null },
                // Only the deep-linked chat gets the shared body.
                initialText = startWithBody.takeIf { address == startWithRecipient },
                onBack = { navController.popBackStack() },
                onOpenInfo = { id, addr -> navController.navigate(Routes.contactInfo(id, addr)) },
            )
        }

        // ---- Contact info sheet ----
        composable(
            route = Routes.contactInfoPattern,
            arguments = listOf(
                navArgument(Routes.argThreadId) {
                    type = NavType.LongType
                    defaultValue = NO_THREAD_ID
                },
                navArgument(Routes.argAddress) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val args = backStackEntry.arguments
            val threadId = args?.getLong(Routes.argThreadId) ?: NO_THREAD_ID
            val address = Routes.decodeAddress(args?.getString(Routes.argAddress))
            ContactInfoScreen(
                threadId = threadId,
                address = address,
                onBack = { navController.popBackStack() },
                onOpenMessage = { id ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(JUMP_TO, id)
                    navController.popBackStack()
                },
                // The chat underneath no longer exists — go straight back to the list.
                onConversationDeleted = { navController.popBackStack(Routes.conversations, inclusive = false) },
            )
        }

        // ---- Recently Deleted ----
        composable(Routes.recentlyDeleted) {
            RecentlyDeletedScreen(onBack = { navController.popBackStack() })
        }

        // ---- Settings ----
        composable(Routes.settings) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun TabletSplitLayout(
    startWithRecipient: String?,
    startWithBody: String?,
    deepLinkNonce: Int,
) {
    val colors = LiquidTheme.colors
    var detail by remember { mutableStateOf<TabletDetail>(TabletDetail.Empty) }

    if (!startWithRecipient.isNullOrBlank()) {
        LaunchedEffect(startWithRecipient, startWithBody, deepLinkNonce) {
            detail = TabletDetail.Chat(NO_THREAD_ID, startWithRecipient)
        }
    }

    BackHandler(enabled = detail != TabletDetail.Empty) {
        detail = when (val current = detail) {
            is TabletDetail.ContactInfo -> TabletDetail.Chat(current.threadId, current.address)
            is TabletDetail.Settings -> current.returnTo
            is TabletDetail.RecentlyDeleted -> current.returnTo
            is TabletDetail.Chat -> TabletDetail.Empty
            TabletDetail.Empty -> TabletDetail.Empty
        }
    }

    val selectedThreadId = when (val current = detail) {
        is TabletDetail.Chat -> current.threadId
        is TabletDetail.ContactInfo -> current.threadId
        else -> null
    }

    Row(Modifier.fillMaxSize().background(colors.listBackground)) {
        Box(Modifier.width(360.dp).fillMaxHeight()) {
            ConversationListScreen(
                selectedThreadId = selectedThreadId,
                onConversationClick = { threadId, address ->
                    detail = TabletDetail.Chat(threadId, address)
                },
                onOpenSettings = {
                    val returnTarget = (detail as? TabletDetail.Chat) ?: TabletDetail.Empty
                    detail = TabletDetail.Settings(returnTarget)
                },
                onOpenRecentlyDeleted = {
                    val returnTarget = (detail as? TabletDetail.Chat) ?: TabletDetail.Empty
                    detail = TabletDetail.RecentlyDeleted(returnTarget)
                },
            )
        }

        Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.divider))

        Box(Modifier.weight(1f).fillMaxHeight().background(colors.chatBackground)) {
            when (val current = detail) {
                TabletDetail.Empty -> {
                    EmptyState(
                        title = "No Conversation Selected",
                        subtitle = "Select a conversation on the left or tap compose to start a new thread.",
                        icon = Icons.Rounded.ChatBubbleOutline,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is TabletDetail.Chat -> {
                    key(current.threadId, current.address) {
                        ChatScreen(
                            threadId = current.threadId,
                            address = current.address,
                            jumpToMessageId = current.jumpToMessageId,
                            onJumpHandled = {
                                if (detail is TabletDetail.Chat) {
                                    detail = (detail as TabletDetail.Chat).copy(jumpToMessageId = null)
                                }
                            },
                            initialText = startWithBody.takeIf { current.address == startWithRecipient },
                            showBackButton = false,
                            onBack = { detail = TabletDetail.Empty },
                            onOpenInfo = { id, addr -> detail = TabletDetail.ContactInfo(id, addr) },
                        )
                    }
                }
                is TabletDetail.ContactInfo -> {
                    key(current.threadId, current.address) {
                        ContactInfoScreen(
                            threadId = current.threadId,
                            address = current.address,
                            onBack = { detail = TabletDetail.Chat(current.threadId, current.address) },
                            onOpenMessage = { msgId ->
                                detail = TabletDetail.Chat(current.threadId, current.address, msgId)
                            },
                            onConversationDeleted = { detail = TabletDetail.Empty },
                        )
                    }
                }
                is TabletDetail.Settings -> {
                    SettingsScreen(onBack = { detail = current.returnTo })
                }
                is TabletDetail.RecentlyDeleted -> {
                    RecentlyDeletedScreen(onBack = { detail = current.returnTo })
                }
            }
        }
    }
}
