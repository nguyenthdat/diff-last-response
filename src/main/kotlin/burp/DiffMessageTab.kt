package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.Selection
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor
import uniffi.diffy.DeltaKind
import uniffi.diffy.Decorations
import uniffi.diffy.LineBlock
import uniffi.diffy.InlineSpan
import uniffi.diffy.computeDecorations
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import org.fife.ui.rtextarea.Gutter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.IOException
import java.util.*
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.text.BadLocationException
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultHighlighter.DefaultHighlightPainter
import javax.swing.text.Highlighter

class DiffMessageTab(private val api: MontoyaApi) : ExtensionProvidedHttpResponseEditor {
    private val diffContainer = JPanel(BorderLayout())
    private val textEditor = RSyntaxTextArea(20, 60)
    private val scrollPane = RTextScrollPane(textEditor)

    // Diff highlight colors are theme-aware (set in configureEditorTheme)
    private var lineDeleteColor: Color = Color(0xDC, 0x35, 0x45) // light theme default
    private var lineInsertColor: Color = Color(0x28, 0xA7, 0x45) // light theme default
    private var inlineChangeColor: Color = Color(0x0D, 0x6E, 0xFD, 110) // translucent for inline spans
    private var modifiedPainter: Highlighter.HighlightPainter = DefaultHighlightPainter(inlineChangeColor)
    private var currentMessage: ByteArray? = null
    private var componentShown = false
    private val maxBytes = 750000
    private var currentRequestResponse: HttpRequestResponse? = null

