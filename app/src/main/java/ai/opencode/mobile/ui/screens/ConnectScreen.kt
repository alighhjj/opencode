package ai.opencode.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.opencode.mobile.data.Server
import ai.opencode.mobile.data.ServerInput
import ai.opencode.mobile.ui.theme.OpenCodeColors

@Composable
fun ConnectScreen(
    recents: List<Server>,
    onConnect: (Server, String?) -> Unit,
    onForget: (String) -> Unit,
) {
    val last = recents.firstOrNull()
    var address by rememberSaveable { mutableStateOf(last?.label.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(last?.password.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun connect() {
        val target = ServerInput.parse(address)
        if (target == null) {
            error = "地址格式不正确，试试 192.168.1.10:4096"
            return
        }
        error = null
        onConnect(Server(target.baseUrl, password.trim()), target.path)
    }

    Box(Modifier.fillMaxSize().background(OpenCodeColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Text(
                text = "OpenCode",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                color = OpenCodeColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "连接电脑上的 OpenCode 服务器，在手机上继续编码会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = OpenCodeColors.TextSecondary,
            )

            Spacer(Modifier.height(28.dp))
            FieldLabel("服务器地址")
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("192.168.1.10:4096", color = OpenCodeColors.TextSecondary.copy(alpha = 0.6f)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("服务器密码（可选）")
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("未设置密码可留空", color = OpenCodeColors.TextSecondary.copy(alpha = 0.6f)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { connect() }),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors(),
            )

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = OpenCodeColors.Danger, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { connect() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OpenCodeColors.Accent,
                    contentColor = Color(0xFF06122E),
                ),
            ) {
                Text("连接", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }

            Spacer(Modifier.height(24.dp))
            HelpCard()

            if (recents.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = "最近连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenCodeColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                recents.forEach { server ->
                    RecentRow(
                        server = server,
                        onSelect = {
                            address = server.label
                            password = server.password
                            error = null
                            onConnect(server, null)
                        },
                        onForget = { onForget(server.url) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(OpenCodeColors.AccentDeep.copy(alpha = 0.18f))
            .border(1.dp, OpenCodeColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ">_",
            color = OpenCodeColors.Accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = OpenCodeColors.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OpenCodeColors.Accent,
    unfocusedBorderColor = OpenCodeColors.Border,
    focusedContainerColor = OpenCodeColors.Surface,
    unfocusedContainerColor = OpenCodeColors.Surface,
    cursorColor = OpenCodeColors.Accent,
    focusedTextColor = OpenCodeColors.TextPrimary,
    unfocusedTextColor = OpenCodeColors.TextPrimary,
)

@Composable
private fun HelpCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OpenCodeColors.Surface)
            .border(1.dp, OpenCodeColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "在电脑上启动服务",
            style = MaterialTheme.typography.labelLarge,
            color = OpenCodeColors.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        CodeLine("opencode serve --hostname 0.0.0.0 --port 4096")
        Spacer(Modifier.height(10.dp))
        Text(
            text = "密码由环境变量 OPENCODE_PASSWORD 指定；不设置时服务端会自动生成并在启动日志中打印。手机与电脑处于同一局域网时，填入电脑的局域网 IP。也可以用 opencode pair 生成的配对链接或二维码内容直接粘贴到地址栏：链接一次性换取 30 天会话凭证。",
            style = MaterialTheme.typography.bodySmall,
            color = OpenCodeColors.TextSecondary,
        )
    }
}

@Composable
private fun CodeLine(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B0B0B))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = OpenCodeColors.Accent.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun RecentRow(server: Server, onSelect: () -> Unit, onForget: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OpenCodeColors.SurfaceRaised)
            .clickable(onClick = onSelect)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenCodeColors.TextPrimary,
            )
            if (server.password.isNotEmpty()) {
                Text(
                    text = "已保存密码",
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenCodeColors.TextSecondary,
                )
            }
        }
        TextButton(onClick = onForget) {
            Text("移除", color = OpenCodeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}
