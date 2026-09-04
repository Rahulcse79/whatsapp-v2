package com.whatsappv2

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * A bare Activity that Hilt can inject into, for Compose tests.
 *
 * `createComposeRule()` hosts content in a plain `ComponentActivity`, which is not an
 * `@AndroidEntryPoint`, so any composable calling `hiltViewModel()` fails with an
 * IllegalStateException. Tests use `createAndroidComposeRule<HiltTestActivity>()` instead.
 *
 * Debug source only: it must never reach a release build, and it is registered in the
 * debug manifest for the same reason.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