    init {
        diffContainer.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent?) {
                if (componentShown) {
                    return
                }
                SwingUtilities.invokeLater {
                    diffContainer.removeAll()
                    textEditor.lineWrap = true
                    textEditor.isEditable = false
                    textEditor.antiAliasingEnabled = true
                    scrollPane.setAutoscrolls(true)
                    val caret = textEditor.caret as DefaultCaret
                    caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE)

                    configureEditorTheme()

                    diffContainer.add(scrollPane)
                }
                componentShown = true
            }
        })
    }

    private fun isDarkLaf(): Boolean {
        val lafName = UIManager.getLookAndFeel()?.name?.lowercase(Locale.getDefault()) ?: ""
        if (lafName.contains("darcula") || lafName.contains("dark")) return true
        val bg = UIManager.getColor("Panel.background") ?: return false
        val luminance = 0.2126 * bg.red + 0.7152 * bg.green + 0.0722 * bg.blue
        return luminance < 128
    }

    private fun configureEditorTheme() {
        val dark = isDarkLaf()

        // Baseline editor tweaks for readability
        textEditor.antiAliasingEnabled = true
        textEditor.isCodeFoldingEnabled = false
        textEditor.highlightCurrentLine = false
        textEditor.fadeCurrentLineHighlight = true
        textEditor.markOccurrences = false

        // Respect IDE font if available
        UIManager.getFont("TextArea.font")?.let { f ->
            textEditor.font = f.deriveFont(Font.PLAIN, f.size2D)
        }

        if (dark) {
            try {
                val theme = Theme.load(
                    javaClass.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")
                )
                theme.apply(textEditor)
            } catch (_: IOException) {}

            // Softer translucent accents for dark background
            lineDeleteColor = Color(239, 83, 80, 80)
            lineInsertColor = Color(76, 175, 80, 80)
            inlineChangeColor = Color(33, 150, 243, 110)
        } else {
            // Gentle overlays for light background
            lineDeleteColor = Color(220, 53, 69, 60)
            lineInsertColor = Color(40, 167, 69, 60)
            inlineChangeColor = Color(13, 110, 253, 90)
        }
        modifiedPainter = DefaultHighlightPainter(inlineChangeColor)

        // Gutter styling
        val gutter: Gutter = scrollPane.gutter
        if (dark) {
            gutter.background = Color(0x22, 0x22, 0x22)
            gutter.foreground = Color(0xBB, 0xBB, 0xBB)
            gutter.borderColor = Color(0x33, 0x33, 0x33)
            gutter.lineNumberColor = Color(0x99, 0x99, 0x99)
        } else {
            gutter.background = Color(0xF7, 0xF7, 0xF7)
            gutter.foreground = Color(0x44, 0x44, 0x44)
            gutter.borderColor = Color(0xDD, 0xDD, 0xDD)
            gutter.lineNumberColor = Color(0x77, 0x77, 0x77)
        }
        gutter.isFoldIndicatorEnabled = false
        gutter.lineNumbersEnabled = true
    }

    private fun addHighlight(
        startPos: Int,
        endPos: Int,
        highlighter: Highlighter,
        painter: Highlighter.HighlightPainter?
    ) {
        try {
            highlighter.addHighlight(startPos, endPos, painter)
        } catch (e: BadLocationException) {
            e.printStackTrace()
        }
    }

    override fun getResponse(): HttpResponse? {
        return currentRequestResponse?.response()
    }

    override fun setRequestResponse(requestResponse: HttpRequestResponse?) {
        if (requestResponse == null) {
            return
        }
        currentRequestResponse = requestResponse

        val response = requestResponse.response() ?: return
        val responseBytes = response.toByteArray().bytes

        if (responseBytes.isNotEmpty()) {
            val svc = requestResponse.request().httpService()
            val currentPort = svc.port()
            val currentHost = svc.host()
            val currentProtocol = if (svc.secure()) "https" else "http"

            if (lastMessage == null && lastProtocol == null && lastHost == null && lastPort == 0) {
                lastMessage = BurpExtender.lastMessageFromListener
                lastHost = BurpExtender.lastHostFromListener
                lastProtocol = BurpExtender.lastProtocolFromListener
                lastPort = BurpExtender.lastPortFromListener
            }

            if (!responseBytes.contentEquals(currentMessage)) {
                if (responseBytes.size > maxBytes) {
                    textEditor.text = "Response is too large to diff"
                    return
                }

                // Decide syntax highlighting by Content-Type (case-insensitive)
                val contentType = response.headers()
                    .firstOrNull { it.name().equals("Content-Type", ignoreCase = true) }
                    ?.value()
                    ?.lowercase(Locale.getDefault())
                    ?: ""

                when {
                    contentType.contains("json") -> textEditor.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_JSON
                    contentType.contains("html") -> textEditor.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_HTML
                    contentType.contains("javascript") -> textEditor.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT
                    contentType.contains("css") -> textEditor.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_CSS
                    contentType.contains("xml") -> textEditor.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_XML
                    else -> textEditor.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
                }

                val responseText = api.utilities().byteUtils().convertToString(responseBytes)
                textEditor.text = responseText
                textEditor.removeAllLineHighlights()

                if (isLastService(currentProtocol, currentHost, currentPort) &&
                    lastMessage != null &&
                    !responseBytes.contentEquals(lastMessage) &&
                    (lastMessage?.isNotEmpty() == true)
                ) {
                    val previousText = api.utilities().byteUtils().convertToString(lastMessage!!)

                    // Pre-compute line start offsets (UTF-16 code units) for absolute highlighting
                    val lines = responseText.split("\n")
                    val lineStartOffsets = IntArray(lines.size + 1)
                    var acc = 0
                    for (i in lines.indices) {
                        lineStartOffsets[i] = acc
                        acc += lines[i].length + 1 // +1 for the '\n' we split on
                    }
                    lineStartOffsets[lines.size] = acc

                    // Ask Rust to compute both line-level blocks and inline spans
                    val deco: Decorations = computeDecorations(previousText, responseText)

                    // Apply line-level highlights
                    for (blk: LineBlock in deco.lineBlocks) {
                        when (blk.kind) {
                            DeltaKind.DELETE -> {
                                try {
                                    textEditor.addLineHighlight(blk.startLine.toInt(), lineDeleteColor)
                                } catch (_: BadLocationException) {}
                            }
                            DeltaKind.INSERT -> {
                                val start = blk.startLine.toInt()
                                val end = start + blk.lineCount.toInt()
                                var i = start
                                while (i < end) {
                                    try { textEditor.addLineHighlight(i, lineInsertColor) } catch (_: BadLocationException) {}
                                    i++
                                }
                            }
                            // For CHANGE, we rely on inline spans below to avoid double-highlighting whole lines
                            DeltaKind.CHANGE -> {}
                        }
                    }

                    // Apply inline (word/char) highlights using absolute offsets computed from line + UTF-16 columns
                    val highlighter = textEditor.highlighter
                    for (span: InlineSpan in deco.inlineSpans) {
                        val base = if (span.line.toInt() < lineStartOffsets.size) lineStartOffsets[span.line.toInt()] else 0
                        val start = base + span.startColUtf16.toInt()
                        val end = base + span.endColUtf16.toInt()
                        if (start < end && end <= responseText.length + 1) {
                            addHighlight(start, end, highlighter, modifiedPainter)
                        }
                    }
                }
            }
        }

        lastMessage = responseBytes
        val svc2 = requestResponse.request().httpService()
        lastPort = svc2.port()
        lastHost = svc2.host()
        lastProtocol = if (svc2.secure()) "https" else "http"
        currentMessage = responseBytes
    }

    override fun isEnabledFor(requestResponse: HttpRequestResponse?): Boolean {
        return requestResponse?.response() != null
    }

    override fun caption(): String {
        return "Diff"
    }

    override fun uiComponent(): Component {
        return diffContainer
    }

    override fun selectedData(): Selection? {
        return null
    }

    override fun isModified(): Boolean {
        return false
    }

    companion object {
        private var lastMessage: ByteArray? = null
        private var lastPort = 0
        private var lastHost: String? = null
        private var lastProtocol: String? = null

        fun isLastService(currentProtocol: String?, currentHost: String?, currentPort: Int?): Boolean {
            if (lastPort == 0 || lastHost == null || lastProtocol == null) {
                return true
            }
            return currentPort == lastPort && currentHost == lastHost && currentProtocol == lastProtocol
        }
    }
}