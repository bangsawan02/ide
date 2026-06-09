package com.example

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter

data class AppInfo(val name: String, val packageName: String)

class MainActivity : ComponentActivity() {
    init {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                FridaManagerApp()
            }
        }
    }
}

val SCRIPT_TEMPLATES = mapOf(
    "Default Hook" to """
Java.perform(() => {
  console.log('[*] Successfully injected into ' + Process.id);
  // Add your hooks here...
});
""".trimIndent(),
    "Root Bypass (Mock)" to """
Java.perform(() => {
    console.log('[*] Loading Root Bypass...');
    const Runtime = Java.use('java.lang.Runtime');
    Runtime.exec.overload('java.lang.String').implementation = function(cmd) {
        if (cmd.indexOf('su') !== -1) {
            console.log('[*] Bypassing root check: ' + cmd);
            return this.exec('nosuchcommand_xyz');
        }
        return this.exec(cmd);
    };
});
""".trimIndent(),
    "SSL Pinning Bypass (Basic)" to """
Java.perform(() => {
    console.log('[*] Loading SSL Pinning Bypass...');
    try {
        const TrustManagerImpl = Java.use('com.android.org.conscrypt.TrustManagerImpl');
        TrustManagerImpl.verifyChain.implementation = function(untrustedChain, trustAnchorChain, host, clientAuth, ocspData, tlsSctData) {
            console.log('[*] Bypassed SSL Pinning for host: ' + host);
            return untrustedChain;
        };
    } catch(e) { console.log('[-] Conscrypt not found'); }
});
""".trimIndent()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FridaManagerApp() {
    var isRootGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRootGranted = Shell.getShell().isRoot
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ZygiskFrida IDE",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (isRootGranted) "Root Access Granted" else "Waiting for Root...",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isRootGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (!isRootGranted) {
                Card(
                    modifier = Modifier.padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "This app requires Root access to execute chroot-distro environments and inject Frida scripts. Please grant root.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                FridaWorkspace()
            }
        }
    }
}

