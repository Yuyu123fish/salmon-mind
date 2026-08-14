package com.yuyu.salmonmind.conversation.infrastructure.jsonl;

import com.yuyu.salmonmind.conversation.api.ConversationException;
import com.yuyu.salmonmind.conversation.api.Entry;
import com.yuyu.salmonmind.conversation.application.port.ConversationHistoryRepository;
import com.yuyu.salmonmind.conversation.domain.ConversationHistory;
import com.yuyu.salmonmind.conversation.infrastructure.jsonl.JsonlCodec.JsonlCorruptedException;
import com.yuyu.salmonmind.conversation.infrastructure.jsonl.JsonlCodec.TornTailException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conversation JSONL 历史 Adapter：原子创建、串行追加、强制刷盘、torn-tail 修复
 * 与 Compaction 字节偏移校验。每个 Conversation 由单一写入方串行使用。
 * 本类只做文件 I/O 与格式校验；Active Path 与压缩节点定位规则属于
 * domain 的 {@link ConversationHistory}，不在此处重复实现。
 */
@Component
class JsonlConversationHistoryRepository implements ConversationHistoryRepository {

    static final String EVENTS_FILE = "events.jsonl";

    private final Path dataRoot;
    private final JsonlCodec codec;

    // Spring 构造器；另一个包私有构造器供测试注入临时数据目录
    @Autowired
    JsonlConversationHistoryRepository(
            @Value("${salmon.conversation.data-dir:data}") String dataDir,
            JsonlCodec codec
    ) {
        this(Path.of(dataDir), codec);
    }

    JsonlConversationHistoryRepository(Path dataRoot, JsonlCodec codec) {
        this.dataRoot = dataRoot;
        this.codec = codec;
    }

    Path directoryOf(UUID conversationId) {
        return dataRoot.resolve("conversations").resolve(conversationId.toString());
    }

    Path fileOf(UUID conversationId) {
        return directoryOf(conversationId).resolve(EVENTS_FILE);
    }

