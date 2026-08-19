package com.yuyu.salmonmind.knowledge.infrastructure.tika;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuyu.salmonmind.knowledge.domain.ParsedDocumentMetadata;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.PDF;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TikaCoreProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 将 Tika 的开放 Metadata 映射为有界领域记录。
 * 只有白名单属性会被读取；这里同时完成控制字符清理、长度限制、作者去重和时间解析。
 */
final class TikaMetadataProjector {

    static final int MAX_FIELD_CHARS = 512;
    static final int MAX_AUTHORS = 16;
    static final int MAX_SERIALIZED_BYTES = 4_096;

    private static final ObjectMapper SIZE_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private TikaMetadataProjector() {
    }

    static ParsedDocumentMetadata project(Metadata metadata) {
        String title = firstText(metadata,
                () -> metadata.get(TikaCoreProperties.TITLE),
                () -> metadata.get(PDF.DOC_INFO_TITLE));
        String subject = firstText(metadata,
                () -> metadata.get(TikaCoreProperties.SUBJECT),
                () -> metadata.get(PDF.DOC_INFO_SUBJECT));
        String description = firstText(metadata,
                () -> metadata.get(TikaCoreProperties.DESCRIPTION));
        String language = firstText(metadata,
                () -> metadata.get(TikaCoreProperties.LANGUAGE),
                () -> metadata.get(TikaCoreProperties.TIKA_DETECTED_LANGUAGE));
        String producer = firstText(metadata,
                () -> metadata.get(PDF.PRODUCER),
                () -> metadata.get(PDF.DOC_INFO_PRODUCER),
                () -> metadata.get(TikaCoreProperties.CREATOR_TOOL),
                () -> metadata.get(PDF.DOC_INFO_CREATOR_TOOL));
        List<String> authors = authors(metadata);
        Instant createdAt = firstDate(metadata,
                TikaCoreProperties.CREATED, Office.CREATION_DATE, PDF.DOC_INFO_CREATED);
        Instant modifiedAt = firstDate(metadata,
                TikaCoreProperties.MODIFIED, Office.SAVE_DATE, PDF.DOC_INFO_MODIFICATION_DATE);

        ParsedDocumentMetadata projected = new ParsedDocumentMetadata(
                title, authors, subject, description, language, createdAt, modifiedAt, producer);
        return bound(projected);
    }

    private static List<String> authors(Metadata metadata) {
        Set<String> values = new LinkedHashSet<>();
        addAuthors(values, metadata.getValues(TikaCoreProperties.CREATOR));
        addAuthors(values, metadata.getValues(Office.AUTHOR));
        addAuthors(values, metadata.getValues(TikaCoreProperties.CONTRIBUTOR));
        return values.stream().limit(MAX_AUTHORS).toList();
    }

    private static void addAuthors(Set<String> target, String[] candidates) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            String normalized = normalize(candidate);
            if (normalized != null) {
                target.add(normalized);
            }
            if (target.size() >= MAX_AUTHORS) {
                return;
            }
        }
    }

    private static String firstText(Metadata metadata, Supplier<String>... suppliers) {
        for (Supplier<String> supplier : suppliers) {
            String value = normalize(supplier.get());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant firstDate(Metadata metadata, Property... properties) {
        for (Property property : properties) {
            String value = normalize(metadata.get(property));
            Instant parsed = parseDate(value);
            if (parsed != null) {
                return parsed;
            }
            if (value != null) {
                parsed = parseDate(metadata.get(property));
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static Instant parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        boolean whitespace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                codePoint = ' ';
            }
            if (Character.isWhitespace(codePoint)) {
                whitespace = true;
                continue;
            }
            if (whitespace && !cleaned.isEmpty()) {
                cleaned.append(' ');
            }
            whitespace = false;
            cleaned.appendCodePoint(codePoint);
        }
        String result = cleaned.toString().trim();
        if (result.isEmpty()) {
            return null;
        }
        if (result.codePointCount(0, result.length()) > MAX_FIELD_CHARS) {
            int end = result.offsetByCodePoints(0, MAX_FIELD_CHARS);
            result = result.substring(0, end);
        }
        return result;
    }

    private static ParsedDocumentMetadata bound(ParsedDocumentMetadata metadata) {
        if (serializedSize(metadata) <= MAX_SERIALIZED_BYTES) return metadata;

        // 常态上限允许保留完整白名单；只有整体 JSON 超限时才按说明性优先级收缩，
        // 最后一档保留时间字段，确保结果仍是有界、可回读的合法对象，而不是放行超大 JSON。
        ParsedDocumentMetadata current = replace(metadata, shorten(metadata.title(), 128),
                shortenAuthors(metadata.authors(), 128, MAX_AUTHORS), shorten(metadata.subject(), 128),
                shorten(metadata.description(), 256), shorten(metadata.language(), 64), metadata.createdAt(),
                metadata.modifiedAt(), shorten(metadata.producer(), 128));
        if (serializedSize(current) <= MAX_SERIALIZED_BYTES) return current;

        current = replace(current, shorten(current.title(), 64), shortenAuthors(current.authors(), 64, 1),
                shorten(current.subject(), 64), shorten(current.description(), 128), shorten(current.language(), 32),
                current.createdAt(), current.modifiedAt(), shorten(current.producer(), 64));
        if (serializedSize(current) <= MAX_SERIALIZED_BYTES) return current;

        current = replace(current, null, List.of(), null, null, null, current.createdAt(), current.modifiedAt(), null);
        return current;
    }

    private static ParsedDocumentMetadata replace(ParsedDocumentMetadata original, String title, List<String> authors,
                                                   String subject, String description, String language,
                                                   Instant createdAt, Instant modifiedAt, String producer) {
        return new ParsedDocumentMetadata(title, authors, subject, description, language, createdAt, modifiedAt, producer);
    }

    private static String shorten(String value, int maxChars) {
        if (value == null || value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxChars));
    }

    private static List<String> shortenAuthors(List<String> authors, int maxChars, int maxAuthors) {
        return authors.stream().limit(maxAuthors).map(value -> shorten(value, maxChars)).toList();
    }

    private static int serializedSize(ParsedDocumentMetadata metadata) {
        try {
            return SIZE_MAPPER.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception ex) {
            // 记录只含有限的基础类型；若序列化器异常，按超限处理而不放行未知大小。
            return Integer.MAX_VALUE;
        }
    }
}
