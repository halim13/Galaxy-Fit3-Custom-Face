package com.galaxyfit3.customface

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.galaxyfit3.customface.ui.screens.home.HomeScreen
import com.galaxyfit3.customface.ui.screens.store.StoreScreen
import com.galaxyfit3.customface.ui.screens.editor.EditorScreen
import com.galaxyfit3.customface.ui.screens.preview.PreviewScreen
import com.galaxyfit3.customface.ui.screens.install.InstallScreen
import com.galaxyfit3.customface.ui.screens.about.AboutScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.galaxyfit3.customface.viewmodel.InstallViewModel

object Routes {
    const val HOME = "home"
    const val STORE = "store"
    const val ABOUT = "about"
    const val EDITOR = "editor/{projectId}"
    const val PREVIEW = "preview/{projectId}"
    const val INSTALL = "install/{projectId}"

    fun editor(projectId: String) = "editor/$projectId"
    fun preview(projectId: String) = "preview/$projectId"
    fun install(projectId: String) = "install/$projectId"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenEditor = { id -> navController.navigate(Routes.editor(id)) },
                onOpenStore = { navController.navigate(Routes.STORE) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) }
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.STORE) {
            StoreScreen(
                onBack = { navController.popBackStack() },
                onDownloaded = { id ->
                    navController.popBackStack(Routes.HOME, inclusive = false)
                    navController.navigate(Routes.editor(id))
                }
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("projectId").orEmpty()
            EditorScreen(
                projectId = id,
                onPreview = { navController.navigate(Routes.preview(id)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.PREVIEW,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("projectId").orEmpty()
            PreviewScreen(
                projectId = id,
                onInstall = { navController.navigate(Routes.install(id)) },
                onBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
        composable(
            route = Routes.INSTALL,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            val id = entry.arguments?.getString("projectId").orEmpty()
            InstallScreen(
                projectId = id,
                onBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
    }
}