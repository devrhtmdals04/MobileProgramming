package com.example.study_helper.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
internal fun NotesEditor(
    title: String, notes: String, onTitle: (String) -> Unit, onNotes: (String) -> Unit,
    validate: (String, String) -> String?, onClose: () -> Unit, onSave: suspend () -> String?,
) {
    var attempted by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    val initialTitle = rememberSaveable { title }
    val initialNotes = rememberSaveable { notes }
    val changed = title != initialTitle || notes != initialNotes
    val scope = rememberCoroutineScope()
    val error = remember(title, notes) { validate(title, notes) }
    val close = { if (!saving) { if (changed) confirmClose = true else onClose() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("노트 편집", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = close, enabled = !saving) { Text("닫기") }
                }
                Text("제목, 문단, 목록, 표와 코드를 자유롭게 적으세요.\n저장하면 읽기 화면에서 볼 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp)
                OutlinedTextField(value = title, onValueChange = { if (it.length <= 60) onTitle(it) },
                    enabled = !saving, label = { Text("노트 제목") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = notes, onValueChange = { if (it.length <= 100_000) onNotes(it) },
                    enabled = !saving, label = { Text("마크다운 원문") }, minLines = 10,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), isError = attempted && error != null)
                Text("본문 최대 100,000자 · 마크다운 원문으로 저장됩니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 21.sp)
                if (attempted && error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                Button(onClick = {
                    attempted = true
                    if (error == null) scope.launch {
                        saving = true
                        try { saveError = onSave() } finally { saving = false }
                    }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (saving) "저장하고 있어요…" else "노트 저장", fontWeight = FontWeight.Bold)
                }
            }
        }
        if (confirmClose) AlertDialog(
            onDismissRequest = { confirmClose = false }, title = { Text("수정한 내용을 닫을까요?") },
            text = { Text("저장하지 않은 변경 내용은 사라져요.") },
            confirmButton = { TextButton(onClick = onClose) { Text("저장하지 않고 닫기") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("계속 편집") } },
        )
    }
}
