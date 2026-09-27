package com.example.study_helper

import androidx.compose.ui.window.ComposeUIViewController
import com.example.study_helper.study.NotebookHost

fun NotebookViewController(host: NotebookHost) = ComposeUIViewController { host.Content() }

fun LectureViewController(host: com.example.study_helper.study.LectureHost) = ComposeUIViewController {
    com.example.study_helper.study.LectureRecordings(host)
}
