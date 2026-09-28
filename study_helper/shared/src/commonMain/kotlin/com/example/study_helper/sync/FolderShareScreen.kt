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
fun FolderShareScreen(sync:DriveSync,onClose:()->Unit,onChanged:()->Unit) {
    val scope=rememberCoroutineScope(); val state=sync.state; val mirror=sync.mirror!!; val folder=mirror.state
    var picking by remember { mutableStateOf(state.folderId.isEmpty()) }
    var name by remember { mutableStateOf("학습") }
    LaunchedEffect(Unit) { sync.folderAction("status") }
    MaterialTheme { Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                TextButton(onClick={onChanged();onClose()},enabled=!state.busy) { Text("‹ 서재로") }
                Text("공부 폴더 공유",style=MaterialTheme.typography.headlineSmall)
                Text(if(folder.desktop) "공부 폴더를 Drive에 올리고 다른 기기의 변경사항을 받습니다." else "Drive의 공부 폴더를 받고, 이 기기의 노트와 녹음을 올립니다.")
            }
            if(folder.desktop) item {
                Text("내 공부 폴더: ${folder.label.ifEmpty { "선택하지 않음" }}")
                Row {
                    OutlinedButton(onClick={scope.launch {sync.folderAction("choose")}},enabled=!state.busy) { Text(if(folder.label.isEmpty()) "올릴 폴더 선택" else "공부 폴더 변경") }
                    TextButton(onClick={scope.launch {sync.folderAction("open")}},enabled=!state.busy && folder.label.isNotEmpty()) { Text("폴더 열기") }
                }
            }
            item {
                Text("Drive 공유 폴더: ${state.folderName.ifEmpty { "선택하지 않음" }}")
                Row {
                    OutlinedButton(onClick={picking=true;scope.launch {sync.connect()}},enabled=!state.busy) { Text(if(state.folderId.isEmpty()) "Google Drive에서 폴더 선택" else "공유 폴더 변경") }
                    if(state.connected) TextButton(onClick=sync::disconnect,enabled=!state.busy) {Text("로그아웃")}
                }
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.busy && folder.result.isNotEmpty()) Text(folder.result)
                if(!state.busy && folder.result.isNotEmpty() && state.message != folder.result) Text(folder.result,
                    color=if(folder.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if(state.message.isNotBlank()) Text(state.message)
            }
            if(picking && state.connected) {
                item {
                    HorizontalDivider(); Text("선택할 위치: ${state.browsingName}")
                    Row {
                        TextButton(onClick={scope.launch {sync.up()}},enabled=!state.busy && state.browsingId!="root") {Text("↑ 상위 폴더")}
                        Button(onClick={sync.selectCurrentFolder();picking=false},enabled=!state.busy && state.browsingId!="root") {Text("이 폴더로 공유")}
                    }
                    Row {
                        OutlinedTextField(name,{name=it},Modifier.weight(1f),label={Text("새 공유 폴더 이름")},singleLine=true,enabled=!state.busy)
                        TextButton(onClick={scope.launch {sync.createFolder(name)}},enabled=!state.busy && name.isNotBlank()) {Text("만들기")}
                    }
                    if(state.entries.isEmpty() && !state.busy) Text("아직 올라온 자료가 없습니다. 이 폴더를 선택한 뒤 PC에서 공부 폴더를 올려주세요.")
                }
                items(state.entries,key={"remote-${it.id}"}) { entry ->
                    if(entry.folder) TextButton(onClick={scope.launch {sync.browse(entry)}},enabled=!state.busy) {Text("📁 ${entry.name} ›")}
                    else Text(entry.name)
                }
            }
            if(state.folderId.isNotEmpty() && !picking) {
                item {
                    Button(onClick={scope.launch {sync.syncFolder();onChanged()}},enabled=!state.busy && (!folder.desktop || folder.label.isNotEmpty()),modifier=Modifier.fillMaxWidth()) {Text("지금 동기화")}
                    Row {
                        TextButton(onClick={scope.launch {sync.syncFolder(preview=true)}},enabled=!state.busy) {Text("변경사항 확인")}
                        TextButton(onClick={scope.launch {sync.folderAction("recordings");onChanged()}},enabled=!state.busy) {Text("저장한 녹음 올리기")}
                    }
                    TextButton(onClick={scope.launch {sync.folderAction("notes");onChanged()}},enabled=!state.busy) {Text("기존 서재 노트도 올리기")}
                    Text("PDF·노트·문제·그림·녹음을 폴더 구조 그대로 공유합니다. 녹음은 저장을 마친 뒤 올려주세요.",style=MaterialTheme.typography.bodySmall)
                    Text("삭제는 Drive 휴지통 또는 이 기기 보관함으로 옮깁니다. 양쪽에서 변경된 파일은 충돌로 표시합니다.",style=MaterialTheme.typography.bodySmall)
                    if(folder.files.isEmpty() && !state.busy) Text("아직 받은 자료가 없습니다. PC에서 올렸다면 ‘지금 동기화’를 눌러 받아주세요.")
                    Text(if(folder.finished) "이번 동기화 내역" else "변경사항 목록 · 전체 반영 전",style=MaterialTheme.typography.titleMedium)
                }
                items(folder.changes,key={"change-${it.path}"}) {Text("${it.action} · ${it.path}\n${it.detail}")}
                item { HorizontalDivider(); Text(if(folder.finished) "이 기기의 자료" else "공유할 자료 · 동기화 후 열 수 있습니다",style=MaterialTheme.typography.titleMedium) }
                items(folder.files,key={"file-$it"}) { path ->
                    TextButton(onClick={
                        if(mirror.openNote(path)) {onChanged();onClose()}
                        else scope.launch {sync.folderAction("open",path)}
                    },enabled=!state.busy && folder.finished) {Text(path)}
                }
            }
        }
    } }
}
