package com.example.study_helper.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DriveSyncScreen(sync: DriveSync, onClose: () -> Unit, onChanged: () -> Unit) {
    if(sync.mirror != null) { FolderShareScreen(sync,onClose,onChanged); return }
    val scope = rememberCoroutineScope()
    val state = sync.state
    var folderName by remember { mutableStateOf("") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { onChanged(); onClose() }, enabled = !state.busy) { Text("‹ 서재로") }
                Text("공유 저장소 · Google Drive", style = MaterialTheme.typography.headlineSmall)
                Text("노트(.md)·문제 JSON을 기기에 보관하고 양방향으로 동기화합니다. 하위 폴더도 읽습니다.")
                Text("연결 폴더: ${state.folderName.ifEmpty { "선택하지 않음" }}")
                if (!state.connected) Text("기존 폴더와 하위 파일을 읽기 위해 Google 로그인에서 Drive 전체 접근 권한을 요청합니다. 앱은 선택한 폴더의 노트·문제만 동기화합니다.", style = MaterialTheme.typography.bodySmall)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { sync.connect() } }, enabled = !state.busy) { Text(if (state.connected) "폴더 찾아보기" else "Google 연결") }
                    if (state.connected) TextButton(onClick = sync::disconnect, enabled = !state.busy) { Text("로그아웃") }
                }
                Button(onClick = { scope.launch { sync.sync(); onChanged() } }, enabled = !state.busy && state.folderId.isNotEmpty()) { Text("지금 동기화") }
                Text("기기의 노트·문제도 선택한 폴더에 업로드됩니다. 동시에 수정한 자료는 충돌 사본으로 보관하며, 삭제는 다른 기기로 전파하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                if (state.connected) {
                    HorizontalDivider()
                    Row {
                        TextButton(onClick = { scope.launch { sync.up() } }, enabled = !state.busy) { Text("↑ 상위") }
                        TextButton(onClick = sync::selectCurrentFolder, enabled = !state.busy && state.browsingId != "root") { Text("이 폴더 연결") }
                    }
                    Text(state.browsingName, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(folderName, { folderName = it }, Modifier.weight(1f), label = { Text("새 폴더 이름") }, singleLine = true, enabled = !state.busy)
                        TextButton(onClick = { scope.launch { sync.createFolder(folderName); folderName = "" } }, enabled = !state.busy && folderName.isNotBlank()) { Text("만들기") }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.entries, key = { it.id }) { entry ->
                            TextButton(onClick = { scope.launch { sync.browse(entry) } }, enabled = entry.folder && !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text((if (entry.folder) "폴더  ›  " else "파일  ·  ") + entry.name, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}
