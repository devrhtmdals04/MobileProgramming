package com.example.study_helper

import androidx.compose.ui.window.ComposeUIViewController
import com.example.study_helper.study.NotebookHost

fun NotebookViewController(host: NotebookHost) = ComposeUIViewController { host.Content() }
