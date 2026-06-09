package com.example.texteditor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// Data classes for managing recent files and languages
data class RecentFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val language: ProgrammingLanguage,
    val content: String = ""
)

enum class ProgrammingLanguage(val displayName: String, val extension: String) {
    KOTLIN("Kotlin", "kt"),
    JAVA("Java", "java"),
    CSHARP("C#", "cs"),
    PYTHON("Python", "py"),
    JAVASCRIPT("JavaScript", "js"),
    CPP("C++", "cpp"),
    C("C", "c"),
    GO("Go", "go"),
    RUST("Rust", "rs"),
    SWIFT("Swift", "swift")
}

enum class ThemeMode {
    LIGHT, DARK
}

// Syntax highlighting configuration
data class SyntaxRule(
    val keywords: List<String>,
    val commentSingle: String = "//",
    val commentMultiStart: String = "/*",
    val commentMultiEnd: String = "*/",
    val stringDelimiters: List<String> = listOf("\"", "'")
)

object SyntaxHighlighter {

    private var syntaxRules = mutableMapOf<ProgrammingLanguage, SyntaxRule>()
    private var isInitialized = false

    /** -----------------------------
     *  Initialize: Load XML rules only if context is provided
     *  ----------------------------- */
    fun initialize(context: Context? = null) {
        if (!isInitialized) {
            if (context != null) {
                // Load rules from XML if context is provided
                syntaxRules.putAll(loadSyntaxRulesFromXml(context))
            } else {
                // If no context, create an empty map (no rules)
                syntaxRules.clear()
            }
            isInitialized = true
        }
    }

