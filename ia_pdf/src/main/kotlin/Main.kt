import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.html.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.event.Level
import java.io.File
import java.util.*

fun main() {
    try {
        println("DEBUG: Iniciando servidor...")
        embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::module).start(wait = true)
    } catch (e: Exception) {
        println("DEBUG: Erro fatal no servidor: ${e.message}")
        e.printStackTrace()
    }
}

fun Application.module() {
    println("DEBUG: Iniciando configuração do módulo...")
    install(CallLogging) {
        level = Level.INFO
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            println("DEBUG: Exceção capturada: ${cause.message}")
            cause.printStackTrace()
            call.respondText(text = "500: ${cause.localizedMessage}", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        println("DEBUG: Configurando rotas...")
        get("/") {
            println("DEBUG: GET / acessado")
            call.respondHtml {
                head {
                    title("Extrator de PDF IA")
                    script { src = "https://cdn.tailwindcss.com" }
                }
                body("bg-gray-100 min-h-screen flex flex-col items-center py-10") {
                    div("max-w-4xl w-full bg-white rounded-lg shadow-md p-8") {
                        h1("text-3xl font-bold mb-6 text-blue-600 text-center") { +"Extrator de Produtos PDF" }
                        
                        form(action = "/upload", method = FormMethod.post, encType = FormEncType.multipartFormData) {
                            div("mb-6") {
                                label("block text-gray-700 text-sm font-bold mb-2") { +"Selecione o arquivo PDF do Termo de Referência" }
                                input(type = InputType.file, name = "pdfFile") {
                                    attributes["accept"] = ".pdf"
                                    classes = setOf("block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100")
                                    required = true
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("w-full bg-blue-600 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded-lg transition duration-200")
                                +"Extrair e Visualizar"
                            }
                        }
                    }
                }
            }
        }

        post("/upload") {
            application.log.info("Recebendo requisição POST em /upload")
            val multipart = call.receiveMultipart()
            var resultados = listOf<String>()
            var nomeArquivo = ""

            try {
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        nomeArquivo = part.originalFileName ?: "documento.pdf"
                        val tempFile = File.createTempFile("upload-", ".pdf")
                        part.provider().toInputStream().use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        resultados = extrairDadosDoPdf(tempFile)
                        tempFile.delete()
                    }
                    part.dispose()
                }
            } catch (e: Exception) {
                application.log.error("Erro ao processar multipart: ${e.message}", e)
                throw e
            }

            call.respondHtml {
                head {
                    title("Resultados da Extração")
                    script { src = "https://cdn.tailwindcss.com" }
                    style {
                        unsafe {
                            +"""
                            .table-auto td, .table-auto th { padding: 0.75rem; border-bottom: 1px solid #e2e8f0; }
                            .table-auto th { background-color: #f8fafc; text-align: left; font-weight: 600; }
                            """
                        }
                    }
                }
                body("bg-gray-100 min-h-screen py-10 px-4") {
                    div("max-w-6xl mx-auto") {
                        div("bg-white rounded-lg shadow-xl p-8") {
                            h1("text-2xl font-bold mb-6 text-blue-600 border-b pb-4") { +"Resultados: $nomeArquivo" }
                            
                            if (resultados.isEmpty()) {
                                div("p-6 bg-yellow-50 border-l-4 border-yellow-400 rounded-md") {
                                    p("text-yellow-700 font-medium") { +"Nenhum dado identificado." }
                                }
                            } else {
                                // 1. Tabela de Escopo (se houver dados de tabela)
                                val tableRows = resultados.filter { it.startsWith("TABELA_ROW:") }
                                if (tableRows.isNotEmpty()) {
                                    h2("text-xl font-bold mt-8 mb-4 text-gray-800") { +"Escopo dos Produtos (Tabela)" }
                                    div("overflow-x-auto bg-white rounded-lg border mb-10") {
                                        table("table-auto w-full text-sm") {
                                            thead {
                                                tr {
                                                    th { +"Produto" }
                                                    th { +"Nome / Descrição" }
                                                    th { +"Frequência" }
                                                    th { +"Unidade" }
                                                }
                                            }
                                            tbody {
                                                tableRows.forEach { row ->
                                                    val parts = row.removePrefix("TABELA_ROW:").split("|")
                                                    if (parts.size >= 4) {
                                                        tr {
                                                            td("font-bold text-blue-600") { +parts[0].trim() }
                                                            td { +parts[1].trim() }
                                                            td { +parts[2].trim() }
                                                            td { +parts[3].trim() }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 2. Visão Detalhada
                                h2("text-xl font-bold mt-8 mb-4 text-gray-800") { +"Principais Ações e Atribuições Detalhadas" }
                                div("space-y-3") {
                                    resultados.filter { !it.startsWith("TABELA_ROW:") && it != "### JSON FINAL ###" && !it.startsWith("{") && !it.startsWith("[") }.forEach { resultado ->
                                        val isProduto = resultado.startsWith("PRODUTO")
                                        val isAcao = resultado.startsWith("AÇÃO")
                                        val isAtividade = resultado.startsWith("ATIVIDADE")
                                        
                                        val bgClass = when {
                                            isProduto -> "bg-blue-50 border-blue-500 font-bold"
                                            isAcao -> "bg-purple-50 border-purple-500 ml-4 font-semibold"
                                            isAtividade -> "bg-green-50 border-green-500 ml-8"
                                            else -> "bg-gray-50 border-gray-400 ml-12 italic text-gray-600"
                                        }
                                        
                                        div("p-3 rounded border-l-4 $bgClass shadow-sm") {
                                            +resultado
                                        }
                                    }
                                }

                                // 3. JSON Final
                                val jsonContent = resultados.find { it.startsWith("{") || it.startsWith("[") }
                                if (jsonContent != null) {
                                    h2("text-xl font-bold mt-12 mb-4 text-gray-800 border-t pt-8") { +"Estrutura JSON Completa" }
                                    pre("p-4 bg-gray-900 text-green-400 rounded-lg overflow-x-auto text-xs font-mono shadow-2xl") {
                                        +jsonContent
                                    }
                                }
                            }
                            
                            div("mt-10 pt-6 border-t") {
                                a(href = "/", classes = "bg-blue-600 hover:bg-blue-700 text-white font-bold py-2 px-6 rounded-full transition duration-200 shadow-md") {
                                    +"← Novo Upload"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun extrairDadosDoPdf(arquivo: File): List<String> {
    // ----------------------------- 
    // RESULTADOS FINAIS 
    // ----------------------------- 
    val listaLinear = mutableListOf<String>()

    // Estrutura rica 
    data class Atividade(val nome: String, val detalhes: MutableList<String> = mutableListOf())
    data class Acao(val nome: String, val atividades: MutableList<Atividade> = mutableListOf())
    data class Produto(val nome: String, val acoes: MutableList<Acao> = mutableListOf())

    val produtos = mutableListOf<Produto>()

    // ----------------------------- 
    // REGEX UNIFICADOS 
    // ----------------------------- 
    val regexProduto = Regex(
        pattern = "(?i)(^|\\s)(?:\\d{1,2}\\.\\d{1,2}\\.?)?PRODUTO\\s+\\d+|3\\.\\d+\\.\\s*Produto\\s+\\d+"
    )

    val regexAcao = Regex(
        pattern = "(?i)^ação\\s+\\d+(?:\\.\\d+)?"
    )

    val regexAtividade = Regex(
        pattern = "(?i)^atividade\\s+\\d+(?:\\.\\d+)+"
    )

    val regexAcaoVerbal = Regex(
        pattern = "^(?i)(realizar|apoiar|gerenciar|elaborar|assessorar|monitorar|coletar|propor|manter|subsidiar|auxiliar|controlar|implantar|executar)"
    )

    val regexRodape = Regex("(?i)(sei\\.dnit|câmara nacional|modelo de serviços|aprovação|dnit sede|https://sei\\.dnit|34/79|35/79|36/79|37/79)")

    // Regex para linhas de tabela (ex: 01 Coordenação Geral Mensal Relatório)
    val regexTableRow = Regex("^(\\d{1,2}(?:\\.\\d)?)\\s+(.+?)\\s+(Mensal|Demanda)\\s+(.+)$", RegexOption.IGNORE_CASE)

    // ----------------------------- 
    // ESTADOS INTERNOS 
    // ----------------------------- 
    var produtoAtual: Produto? = null
    var acaoAtual: Acao? = null
    var atividadeAtual: Atividade? = null
    var buffer = StringBuilder()
    var insideTableArea = false

    fun flushBufferAsDetail() {
        val txt = buffer.toString().trim()
        if (txt.isNotEmpty()) {
            atividadeAtual?.detalhes?.add(txt)
            listaLinear.add("DETALHE: $txt")
        }
        buffer = StringBuilder()
    }

    fun iniciarProduto(nome: String) {
        flushBufferAsDetail()
        produtoAtual = Produto(nome)
        produtos.add(produtoAtual!!)
        acaoAtual = null
        atividadeAtual = null
        listaLinear.add("PRODUTO: $nome")
    }

    fun iniciarAcao(nome: String) {
        flushBufferAsDetail()
        acaoAtual = Acao(nome)
        produtoAtual?.acoes?.add(acaoAtual!!)
        atividadeAtual = null
        listaLinear.add("AÇÃO: $nome")
    }

    fun iniciarAtividade(nome: String) {
        flushBufferAsDetail()
        atividadeAtual = Atividade(nome)
        acaoAtual?.atividades?.add(atividadeAtual!!)
        listaLinear.add("ATIVIDADE: $nome")
    }

    // ----------------------------- 
    // INÍCIO DO PARSER DE PDF 
    // ----------------------------- 
    try {
        PDDocument.load(arquivo).use { pdf ->
            val texto = PDFTextStripper().getText(pdf)
            val linhas = texto.lines()

            for (linhaRaw in linhas) {
                val ln = linhaRaw.trim()

                // remover rodapé/sei 
                if (regexRodape.containsMatchIn(ln)) continue
                if (ln.isBlank()) {
                    flushBufferAsDetail()
                    continue
                }

                // Detecção de área de tabela
                if (ln.contains("Escopo dos Produtos", ignoreCase = true) || ln.contains("PRODUTOS DESCRIÇÃO", ignoreCase = true)) {
                    insideTableArea = true
                    continue
                }
                if (insideTableArea && (ln.startsWith("16.") || ln.contains("ATIVIDADES", ignoreCase = true))) {
                    insideTableArea = false
                }

                // Processamento de Tabela
                if (insideTableArea) {
                    val match = regexTableRow.find(ln)
                    if (match != null) {
                        val (id, nome, freq, unid) = match.destructured
                        listaLinear.add("TABELA_ROW: $id | $nome | $freq | $unid")
                        continue
                    }
                }

                // É PRODUTO? 
                if (regexProduto.containsMatchIn(ln)) {
                    iniciarProduto(ln)
                    continue
                }

                // É AÇÃO (nomeada)? 
                if (regexAcao.containsMatchIn(ln)) {
                    iniciarAcao(ln)
                    continue
                }

                // É ATIVIDADE? 
                if (regexAtividade.containsMatchIn(ln)) {
                    iniciarAtividade(ln)
                    continue
                }

                // AÇÃO "verbal" (sem numeração)? 
                if (regexAcaoVerbal.containsMatchIn(ln) && acaoAtual != null && atividadeAtual == null) {
                    iniciarAtividade("Atividade descritiva")
                    buffer.append(ln)
                    continue
                }

                // Se já temos atividade → conteúdo 
                if (atividadeAtual != null) {
                    buffer.append(" ").append(ln)
                }
            }

            flushBufferAsDetail()
        }

    } catch (e: Exception) {
        listaLinear.add("Erro ao processar PDF: ${e.message}")
    }

    // ----------------------------- 
    // ADICIONAR JSON AO FINAL DA LISTA 
    // ----------------------------- 
    val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    val jsonFinal = gson.toJson(produtos)

    listaLinear.add("### JSON FINAL ###")
    listaLinear.add(jsonFinal)

    return listaLinear
}
