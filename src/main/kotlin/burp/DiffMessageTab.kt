package burp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.ui.Selection
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor
import uniffi.diffy.DeltaKind
import uniffi.diffy.computeDeltas
import com.github.difflib.text.DiffRowGenerator
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
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

    private val red = "#dc3545"
    private val green = "#28a745"
    private val blue = "#0d6efd"
    private val modifiedPainter: Highlighter.HighlightPainter = DefaultHighlightPainter(Color.decode(blue))
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
                    textEditor.antiAliasingEnabled = false
                    scrollPane.setAutoscrolls(true)
                    val caret = textEditor.caret as DefaultCaret
                    caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE)

                    if (UIManager.getLookAndFeel().id.contains("Dar")) {
                        try {
                            val theme = Theme.load(
                                javaClass.getResourceAsStream(
                                    "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                                )
                            )
                            theme.apply(textEditor)
                        } catch (_: IOException) {
                        }
                    }
                    diffContainer.add(scrollPane)
                }
                componentShown = true
            }
        })
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
                    val currentResponse = responseText.split("\n").toMutableList()
                    val previousText = api.utilities().byteUtils().convertToString(lastMessage!!)

                    val highlighter = textEditor.highlighter
                    val deltasFromRust = computeDeltas(previousText, responseText)

                    for (delta in deltasFromRust) {
                        when (delta.kind) {
                            DeltaKind.DELETE -> {
                                try {
                                    textEditor.addLineHighlight(delta.targetPosition.toInt(), Color.decode(red))
                                } catch (_: BadLocationException) {}
                            }
                            DeltaKind.INSERT -> {
                                try {
                                    val start = delta.targetPosition.toInt()
                                    val end = start + delta.targetLines.size
                                    var i = start
                                    while (i < end) {
                                        textEditor.addLineHighlight(i, Color.decode(green))
                                        i++
                                    }
                                } catch (_: BadLocationException) {}
                            }
                            DeltaKind.CHANGE -> {
                                val linePos = delta.targetPosition.toInt()
                                var pos = 0
                                var i = 0
                                while (i < linePos) {
                                    pos += currentResponse[i].length + 1
                                    i++
                                }
                                val finalPos = pos
                                val generator = DiffRowGenerator.create()
                                    .showInlineDiffs(true)
                                    .mergeOriginalRevised(true)
                                    .inlineDiffByWord(true)
                                    .lineNormalizer { f: String? -> f }
                                    .processDiffs { diff: String? ->
                                        val targetLines = delta.targetLines
                                        var currentLinePos = finalPos
                                        var j = 0
                                        while (j < targetLines.size) {
                                            val line = targetLines[j]
                                            val foundPos = line.indexOf(diff!!)
                                            if (foundPos != -1) {
                                                val start = currentLinePos + foundPos
                                                val end = start + diff.length
                                                addHighlight(start, end, highlighter, modifiedPainter)
                                                break
                                            } else {
                                                currentLinePos += line.length + 1
                                            }
                                            j++
                                        }
                                        diff
                                    }
                                    .build()

                                generator.generateDiffRows(delta.sourceLines, delta.targetLines)

                                var currentLine = linePos + 1
                                var k = delta.sourceLines.size
                                while (k < delta.targetLines.size) {
                                    try {
                                        textEditor.addLineHighlight(currentLine, Color.decode(green))
                                    } catch (_: BadLocationException) {}
                                    currentLine++
                                    k++
                                }
                            }
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