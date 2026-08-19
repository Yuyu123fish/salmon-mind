package com.yuyu.salmonmind.knowledge.infrastructure.postgres;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yuyu.salmonmind.knowledge.domain.ParsedDocumentMetadata;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Revision JSONB 的公开 MyBatis 编解码器。
 * PostgreSQL 只接收 JSON 文本，领域/API 层不会泄漏 PGobject、JsonNode 或 MyBatis 类型。
 */
@MappedTypes(ParsedDocumentMetadata.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class ParsedDocumentMetadataJsonTypeHandler extends BaseTypeHandler<ParsedDocumentMetadata> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, ParsedDocumentMetadata parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setObject(i, MAPPER.writeValueAsString(parameter), Types.OTHER);
        } catch (Exception ex) {
            throw new SQLException("解析文档元信息 JSON 序列化失败", ex);
        }
    }

    @Override
    public ParsedDocumentMetadata getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decode(rs.getString(columnName));
    }

    @Override
    public ParsedDocumentMetadata getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decode(rs.getString(columnIndex));
    }

    @Override
    public ParsedDocumentMetadata getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decode(cs.getString(columnIndex));
    }

    private static ParsedDocumentMetadata decode(String json) throws SQLException {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return ParsedDocumentMetadata.empty();
        }
        try {
            var tree = MAPPER.readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new SQLException("解析文档元信息 JSON 必须是对象");
            }
            ParsedDocumentMetadata value = MAPPER.treeToValue(tree, ParsedDocumentMetadata.class);
            return value == null ? ParsedDocumentMetadata.empty() : value;
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SQLException("解析文档元信息 JSON 失败", ex);
        }
    }
}
