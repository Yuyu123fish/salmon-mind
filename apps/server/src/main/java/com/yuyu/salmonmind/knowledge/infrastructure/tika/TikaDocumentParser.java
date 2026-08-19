package com.yuyu.salmonmind.knowledge.infrastructure.tika;

import com.yuyu.salmonmind.knowledge.api.KnowledgeException;
import com.yuyu.salmonmind.knowledge.application.port.DocumentParserPort;
import com.yuyu.salmonmind.knowledge.domain.ParsedDocument;
import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内 Tika Adapter。只接收已经通过扩展名/实际媒体类型校验的文件，
 * 以有界 BodyContentHandler 解析，不启用 OCR、外部程序或嵌入附件递归。
 */
@Component
class TikaDocumentParser implements DocumentParserPort {

    private static final int MAX_TEXT_CHARS = 2_000_000;
    private static final int MAX_PAGE_COUNT = 5_000;
    private static final long MAX_PARSE_MILLIS = 30_000;

    private final Tika detector;
    private final AutoDetectParser parser;
    private final ExecutorService parseExecutor;
    private final AtomicBoolean parseInFlight = new AtomicBoolean();

    TikaDocumentParser() {
        // 显式构造配置对象作为安全边界；本 Stage 不引入 Tesseract/OCR parser。
        TikaConfig config = TikaConfig.getDefaultConfig();
        this.detector = new Tika(config);
        this.parser = new AutoDetectParser(config);
        this.parseExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "salmon-tika-parser");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public String detect(Path file) {
        try {
            return detector.detect(file);
        } catch (IOException ex) {
            throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "无法识别文档类型", ex);
        }
    }

    @Override
    public ParsedDocument parse(Path file, String expectedMediaType) {
        if (!parseInFlight.compareAndSet(false, true)) {
            throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED,
                    "解析器正在处理其他文档，请稍后重试");
        }
        Future<ParsedDocument> task;
        try {
            task = parseExecutor.submit(() -> {
                try {
                    return parseInternal(file, expectedMediaType);
                } finally {
                    // 超时方取消 Future，但底层 parser 可能仍需响应中断；只有真实任务
                    // 结束后才释放槽位，避免后续请求无限排队在同一条解析线程上。
                    parseInFlight.set(false);
                }
            });
        } catch (RuntimeException ex) {
            parseInFlight.set(false);
            throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "解析器当前不可用", ex);
        }
        try {
            return task.get(MAX_PARSE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "文档解析超过时间上限", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "文档解析被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof KnowledgeException knowledgeException) {
                throw knowledgeException;
            }
            throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "文档解析失败", cause);
        }
    }

    private ParsedDocument parseInternal(Path file, String expectedMediaType) {
        try {
            String detected = detect(file);
            if (expectedMediaType != null && !expectedMediaType.equalsIgnoreCase(detected)
                    && !(expectedMediaType.toLowerCase(Locale.ROOT).startsWith("text/")
                    && detected.toLowerCase(Locale.ROOT).startsWith("text/"))) {
                throw new KnowledgeException(KnowledgeException.Code.INVALID_UPLOAD, "文档实际类型与提交类型不一致");
            }
            validatePdfBounds(file, detected);
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, detected);
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARS);
            ParseContext context = new ParseContext();
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            pdfConfig.setExtractInlineImages(false);
            context.set(PDFParserConfig.class, pdfConfig);
            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata embeddedMetadata) {
                    return false;
                }

                @Override
                public void parseEmbedded(java.io.InputStream stream,
                                          org.xml.sax.ContentHandler embeddedHandler,
                                          Metadata embeddedMetadata,
                                          boolean outputHtml) {
                    // 当前 Stage 不递归处理附件、宏或嵌套文档。
                }
            });
            parser.parse(TikaInputStream.get(file), handler, metadata, context);
            String text = normalize(handler.toString());
            int pageCount = parsePageCount(metadata);
            if (pageCount > MAX_PAGE_COUNT) {
                throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "文档页数超过处理上限");
            }
            return new ParsedDocument(detected, text, pageCount, text.length(), TikaMetadataProjector.project(metadata));
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (Exception ex) {
            if (hasCause(ex, InvalidPasswordException.class)) {
                throw new KnowledgeException(KnowledgeException.Code.DOCUMENT_PASSWORD_REQUIRED,
                        "PDF 受密码保护，当前不接收密码", ex);
            }
            throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "文档解析失败", ex);
        }
    }

    private static void validatePdfBounds(Path file, String detected) {
        if (!"application/pdf".equalsIgnoreCase(detected)) {
            return;
        }
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            if (document.getNumberOfPages() > MAX_PAGE_COUNT) {
                throw new KnowledgeException(KnowledgeException.Code.PARSE_FAILED, "文档页数超过处理上限");
            }
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (InvalidPasswordException ex) {
            throw new KnowledgeException(KnowledgeException.Code.DOCUMENT_PASSWORD_REQUIRED,
                    "PDF 受密码保护，当前不接收密码", ex);
        } catch (IOException ignored) {
            // 结构损坏交给 Tika 主解析流程统一映射；预检不能掩盖更有用的解析诊断。
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            if (character == '\r') {
                cleaned.append('\n');
            } else if (character == '\n' || character == '\t' || !Character.isISOControl(character)) {
                cleaned.append(character);
            }
        }
        return cleaned.toString().replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
    }

    private static int parsePageCount(Metadata metadata) {
        String value = metadata.get("xmpTPg:NPages");
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
        Throwable current = failure;
        while (current != null) {
            if (expected.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @PreDestroy
    void close() {
        parseExecutor.shutdownNow();
    }
}