@Composable
fun FridaWorkspace() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Target", "Script", "Console", "Logcat")

    // Global State
    var packageName by remember { mutableStateOf("") }
    var startupDelay by remember { mutableStateOf("0") }
    var containerName by remember { mutableStateOf("debian") }
    var fridaScript by remember { mutableStateOf(SCRIPT_TEMPLATES["Default Hook"]!!) }

    // Console State
    var interactiveOutWriter by remember { mutableStateOf<OutputStreamWriter?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var outputLog by remember { mutableStateOf("Ready to inject.\n") }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (selectedTab) {
                0 -> TargetTab(
                    packageName = packageName,
                    onPackageNameChange = { packageName = it },
                    startupDelay = startupDelay,
                    onStartupDelayChange = { startupDelay = it }
                )
                1 -> ScriptTab(
                    containerName = containerName,
                    onContainerNameChange = { containerName = it },
                    fridaScript = fridaScript,
                    onFridaScriptChange = { fridaScript = it }
                )
                2 -> ConsoleTab(
                    packageName = packageName,
                    containerName = containerName,
                    fridaScript = fridaScript,
                    isRunning = isRunning,
                    onIsRunningChange = { isRunning = it },
                    outputLog = outputLog,
                    onOutputLogChange = { outputLog = it },
                    outWriter = interactiveOutWriter,
                    onOutWriterChange = { interactiveOutWriter = it }
                )
                3 -> LogcatTab(packageName = packageName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetTab(
    packageName: String,
    onPackageNameChange: (String) -> Unit,
    startupDelay: String,
    onStartupDelayChange: (String) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    var expanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf(packageName) }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var saveStatus by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val res = Shell.cmd("ps -A -o NAME").exec()
            val runningNames = res.out.filter { it.contains(".") && !it.contains(":") }.distinct()
            val list = runningNames.map { pkg ->
                var appName = pkg
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    appName = info.loadLabel(pm).toString()
                } catch (e: Exception) {}
                AppInfo(appName, pkg)
            }.sortedBy { it.name.lowercase() }
            withContext(Dispatchers.Main) { apps = list }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("ZygiskFrida Config", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = {
                    searchText = it
                    onPackageNameChange(it)
                    expanded = true
                },
                label = { Text("Target Package Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            val filtered = apps.filter { it.name.contains(searchText, true) || it.packageName.contains(searchText, true) }.take(10)
            if (filtered.isNotEmpty() && expanded) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    filtered.forEach { app ->
                        DropdownMenuItem(
                            text = { 
                                Column {
                                    Text(app.name, fontWeight = FontWeight.Bold)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = {
                                searchText = app.packageName
                                onPackageNameChange(app.packageName)
                                expanded = false
                                focusManager.clearFocus()
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = startupDelay,
            onValueChange = onStartupDelayChange,
            label = { Text("Startup Delay (ms)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Button(
            onClick = {
                focusManager.clearFocus()
                coroutineScope.launch(Dispatchers.IO) {
                    val zygiskPath = "/data/local/tmp/re.zyg.fri"
                    val exampleConfig = "$zygiskPath/config.json.example"
                    val currentConfig = "$zygiskPath/config.json"

                    val cmds = listOf(
                        "mkdir -p $zygiskPath",
                        "touch $currentConfig",
                        "cp $exampleConfig $currentConfig",
                        "sed -i 's/com.example.package/$packageName/g' $currentConfig",
                        "am force-stop $packageName"
                    )

                    cmds.forEach { Shell.cmd(it).exec() }

                    withContext(Dispatchers.Main) {
                        saveStatus = "Updated config for $packageName and force-stopped app!"
                    }
                }
            },
            enabled = packageName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Update Target & Force-Stop")
        }

        if (saveStatus.isNotBlank()) {
            Text(saveStatus, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptTab(
    containerName: String,
    onContainerNameChange: (String) -> Unit,
    fridaScript: String,
    onFridaScriptChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        OutlinedTextField(
            value = containerName,
            onValueChange = onContainerNameChange,
            label = { Text("chroot-distro Name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = "Load Template",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(12.dp)
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                SCRIPT_TEMPLATES.keys.forEach { key ->
                    DropdownMenuItem(
                        text = { Text(key) },
                        onClick = {
                            onFridaScriptChange(SCRIPT_TEMPLATES[key]!!)
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = fridaScript,
            onValueChange = onFridaScriptChange,
            label = { Text("Frida Script (JS)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            shape = RoundedCornerShape(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        )
    }
}

@Composable
fun ConsoleTab(
    packageName: String,
    containerName: String,
    fridaScript: String,
    isRunning: Boolean,
    onIsRunningChange: (Boolean) -> Unit,
    outputLog: String,
    onOutputLogChange: (String) -> Unit,
    outWriter: OutputStreamWriter?,
    onOutWriterChange: (OutputStreamWriter?) -> Unit
) {
    var replInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (isRunning) {
                        coroutineScope.launch(Dispatchers.IO) {
                            outWriter?.apply { write("exit\n"); flush() }
                            Shell.cmd("killall frida").exec()
                        }
                    } else {
                        onIsRunningChange(true)
                        coroutineScope.launch(Dispatchers.IO) {
                            withContext(Dispatchers.Main) { onOutputLogChange("Initializing Frida session...\n") }

                            val safeScript = fridaScript.replace("'", "'\\''")
                            Shell.cmd("cat > /data/local/tmp/zygisk_payload.js << 'EOF'\n$fridaScript\nEOF").exec()

                            val process = Runtime.getRuntime().exec(arrayOf("su"))
                            val rawWriter = OutputStreamWriter(process.outputStream)
                            withContext(Dispatchers.Main) { onOutWriterChange(rawWriter) }

                            rawWriter.write("chroot-distro login $containerName\n")
                            rawWriter.write("frida -H 127.0.0.1 -N Gadget -l /data/local/tmp/zygisk_payload.js\n")
                            rawWriter.flush()

                            val reader = process.inputStream.bufferedReader()
                            val errReader = process.errorStream.bufferedReader()

                            launch {
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    withContext(Dispatchers.Main) {
                                        onOutputLogChange(outputLog + line + "\n")
                                    }
                                }
                            }

                            launch {
                                var errLine: String?
                                while (errReader.readLine().also { errLine = it } != null) {
                                    withContext(Dispatchers.Main) {
                                        onOutputLogChange(outputLog + "[ERR] " + errLine + "\n")
                                    }
                                }
                            }

                            process.waitFor()

                            withContext(Dispatchers.Main) {
                                onOutputLogChange(outputLog + "\n[Session Ended]\n")
                                onIsRunningChange(false)
                                onOutWriterChange(null)
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isRunning) "Stop Session" else "Start Session")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            Text(
                text = outputLog,
                color = Color.Green,
                style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            )
        }

        OutlinedTextField(
            value = replInput,
            onValueChange = { replInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("REPL Command (JavaScript)") },
            enabled = isRunning,
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (replInput.isNotBlank()) {
                            coroutineScope.launch(Dispatchers.IO) {
                                outWriter?.apply {
                                    write("$replInput\n")
                                    flush()
                                }
                                withContext(Dispatchers.Main) {
                                    onOutputLogChange(outputLog + "\n> $replInput\n")
                                    replInput = ""
                                }
                            }
                        }
                    },
                    enabled = isRunning && replInput.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (replInput.isNotBlank()) {
                        coroutineScope.launch(Dispatchers.IO) {
                            outWriter?.apply {
                                write("$replInput\n")
                                flush()
                            }
                            withContext(Dispatchers.Main) {
                                onOutputLogChange(outputLog + "\n> $replInput\n")
                                replInput = ""
                            }
                        }
                    }
                }
            )
        )
    }

    LaunchedEffect(outputLog) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
}

@Composable
fun LogcatTab(packageName: String) {
    var logcatOutput by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = {
                isFetching = true
                scope.launch(Dispatchers.IO) {
                    // Try to fetch PIDs for package
                    val pidRes = Shell.cmd("pidof $packageName").exec()
                    val pids = pidRes.out.joinToString("|").replace(" ", "|")

                    val cmd = if (pids.isNotBlank()) {
                        "logcat -d | grep -E '$pids' | tail -n 500"
                    } else {
                        "logcat -d | grep $packageName | tail -n 500"
                    }

                    val res = Shell.cmd(cmd).exec()
                    val out = res.out.joinToString("\n").ifBlank { "No logs found for $packageName." }

                    withContext(Dispatchers.Main) {
                        logcatOutput = out
                        isFetching = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isFetching) "Fetching Logcat..." else "Refresh Target Logcat")
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (logcatOutput.isBlank()) {
                Text(
                    "Click Refresh to fetch the latest logs.",
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Text(
                    text = logcatOutput,
                    color = Color.LightGray,
                    style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                )
            }
        }
    }

    LaunchedEffect(logcatOutput) {
        if (logcatOutput.isNotBlank()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
}

