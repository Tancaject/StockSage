package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Locale;

/** 保留旧 task ID/提交键，按原请求重建跨会话分组；算法固定在迁移内，不依赖未来业务代码。 */
public class V12__research_run_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE research_tasks ADD COLUMN request_fingerprint VARCHAR(191)");
            statement.execute("ALTER TABLE research_tasks ADD COLUMN previous_task_id BIGINT");
        }
        ObjectMapper mapper = new ObjectMapper();
        try (var select = connection.prepareStatement(
                "SELECT id,user_id,ticker,payload_json,idempotency_key FROM research_tasks ORDER BY id");
             var rows = select.executeQuery();
             var update = connection.prepareStatement(
                     "UPDATE research_tasks SET request_fingerprint=? WHERE id=?")) {
            while (rows.next()) {
                String fingerprint = rows.getString("idempotency_key");
                JsonNode payload = mapper.readTree(rows.getString("payload_json"));
                if (payload != null && payload.path("query").isTextual()) {
                    String ticker = rows.getString("ticker").trim().toUpperCase(Locale.ROOT)
                            .replaceAll("[^A-Z0-9.]", "");
                    if (ticker.isBlank()) ticker = "UNKNOWN";
                    String query = payload.path("query").textValue().trim().toLowerCase(Locale.ROOT)
                            .replaceAll("\\s+", " ");
                    String input = String.join("|", rows.getString("user_id").trim(), ticker, query);
                    fingerprint = "deep-request:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
                }
                update.setString(1, fingerprint);
                update.setLong(2, rows.getLong("id"));
                update.executeUpdate();
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE research_tasks MODIFY COLUMN request_fingerprint VARCHAR(191) NOT NULL");
            statement.execute("CREATE INDEX idx_research_task_request ON research_tasks (user_id,request_fingerprint,id)");
        }
    }
}