    /** -----------------------------
     *  Load XML Rules
     *  ----------------------------- */
    private fun loadSyntaxRulesFromXml(context: Context): Map<ProgrammingLanguage, SyntaxRule> {
        val rulesMap = mutableMapOf<ProgrammingLanguage, SyntaxRule>()
        try {
            val parser = context.resources.getXml(R.xml.syntax_rules)
            var eventType = parser.eventType

            var currentLanguage: ProgrammingLanguage? = null
            var currentKeywords = mutableListOf<String>()
            var currentCommentSingle = "//"
            var currentCommentMultiStart = "/*"
            var currentCommentMultiEnd = "*/"
            var currentStringDelimiters = mutableListOf<String>("\"", "'")

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when(eventType) {
                    XmlPullParser.START_TAG -> {
                        when(parser.name) {
                            "language" -> {
                                val langName = parser.getAttributeValue(null, "name")
                                currentLanguage = ProgrammingLanguage.values().find {
                                    it.name.equals(langName, ignoreCase = true) ||
                                            it.displayName.equals(langName, ignoreCase = true)
                                }
                                currentKeywords.clear()
                                // Reset to defaults
                                currentCommentSingle = "//"
                                currentCommentMultiStart = "/*"
                                currentCommentMultiEnd = "*/"
                                currentStringDelimiters = mutableListOf("\"", "'")
                            }
                            "comment" -> {
                                currentCommentSingle = parser.getAttributeValue(null, "single") ?: currentCommentSingle
                                currentCommentMultiStart = parser.getAttributeValue(null, "multiStart") ?: currentCommentMultiStart
                                currentCommentMultiEnd = parser.getAttributeValue(null, "multiEnd") ?: currentCommentMultiEnd
                            }
                            "delimiter" -> {
                                parser.getAttributeValue(null, "value")?.let {
                                    if (it.isNotEmpty()) {
                                        currentStringDelimiters.add(it)
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            // Check if we're inside a keywords element
                            val elementName = try {
                                // Get the current element name (this is a simplified approach)
                                "keywords" // You may need to track the current element more precisely
                            } catch (e: Exception) {
                                ""
                            }

                            if (elementName == "keywords") {
                                currentKeywords.addAll(text.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "language" && currentLanguage != null) {
                            rulesMap[currentLanguage!!] = SyntaxRule(
                                keywords = currentKeywords.toList(),
                                commentSingle = currentCommentSingle,
                                commentMultiStart = currentCommentMultiStart,
                                commentMultiEnd = currentCommentMultiEnd,
                                stringDelimiters = currentStringDelimiters.toList()
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
            Log.d("SyntaxHighlighter", "Loaded ${rulesMap.size} language rules")
        } catch(e: Exception) {
            Log.e("SyntaxHighlighter", "Error loading XML syntax rules: ${e.message}")
        }
        return rulesMap
    }

    /** -----------------------------
     *  Color Palette
     *  ----------------------------- */
    data class SyntaxHighlightColors(
        val keyword: Color,
        val comment: Color,
        val string: Color,
        val number: Color = Color.Gray,
        val function: Color = Color.Cyan,
        val type: Color = Color.Magenta,
        val default: Color = Color.Unspecified
    )

    private val LightColors = SyntaxHighlightColors(
        keyword = Color(0xFF0000FF),
        comment = Color(0xFF008000),
        string = Color(0xFFA31515)
    )
    private val DarkColors = SyntaxHighlightColors(
        keyword = Color(0xFF569CD6),
        comment = Color(0xFF6A9955),
        string = Color(0xFFCE9178)
    )

    /** -----------------------------
     *  Highlight code
     *  ----------------------------- */
    fun highlightCode(code: String, language: ProgrammingLanguage, isDarkMode: Boolean): AnnotatedString {
        // Debug logging
        Log.d("SyntaxHighlighter", "Highlighting for $language, rules available: ${syntaxRules.keys}")

        // If no rules are loaded, return plain text with proper color
        if (syntaxRules.isEmpty() || !syntaxRules.containsKey(language)) {
            Log.w("SyntaxHighlighter", "No syntax rules loaded for $language")
            return buildAnnotatedString {
                withStyle(SpanStyle(color = if (isDarkMode) Color.White else Color.Black)) {
                    append(code)
                }
            }
        }

        val rule = syntaxRules[language]!!
        val colors = if(isDarkMode) DarkColors else LightColors

        Log.d("SyntaxHighlighter", "Using rule for $language: keywords=${rule.keywords.size}, commentSingle=${rule.commentSingle}")

        return buildAnnotatedString {
            // Start with default style
            withStyle(SpanStyle(color = if (isDarkMode) Color.White else Color.Black)) {
                append(code)
            }

            // Apply highlighting only if we have rules
            if (rule.keywords.isNotEmpty()) {
                // keywords
                rule.keywords.forEach { keyword ->
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b")
                    regex.findAll(code).forEach { match ->
                        addStyle(
                            SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold),
                            match.range.first,
                            match.range.last + 1
                        )
                    }
                }
            }

            // single-line comments
            if(rule.commentSingle.isNotEmpty()) {
                val commentRegex = Regex("${Regex.escape(rule.commentSingle)}.*$", RegexOption.MULTILINE)
                commentRegex.findAll(code).forEach { match ->
                    addStyle(
                        SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic),
                        match.range.first,
                        match.range.last + 1
                    )
                }
            }

            // multi-line comments
            if(rule.commentMultiStart.isNotEmpty() && rule.commentMultiEnd.isNotEmpty()) {
                val multiCommentRegex = Regex(
                    "${Regex.escape(rule.commentMultiStart)}.*?${Regex.escape(rule.commentMultiEnd)}",
                    RegexOption.DOT_MATCHES_ALL
                )
                multiCommentRegex.findAll(code).forEach { match ->
                    addStyle(
                        SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic),
                        match.range.first,
                        match.range.last + 1
                    )
                }
            }

            // strings
            rule.stringDelimiters.forEach { delimiter ->
                if (delimiter.isNotEmpty()) {
                    val stringRegex = Regex("${Regex.escape(delimiter)}[^${Regex.escape(delimiter)}]*${Regex.escape(delimiter)}")
                    stringRegex.findAll(code).forEach { match ->
                        addStyle(
                            SpanStyle(color = colors.string),
                            match.range.first,
                            match.range.last + 1
                        )
                    }
                }
            }
        }
    }

    /** -----------------------------
     *  Check if a language is supported
     *  ----------------------------- */
    fun isLanguageSupported(language: ProgrammingLanguage): Boolean {
        return syntaxRules.containsKey(language)
    }

    /** -----------------------------
     *  Get supported languages
     *  ----------------------------- */
    fun getSupportedLanguages(): List<ProgrammingLanguage> {
        return syntaxRules.keys.toList()
    }
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Open File launcher
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val content = readFileFromUri(uri)
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "untitled.txt"
            openFileContent?.invoke(content, fileName)
        }
    }

    private var openFileContent: ((String, String) -> Unit)? = null

    fun openFile(onContentRead: (String, String) -> Unit) {
        openFileContent = onContentRead
        openFileLauncher.launch(arrayOf("text/*"))
    }

    private fun readFileFromUri(uri: android.net.Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() ?: "" }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
            }
            ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize syntax highlighting
        SyntaxHighlighter.initialize(this)

        setContent {
            TextopiaTheme {
                TextopiaApp(
                    onSaveFile = { content, fileName ->
                        saveFileToDocuments(content, fileName)
                    },
                    onRequestPermission = {
                        requestStoragePermission()
                    },
                    onOpenFile = { callback ->
                        openFile(callback)
                    }
                )
            }
        }
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+ doesn't need WRITE_EXTERNAL_STORAGE for Documents folder
            }
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun saveFileToDocuments(content: String, fileName: String): Boolean {
        return try {
            // Save to Documents folder instead of Downloads
            val documentsDir = File(Environment.getExternalStorageDirectory(), "Documents")
            if (!documentsDir.exists()) {
                documentsDir.mkdirs()
            }

            val file = File(documentsDir, fileName)

            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray())
            }

            runOnUiThread {
                Toast.makeText(this, "File saved to Documents: $fileName", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}

@Composable
fun TextopiaTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    val colorScheme = if (themeMode == ThemeMode.DARK) {
        darkColorScheme(
            primary = Color(0xFF6366F1),
            surface = Color(0xFF1F2937),
            background = Color(0xFF111827),
            onSurface = Color.White,
            onBackground = Color.White
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6366F1),
            surface = Color.White,
            background = Color(0xFFF8FAFC),
            onSurface = Color.Black,
            onBackground = Color.Black
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

// Boilerplate code templates
object CodeTemplates {
    fun getTemplate(language: ProgrammingLanguage): String {
        return when (language) {
            ProgrammingLanguage.KOTLIN -> """fun main() {
    println("Hello, Kotlin!")
    
    // Your Kotlin code here
}"""

            ProgrammingLanguage.JAVA -> """public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Java!");
        
        // Your Java code here
    }
}"""

            ProgrammingLanguage.CSHARP -> """using System;

class Program {
    static void Main() {
        Console.WriteLine("Hello, C#!");
        
        // Your C# code here
    }
}"""

            ProgrammingLanguage.PYTHON -> """# Python program
print("Hello, Python!")

# Your Python code here"""

            ProgrammingLanguage.JAVASCRIPT -> """// JavaScript program
console.log("Hello, JavaScript!");

// Your JavaScript code here"""

            ProgrammingLanguage.CPP -> """#include <iostream>
using namespace std;

int main() {
    cout << "Hello, C++!" << endl;
    
    // Your C++ code here
    return 0;
}"""

            ProgrammingLanguage.C -> """#include <stdio.h>

int main() {
    printf("Hello, C!\n");
    
    // Your C code here
    return 0;
}"""

            ProgrammingLanguage.GO -> """package main

import "fmt"

func main() {
    fmt.Println("Hello, Go!")
    
    // Your Go code here
}"""

            ProgrammingLanguage.RUST -> """fn main() {
    println!("Hello, Rust!");
    
    // Your Rust code here
}"""

            ProgrammingLanguage.SWIFT -> """import Foundation

print("Hello, Swift!")

// Your Swift code here"""
        }
    }
}

// PC Watcher based compilation system
object CodeCompiler {
    fun performCompile(
        context: Context,
        fileName: String,
        codeFieldValue: TextFieldValue,
        onResult: (String) -> Unit
    ) {
        try {
            val codedDir = File(context.getExternalFilesDir(null), "coded")
            if (!codedDir.exists()) {
                codedDir.mkdirs()
            }

            val sourceFile = File(codedDir, fileName)
            sourceFile.writeText(codeFieldValue.text)

            val runFile = File(codedDir, "run.txt")
            val writeSuccess = try {
                runFile.writeText(fileName)
                true
            } catch (e: Exception) {
                false
            }

            val result = when {
                codeFieldValue.text.trim().isEmpty() -> "❌ Error: No code to compile"
                !writeSuccess -> "❌ Error: Failed to send the compilation request!"
                codeFieldValue.text.contains("fun main") -> {
                    "✅ Compilation request sent!\nPC watcher will pull and compile automatically."
                }
                codeFieldValue.text.contains("class") && codeFieldValue.text.contains("{") -> {
                    "✅ Class compilation requested!\nPC watcher will pull and compile automatically"
                }
                codeFieldValue.text.contains("println") || codeFieldValue.text.contains("print") -> {
                    "✅ Code compilation requested!\nPC watcher will pull and compile automatically"
                }
                else -> {
                    "⚠ Warning: Code may have syntax issues\nAnyway, PC watcher will pull and attempt compilation."
                }
            }

            // Clean up previous output file
            val outputFile = File(codedDir, fileName.substringBeforeLast('.') + ".txt")
            if (outputFile.exists()) {
                outputFile.delete()
            }

            onResult(result)
            Toast.makeText(context, "PC watcher will handle request. Please wait!", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            onResult("💥 Error during compilation: ${e.message}")
            Toast.makeText(context, "Compilation error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Function to check for compilation output
    fun checkCompilationOutput(context: Context, fileName: String): String? {
        return try {
            val codedDir = File(context.getExternalFilesDir(null), "coded")
            val outputFile = File(codedDir, fileName.substringBeforeLast('.') + ".txt")

            if (outputFile.exists()) {
                val output = outputFile.readText()
                outputFile.delete() // Clean up after reading
                output
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

// Simple and reliable Undo/Redo manager
class UndoRedoManager {
    private val undoStack = mutableListOf<String>()
    private val redoStack = mutableListOf<String>()
    private var lastText = ""
    private var lastSaveTime = 0L

    fun saveState(currentText: String) {
        val currentTime = System.currentTimeMillis()

        if (currentText != lastText && shouldSaveState(lastText, currentText, currentTime)) {
            if (lastText.isNotEmpty() && (undoStack.isEmpty() || undoStack.last() != lastText)) {
                undoStack.add(lastText)
                if (undoStack.size > 50) {
                    undoStack.removeAt(0)
                }
            }

            redoStack.clear()
            lastText = currentText
            lastSaveTime = currentTime
        }
    }

    private fun shouldSaveState(oldText: String, newText: String, currentTime: Long): Boolean {
        if (oldText.isEmpty()) return true
        val timeDiff = currentTime - lastSaveTime
        val lengthDiff = kotlin.math.abs(newText.length - oldText.length)
        return timeDiff > 1000 || lengthDiff > 5 || hasWordBoundaryChange(oldText, newText)
    }

    private fun hasWordBoundaryChange(oldText: String, newText: String): Boolean {
        val wordChars = setOf(' ', '\n', '\t', '.', ',', ';', '!', '?')
        val oldLastChar = oldText.lastOrNull()
        val newLastChar = newText.lastOrNull()
        return (oldLastChar in wordChars) != (newLastChar in wordChars)
    }

    fun undo(currentText: String): String? {
        return if (undoStack.isNotEmpty()) {
            redoStack.add(currentText)
            val previousText = undoStack.removeAt(undoStack.size - 1)
            lastText = previousText
            previousText
        } else null
    }

    fun redo(): String? {
        return if (redoStack.isNotEmpty()) {
            undoStack.add(lastText)
            val nextText = redoStack.removeAt(redoStack.size - 1)
            lastText = nextText
            nextText
        } else null
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastText = ""
        lastSaveTime = 0L
    }

    fun initialize(text: String) {
        clear()
        lastText = text
        lastSaveTime = System.currentTimeMillis()
    }
}

// Simple persistent storage for recent files
object RecentFilesManager {
    private var recentFilesList = mutableListOf<RecentFile>()

    fun addRecentFile(file: RecentFile) {
        // Remove existing entry with same name if exists
        recentFilesList.removeAll { it.name == file.name }
        // Add to beginning
        recentFilesList.add(0, file)
        // Keep only last 10 files
        if (recentFilesList.size > 10) {
            recentFilesList = recentFilesList.take(10).toMutableList()
        }
    }

    fun getRecentFiles(): List<RecentFile> {
        return recentFilesList.toList()
    }

    fun clearRecentFiles() {
        recentFilesList.clear()
    }
}

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextopiaApp(
    onSaveFile: (String, String) -> Boolean = { _, _ -> false },
    onRequestPermission: () -> Unit = {},
    onOpenFile: ((String, String) -> Unit) -> Unit = {}
) {
    // Coroutine scope for handling async operations
    val coroutineScope = rememberCoroutineScope()
    // Theme and language state
    var currentTheme by remember { mutableStateOf(ThemeMode.LIGHT) }
    var currentLanguage by remember { mutableStateOf(ProgrammingLanguage.KOTLIN) }

    // Code editor state
    var codeTextFieldValue by remember { mutableStateOf(TextFieldValue(CodeTemplates.getTemplate(currentLanguage))) }
    var output by remember { mutableStateOf("") }
    var isCompiling by remember { mutableStateOf(false) }

    // File state
    var fileName by remember { mutableStateOf("untitled.${currentLanguage.extension}") }
    var lastSavedText by remember { mutableStateOf(codeTextFieldValue.text) }
    val isDirty by derivedStateOf { codeTextFieldValue.text != lastSavedText }

    // Recent files
    var recentFiles by remember { mutableStateOf(RecentFilesManager.getRecentFiles()) }

    // Undo/Redo manager
    val undoRedoManager = remember { UndoRedoManager() }
    var isUndoRedoInProgress by remember { mutableStateOf(false) }

    // Dialog states
    var showSaveDialog by remember { mutableStateOf(false) }
    var showNewFileConfirm by remember { mutableStateOf(false) }
    var showDropdownMenu by remember { mutableStateOf(false) }
    var showRecentFiles by remember { mutableStateOf(false) }
    var showLanguageSelector by remember { mutableStateOf(false) }

    // Text statistics
    val wordCount by derivedStateOf {
        if (codeTextFieldValue.text.isBlank()) 0
        else codeTextFieldValue.text.trim().split(Regex("\\s+")).size
    }

    val characterCount by derivedStateOf { codeTextFieldValue.text.length }
    val lineCount by derivedStateOf { codeTextFieldValue.text.lines().size }

    // Initialize undo manager when language changes
    LaunchedEffect(currentLanguage) {
        val template = CodeTemplates.getTemplate(currentLanguage)
        codeTextFieldValue = TextFieldValue(template)
        fileName = "untitled.${currentLanguage.extension}"
        undoRedoManager.initialize(template)
        lastSavedText = template
    }

    // Save state to undo manager when text changes
    LaunchedEffect(codeTextFieldValue.text) {
        if (!isUndoRedoInProgress) {
            delay(200)
            if (!isUndoRedoInProgress) {
                undoRedoManager.saveState(codeTextFieldValue.text)
            }
        }
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Background task to check for compilation output
    LaunchedEffect(isCompiling) {
        if (isCompiling) {
            // Poll for output file every 2 seconds
            while (isCompiling) {
                delay(2000)
                val compilationOutput = CodeCompiler.checkCompilationOutput(context, fileName)
                if (compilationOutput != null) {
                    output = compilationOutput
                    isCompiling = false
                    break
                }
            }
        }
    }

    fun createNewFile() {
        val template = CodeTemplates.getTemplate(currentLanguage)
        codeTextFieldValue = TextFieldValue(template)
        output = ""
        fileName = "untitled.${currentLanguage.extension}"
        lastSavedText = template
        undoRedoManager.initialize(template)
        Toast.makeText(context, "New ${currentLanguage.displayName} file created", Toast.LENGTH_SHORT).show()
    }

    fun requestNewFile() {
        if (isDirty) {
            showNewFileConfirm = true
        } else {
            createNewFile()
        }
    }

    fun performSave(): Boolean {
        var finalFileName = fileName
        if (finalFileName.isBlank()) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            finalFileName = "textopia_$ts.${currentLanguage.extension}"
        }

        if (!finalFileName.endsWith(".${currentLanguage.extension}", ignoreCase = true)) {
            finalFileName = "$finalFileName.${currentLanguage.extension}"
        }

        onRequestPermission()
        val success = onSaveFile(codeTextFieldValue.text, finalFileName)
        if (success) {
            lastSavedText = codeTextFieldValue.text
            fileName = finalFileName // Update the fileName state
            // Add to recent files
            val recentFile = RecentFile(
                name = finalFileName,
                path = "/Documents/$finalFileName",
                lastModified = System.currentTimeMillis(),
                language = currentLanguage,
                content = codeTextFieldValue.text
            )
            RecentFilesManager.addRecentFile(recentFile)
            recentFiles = RecentFilesManager.getRecentFiles()
        }
        return success
    }

    fun openRecentFile(recentFile: RecentFile) {
        codeTextFieldValue = TextFieldValue(recentFile.content)
        fileName = recentFile.name
        currentLanguage = recentFile.language
        undoRedoManager.initialize(recentFile.content)
        lastSavedText = recentFile.content
        Toast.makeText(context, "Opened ${recentFile.name}", Toast.LENGTH_SHORT).show()
    }

    // Theme wrapper
    TextopiaTheme(themeMode = currentTheme) {
        val backgroundColor = if (currentTheme == ThemeMode.DARK) Color(0xFF111827) else Color(0xFF6366F1)
        val textColor = if (currentTheme == ThemeMode.DARK) Color.White else Color.White
        val cardColor = if (currentTheme == ThemeMode.DARK) Color(0xFF1F2937) else Color.White

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📝", fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = "Textopia",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF6B35)
                )
                Text("🚀", fontSize = 24.sp, modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currentLanguage.displayName} - ${if (isDirty) "* $fileName" else fileName}",
                    color = textColor,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    text = "New",
                    icon = Icons.Default.Add,
                    backgroundColor = Color(0xFF4CAF50)
                ) { requestNewFile() }

                ActionButton(
                    text = "Open",
                    icon = Icons.Default.Folder,
                    backgroundColor = Color(0xFF2196F3)
                ) {
                    onOpenFile { content, openedFileName ->
                        codeTextFieldValue = TextFieldValue(content)
                        fileName = openedFileName
                        undoRedoManager.clear()
                        undoRedoManager.initialize(content)
                        lastSavedText = content

                        // Add to recent files
                        val recentFile = RecentFile(
                            name = openedFileName,
                            path = "/Documents/$openedFileName",
                            lastModified = System.currentTimeMillis(),
                            language = currentLanguage,
                            content = content
                        )
                        RecentFilesManager.addRecentFile(recentFile)
                        recentFiles = RecentFilesManager.getRecentFiles()

                        Toast.makeText(context, "File opened: $openedFileName", Toast.LENGTH_SHORT).show()
                    }
                }

                ActionButton(
                    text = "Recent",
                    icon = Icons.Default.History,
                    backgroundColor = Color(0xFF9C27B0)
                ) { showRecentFiles = true }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Code Editor Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentTheme == ThemeMode.DARK) Color(0xFF1F2937) else Color(0xFFFFFFFF)
                ),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Toolbar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Undo
                        IconButton(
                            onClick = {
                                isUndoRedoInProgress = true
                                val undoText = undoRedoManager.undo(codeTextFieldValue.text)
                                if (undoText != null) {
                                    codeTextFieldValue = TextFieldValue(undoText, TextRange(undoText.length))
                                    Toast.makeText(context, "Undo", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Nothing to undo", Toast.LENGTH_SHORT).show()
                                }
                                isUndoRedoInProgress = false
                            },
                            enabled = undoRedoManager.canUndo()
                        ) {
                            Icon(
                                Icons.Default.Undo,
                                contentDescription = "Undo",
                                tint = if (undoRedoManager.canUndo()) MaterialTheme.colorScheme.onSurface else Color.Gray
                            )
                        }

                        // Redo
                        IconButton(
                            onClick = {
                                isUndoRedoInProgress = true
                                val redoText = undoRedoManager.redo()
                                if (redoText != null) {
                                    codeTextFieldValue = TextFieldValue(redoText, TextRange(redoText.length))
                                    Toast.makeText(context, "Redo", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Nothing to redo", Toast.LENGTH_SHORT).show()
                                }
                                isUndoRedoInProgress = false
                            },
                            enabled = undoRedoManager.canRedo()
                        ) {
                            Icon(
                                Icons.Default.Redo,
                                contentDescription = "Redo",
                                tint = if (undoRedoManager.canRedo()) MaterialTheme.colorScheme.onSurface else Color.Gray
                            )
                        }

                        // Cut, Copy, Paste
                        IconButton(onClick = {
                            val sel = codeTextFieldValue.selection
                            val hasSelection = sel.start != sel.end
                            val selected = if (hasSelection) codeTextFieldValue.text.substring(sel.start, sel.end) else ""
                            val toCut = if (hasSelection) selected else codeTextFieldValue.text
                            if (toCut.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(toCut))
                                codeTextFieldValue = if (hasSelection) {
                                    val newText = codeTextFieldValue.text.removeRange(sel.start, sel.end)
                                    codeTextFieldValue.copy(text = newText, selection = TextRange(sel.start, sel.start))
                                } else {
                                    TextFieldValue("")
                                }
                                Toast.makeText(context, "Cut to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        IconButton(onClick = {
                            val sel = codeTextFieldValue.selection
                            val selected = if (sel.start != sel.end) codeTextFieldValue.text.substring(sel.start, sel.end) else ""
                            val textToCopy = if (selected.isNotEmpty()) selected else codeTextFieldValue.text
                            if (textToCopy.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(textToCopy))
                                Toast.makeText(context, if (selected.isNotEmpty()) "Selected text copied" else "All code copied", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        IconButton(onClick = {
                            val clip = clipboardManager.getText()
                            val pasteText = clip?.text ?: ""
                            if (pasteText.isNotEmpty()) {
                                val sel = codeTextFieldValue.selection
                                val newText = buildString {
                                    append(codeTextFieldValue.text.substring(0, sel.start))
                                    append(pasteText)
                                    append(codeTextFieldValue.text.substring(sel.end))
                                }
                                val newCursor = sel.start + pasteText.length
                                codeTextFieldValue = codeTextFieldValue.copy(text = newText, selection = TextRange(newCursor, newCursor))
                                Toast.makeText(context, "Pasted", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        // Save
                        IconButton(onClick = {
                            if (fileName.isBlank()) {
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                fileName = "textopia_$ts.${currentLanguage.extension}"
                            }
                            showSaveDialog = true
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Enhanced Menu Button with Dropdown
                        Box {
                            IconButton(
                                onClick = { showDropdownMenu = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showDropdownMenu,
                                onDismissRequest = { showDropdownMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Language: ${currentLanguage.displayName}") },
                                    leadingIcon = { Icon(Icons.Default.Code, contentDescription = null) },
                                    onClick = {
                                        showLanguageSelector = true
                                        showDropdownMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Word Count: $wordCount") },
                                    leadingIcon = { Icon(Icons.Default.Article, contentDescription = null) },
                                    onClick = {
                                        Toast.makeText(context, "Word count: $wordCount", Toast.LENGTH_SHORT).show()
                                        showDropdownMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Character Count: $characterCount") },
                                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                                    onClick = {
                                        Toast.makeText(context, "Character count: $characterCount", Toast.LENGTH_SHORT).show()
                                        showDropdownMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Line Count: $lineCount") },
                                    leadingIcon = { Icon(Icons.Default.Subject, contentDescription = null) },
                                    onClick = {
                                        Toast.makeText(context, "Line count: $lineCount", Toast.LENGTH_SHORT).show()
                                        showDropdownMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("${if (currentTheme == ThemeMode.DARK) "Light" else "Dark"} Mode") },
                                    leadingIcon = {
                                        Icon(
                                            if (currentTheme == ThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        currentTheme = if (currentTheme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                                        showDropdownMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("About Textopia") },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    onClick = {
                                        Toast.makeText(context, "Textopia v1.0 - Advanced Code Editor", Toast.LENGTH_LONG).show()
                                        showDropdownMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Clear All") },
                                    leadingIcon = { Icon(Icons.Default.Clear, contentDescription = null) },
                                    onClick = {
                                        codeTextFieldValue = TextFieldValue("")
                                        output = ""
                                        undoRedoManager.clear()
                                        showDropdownMenu = false
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color.LightGray, thickness = 1.dp)

                    // Code Editor with Syntax Highlighting
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(8.dp)
                                .background(
                                    if (currentTheme == ThemeMode.DARK) Color(0xFF1E1E1E) else Color.White,
                                    RoundedCornerShape(4.dp)
                                )
                        ) {
                            val scrollState = rememberScrollState()

                            // Regular BasicTextField for input
                            BasicTextField(
                                value = codeTextFieldValue,
                                onValueChange = { codeTextFieldValue = it },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .verticalScroll(scrollState),
                                textStyle = TextStyle(
                                    color = Color.Transparent, // invisible real text
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp // 👈 add same lineHeight
                                ),
                                cursorBrush = SolidColor(
                                    if (currentTheme == ThemeMode.DARK) Color.White else Color.Black
                                ),
                                decorationBox = { innerTextField ->
                                    Box {
                                        // Highlighted layer (same style as the real text field)
                                        Text(
                                            text = SyntaxHighlighter.highlightCode(
                                                code = codeTextFieldValue.text,
                                                language = currentLanguage,
                                                isDarkMode = currentTheme == ThemeMode.DARK
                                            ),
                                            modifier = Modifier.fillMaxSize(),
                                            style = TextStyle( // 👈 match exactly
                                                color = if (currentTheme == ThemeMode.DARK) Color.White else Color.Black,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp
                                            )
                                        )
                                        // Transparent editable layer
                                        innerTextField()
                                    }
                                }
                            )

                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Compile Button - Updated to use PC Watcher system
            Button(
                onClick = {
                    if (!isCompiling) {
                        isCompiling = true
                        output = "Sending compilation request to PC watcher..."

                        // Use the PC Watcher compilation system
                        CodeCompiler.performCompile(
                            context = context,
                            fileName = fileName,
                            codeFieldValue = codeTextFieldValue
                        ) { result ->
                            output = result
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                shape = RoundedCornerShape(24.dp),
                enabled = !isCompiling
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isCompiling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Compile", tint = Color.White, modifier = Modifier.size(20.dp).padding(end = 4.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isCompiling) "Waiting for PC watcher..." else "Compile & Run",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Output",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth().height(150.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (currentTheme == ThemeMode.DARK) Color(0xFF0D1117) else Color(0xFF2D2D2D)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        text = output.ifEmpty { "Output will appear here..." },
                        color = if (output.isEmpty()) Color.Gray else Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Save Dialog
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save File") },
                text = {
                    Column {
                        Text("Enter filename:")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            placeholder = { Text("filename.${currentLanguage.extension}") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (fileName.isNotBlank()) {
                            val ok = performSave()
                            if (ok) showSaveDialog = false
                        } else {
                            Toast.makeText(context, "Please enter a filename", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }

        // New File Confirm Dialog
        if (showNewFileConfirm) {
            AlertDialog(
                onDismissRequest = { showNewFileConfirm = false },
                title = { Text("Discard changes?") },
                text = { Text("You have unsaved changes in \"$fileName\". What would you like to do?") },
                confirmButton = {
                    TextButton(onClick = {
                        val ok = performSave()
                        if (ok) {
                            createNewFile()
                            showNewFileConfirm = false
                        }
                    }) { Text("Save & New") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            createNewFile()
                            showNewFileConfirm = false
                        }) { Text("Discard") }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { showNewFileConfirm = false }) { Text("Cancel") }
                    }
                }
            )
        }

        // Recent Files Dialog
        if (showRecentFiles) {
            AlertDialog(
                onDismissRequest = { showRecentFiles = false },
                title = { Text("Recent Files") },
                text = {
                    if (recentFiles.isEmpty()) {
                        Text("No recent files found. Create or save some files to see them here.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(300.dp)
                        ) {
                            items(recentFiles) { file ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    onClick = {
                                        openRecentFile(file)
                                        showRecentFiles = false
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Text(
                                            text = file.name,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${file.language.displayName} • ${SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified))}",
                                            fontSize = 12.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showRecentFiles = false }) { Text("Close") }
                }
            )
        }

        // Language Selector Dialog
        if (showLanguageSelector) {
            AlertDialog(
                onDismissRequest = { showLanguageSelector = false },
                title = { Text("Select Programming Language") },
                text = {
                    LazyColumn(
                        modifier = Modifier.height(400.dp)
                    ) {
                        items(ProgrammingLanguage.values().toList()) { language ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                onClick = {
                                    if (isDirty) {
                                        Toast.makeText(context, "Save your work before changing language", Toast.LENGTH_LONG).show()
                                    } else {
                                        currentLanguage = language
                                        showLanguageSelector = false
                                    }
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (language == currentLanguage) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = language.displayName,
                                        fontWeight = if (language == currentLanguage) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = ".${language.extension}",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    if (language == currentLanguage) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageSelector = false }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.height(40.dp).width(110.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = text, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}