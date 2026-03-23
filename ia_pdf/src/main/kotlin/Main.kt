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
                    div("max-w-2xl w-full bg-white rounded-lg shadow-md p-8") {
                        h1("text-3xl font-bold mb-6 text-blue-600 text-center") { +"Extrator de Produtos PDF" }
                        
                        form(action = "/upload", method = FormMethod.post, encType = FormEncType.multipartFormData) {
                            div("mb-6") {
                                label("block text-gray-700 text-sm font-bold mb-2") { +"Selecione o arquivo PDF" }
                                input(type = InputType.file, name = "pdfFile") {
                                    attributes["accept"] = ".pdf"
                                    classes = setOf("block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100")
                                    required = true
                                }
                            }
                            button(type = ButtonType.submit) {
                                classes = setOf("w-full bg-blue-600 hover:bg-blue-700 text-white font-bold py-2 px-4 rounded-lg transition duration-200")
                                +"Extrair Dados"
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
                    application.log.info("Processando parte: ${part.name}")
                    if (part is PartData.FileItem) {
                        nomeArquivo = part.originalFileName ?: "documento.pdf"
                        application.log.info("Arquivo recebido: $nomeArquivo")
                        val tempFile = File.createTempFile("upload-", ".pdf")
                        part.provider().toInputStream().use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        application.log.info("Arquivo temporário criado em: ${tempFile.absolutePath}")
                        resultados = extrairDadosDoPdf(tempFile)
                        tempFile.delete()
                    }
                    part.dispose()
                }
            } catch (e: Exception) {
                application.log.error("Erro ao processar multipart: ${e.message}", e)
                throw e
            }

            application.log.info("Processamento concluído para $nomeArquivo. Resultados: ${resultados.size}")
            call.respondHtml {
                head {
                    title("Resultados da Extração")
                    script { src = "https://cdn.tailwindcss.com" }
                }
                body("bg-gray-100 min-h-screen py-10 px-4") {
                    div("max-w-4xl mx-auto") {
                        div("bg-white rounded-lg shadow-md p-8") {
                            h1("text-2xl font-bold mb-4 text-blue-600") { +"Resultados para: $nomeArquivo" }
                            
                            if (resultados.isEmpty()) {
                                div("p-6 bg-yellow-50 border-l-4 border-yellow-400 rounded-md") {
                                    p("text-yellow-700 font-medium") { 
                                        +"Aviso: Não foi encontrado nenhum parágrafo que contenha descrição de produtos ou ações neste documento." 
                                    }
                                    p("text-yellow-600 text-sm mt-2") {
                                        +"Certifique-se de que o PDF contém as palavras-chave 'Produto' ou termos relacionados a ações."
                                    }
                                }
                            } else {
                                div("space-y-2") {
                                    resultados.forEach { resultado ->
                                        val isProduto = resultado.startsWith("PRODUTO")
                                        div("p-3 rounded border-l-4 ${if (isProduto) "bg-blue-50 border-blue-500" else "bg-green-50 border-green-500 ml-4"}") {
                                            +resultado
                                        }
                                    }
                                }
                            }
                            
                            a(href = "/", classes = "inline-block mt-8 text-blue-600 hover:underline font-semibold") {
                                +"← Voltar para Início"
                            }
                        }
                    }
                }
            }
        }
    }
}

fun extrairDadosDoPdf(arquivo: File): List<String> {
    val resultados = mutableListOf<String>()
    
    // Palavras-chave e padrões
    val patternProduto = Regex("^(?:\\d+\\.\\d+\\.\\s+)?PRODUTO\\s+\\d+", RegexOption.IGNORE_CASE)
    val keywordsAcoesInit = listOf("A ", "O ", "As ", "Os ", "Realizar", "Providenciar", "Acompanhar", "Participar", "Adotar", "Administrar", "Preparar", "Consolidar", "Ficará", "Será", "Coletar", "Prover", "Desenvolver", "Dar suporte", "Apoio", "Assessorar", "Examinar", "Estruturar", "Monitorar", "Definir", "Verificar", "Controlar", "Mapear", "Oferecer", "Identificar", "Fornecer", "Propor", "Preparar")
    
    try {
        PDDocument.load(arquivo).use { documento ->
            val extrator = PDFTextStripper()
            val textoCompleto = extrator.getText(documento)
            val linhas = textoCompleto.lines()

            var currentType = ""
            var currentParagraph = StringBuilder()

            for (linha in linhas) {
                val linhaTrim = linha.trim()
                
                // Ignora linhas de rodapé ou cabeçalhos de página do SEI (exemplo: URLs e datas)
                if (linhaTrim.contains("sei.dnit.gov.br") || linhaTrim.contains("SEI/DNIT") || linhaTrim.contains("Termo de Referência")) {
                    continue
                }

                if (linhaTrim.isBlank()) {
                    if (currentParagraph.isNotEmpty()) {
                        resultados.add("$currentType: ${currentParagraph.toString().trim()}")
                        currentParagraph.setLength(0)
                    }
                    continue
                }

                // Verifica se é um cabeçalho de PRODUTO (ex: 16.4. PRODUTO 01)
                if (patternProduto.containsMatchIn(linhaTrim)) {
                    if (currentParagraph.isNotEmpty()) {
                        resultados.add("$currentType: ${currentParagraph.toString().trim()}")
                        currentParagraph.setLength(0)
                    }
                    currentType = "PRODUTO IDENTIFICADO"
                    currentParagraph.append(linhaTrim)
                    // Fecha o parágrafo do cabeçalho imediatamente para os próximos serem ações
                    resultados.add("$currentType: ${currentParagraph.toString().trim()}")
                    currentParagraph.setLength(0)
                    currentType = "AÇÃO/DETALHE"
                    continue
                }

                // Verifica se a linha parece ser o início de uma ação/atribuição
                val isStartOfAction = keywordsAcoesInit.any { linhaTrim.startsWith(it, ignoreCase = true) } || 
                                     linhaTrim.startsWith("-") || 
                                     linhaTrim.startsWith("*") ||
                                     linhaTrim.endsWith(":") // Ex: "À Coordenação-Geral caberá:"

                if (isStartOfAction && currentType == "AÇÃO/DETALHE") {
                    if (currentParagraph.isNotEmpty()) {
                        resultados.add("$currentType: ${currentParagraph.toString().trim()}")
                        currentParagraph.setLength(0)
                    }
                    currentParagraph.append(linhaTrim)
                } else if (currentParagraph.isNotEmpty()) {
                    // Continua o parágrafo anterior (tratando quebras de linha no PDF)
                    currentParagraph.append(" ").append(linhaTrim)
                } else if (currentType == "AÇÃO/DETALHE") {
                    // Se já estamos em modo ação mas a linha não começa com keyword, 
                    // provavelmente é uma continuação ou novo parágrafo de detalhe
                    currentParagraph.append(linhaTrim)
                }
            }
            
            if (currentParagraph.isNotEmpty()) {
                resultados.add("$currentType: ${currentParagraph.toString().trim()}")
            }
        }
    } catch (e: Exception) {
        resultados.add("Erro ao processar PDF: ${e.message}")
    }
    return resultados
}