    /** 原子创建：同目录临时文件写入 Header、强制刷盘后移动为正式文件。 */
    @Override
    public void create(UUID conversationId, Instant createdAt) {
        Path file = fileOf(conversationId);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), "events", ".tmp");
            try {
                String headerLine = codec.encodeHeader(
                        new ConversationHistory.Header(ConversationHistory.FORMAT_VERSION, conversationId, createdAt));
                writeAllAndForce(tmp, headerLine.getBytes(StandardCharsets.UTF_8), (byte) '\n');
                moveAtomically(tmp, file);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("创建 Conversation JSONL 失败", ex);
        }
    }

    /** 串行追加一条完整 Entry 并强制刷盘；追加顺序即调用顺序。 */
    @Override
    public void append(UUID conversationId, Entry entry) {
        if (!conversationId.equals(entry.conversationId())) {
            throw new IllegalArgumentException("Entry 的 conversationId 与文件不一致");
        }
        Path file = fileOf(conversationId);
        String line = codec.encodeEntry(entry);
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            channel.write(ByteBuffer.wrap(new byte[]{(byte) '\n'}));
            channel.force(true);
        } catch (IOException ex) {
            throw new UncheckedIOException("追加 Conversation Entry 失败", ex);
        }
    }

    /**
     * 读取完整历史：Header 身份校验、中间损坏拒绝；末行 JSON 截断视为未确认写入，
     * 自动修复（删除该行并写回）后返回修复结果。
     */
    @Override
    public ConversationHistory read(UUID conversationId) {
        Path file = fileOf(conversationId);
        if (!Files.exists(file)) {
            throw historyCorrupted("会话历史文件缺失");
        }
        List<String> lines;
        List<Long> offsets;
        try {
            byte[] bytes = Files.readAllBytes(file);
            lines = new ArrayList<>();
            offsets = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    lines.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                    offsets.add((long) start);
                    start = i + 1;
                }
            }
            if (start < bytes.length) {
                lines.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
                offsets.add((long) start);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("读取 Conversation JSONL 失败", ex);
        }
        if (lines.isEmpty()) {
            throw historyCorrupted("会话历史文件为空");
        }

        ConversationHistory.Header header;
        // 解码 Header
        try {
            header = codec.decodeHeader(lines.get(0));
        } catch (TornTailException ex) {
            throw historyCorrupted("Header 行 JSON 截断");
        } catch (JsonlCorruptedException ex) {
            throw historyCorrupted(ex.getMessage());
        }
        if (!conversationId.equals(header.conversationId())) {
            throw historyCorrupted("JSONL Header 的 conversationId 与文件身份不一致");
        }

        List<Entry> entries = new ArrayList<>();
        List<Long> entryOffsets = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            boolean lastLine = i == lines.size() - 1;
            try {
                entries.add(codec.decodeEntry(lines.get(i)));
                entryOffsets.add(offsets.get(i));
            } catch (TornTailException ex) {
                if (!lastLine) {
                    throw historyCorrupted("中间行 JSON 截断");
                }
                // 末行截断：删除该行并写回，视为未确认写入
                truncateTail(file, lines.size() - 1);
                break;
            } catch (JsonlCorruptedException ex) {
                if (lastLine) {
                    throw historyCorrupted("末行完整但非法: " + ex.getMessage());
                }
                throw historyCorrupted("中间行损坏: " + ex.getMessage());
            }
        }
        validateSeq(entries);
        return new ConversationHistory(header, List.copyOf(entries), List.copyOf(entryOffsets));
    }

    // seq 是从 1 开始的稳定递增顺序；跳号、重复或乱序都属于历史损坏
    private static void validateSeq(List<Entry> entries) {
        long expected = 1;
        for (Entry entry : entries) {
            if (entry.seq() != expected) {
                throw historyCorrupted("Entry seq 不连续: 期望 " + expected + " 实际 " + entry.seq());
            }
            expected++;
        }
    }

    /** 按字节偏移定位校验 Compaction Entry；不一致或越界返回 false，不采用未校验偏移。 */
    @Override
    public boolean validateCompaction(UUID conversationId, UUID entryId, long seq, long byteOffset) {
        Path file = fileOf(conversationId);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (byteOffset < 0 || byteOffset >= channel.size()) {
                return false;
            }
            channel.position(byteOffset);
            String line = readLineUtf8(channel);
            if (line == null || line.isBlank()) {
                return false;
            }
            Entry entry = codec.decodeEntry(line);
            return entryId.equals(entry.id())
                    && seq == entry.seq()
                    && conversationId.equals(entry.conversationId());
        } catch (TornTailException ex) {
            return false;
        } catch (JsonlCorruptedException ex) {
            return false;
        } catch (IOException ex) {
            throw new UncheckedIOException("校验 Compaction 偏移失败", ex);
        }
    }

    /** 尽力删除孤儿目录（创建时数据库写入失败后的清理）。 */
    @Override
    public void deleteOrphan(UUID conversationId) {
        Path dir = directoryOf(conversationId);
        try {
            if (Files.exists(dir)) {
                try (var paths = Files.walk(dir)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("清理孤儿 Conversation 目录失败", ex);
        }
    }

    // 重写文件，只保留前 keepLines 行（删除截断末行），同目录临时文件 + 原子移动
    private void truncateTail(Path file, int keepLines) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= keepLines) {
                return;
            }
            Path tmp = Files.createTempFile(file.getParent(), "events", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int i = 0; i < keepLines; i++) {
                    byte[] line = lines.get(i).getBytes(StandardCharsets.UTF_8);
                    channel.write(ByteBuffer.wrap(line));
                    channel.write(ByteBuffer.wrap(new byte[]{(byte) '\n'}));
                }
                channel.force(true);
            }
            moveAtomically(tmp, file);
            Files.deleteIfExists(tmp);
        } catch (IOException ex) {
            throw new UncheckedIOException("修复 torn tail 失败", ex);
        }
    }

    // 从当前 FileChannel 位置读取一行（到 \n 或 EOF），UTF-8 解码
    private static String readLineUtf8(FileChannel channel) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(1024);
        boolean found = false;
        while (!found) {
            buf.clear();
            int read = channel.read(buf);
            if (read < 0) {
                break;
            }
            buf.flip();
            while (buf.hasRemaining()) {
                byte b = buf.get();
                if (b == '\n') {
                    found = true;
                    break;
                }
                line.write(b);
            }
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
    }

    private static void writeAllAndForce(Path file, byte[] bytes, byte trailing) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.write(ByteBuffer.wrap(new byte[]{trailing}));
            channel.force(true);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            // 同目录 move 即使无 ATOMIC_MOVE 也保留 rename 语义
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ConversationException historyCorrupted(String message) {
        return new ConversationException(
                ConversationException.ConversationErrorCode.CONVERSATION_HISTORY_CORRUPTED, message);
    }
}
