package com.example.readalong.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.example.readalong.ios.ui.App
import platform.UIKit.UIViewController

/** The app's only UIKit entry point; iOSApp.swift shows this controller full screen. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
