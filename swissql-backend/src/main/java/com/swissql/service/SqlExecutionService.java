package com.swissql.service;

import com.swissql.api.ExecuteResponse;
import com.swissql.api.SqlExecuteRequest;
import com.swissql.model.ConnectionProfile;
import com.swissql.util.JdbcJsonSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SqlExecutionService {
    private static final Logger auditLog = LoggerFactory.getLogger("com.swissql.audit");

    private final ConnectionProfileService profileService;
    private final ConnectionPoolService poolService;

    public SqlExecutionService(ConnectionProfileService profileService, ConnectionPoolService poolService) {
        this.profileService = profileService;
        this.poolService = poolService;
    }

    public ExecuteResponse execute(SqlExecuteRequest request) {
        // Safety check fires before profile resolution — audit as "blocked" if rejected.
        try {
            SqlSafetyValidator.validate(request.getSql(), request.isAllowWrite());
        } catch (CoreApiException e) {
            auditLog.warn("profile_id={} db_type=unknown allow_write={} outcome=blocked duration_ms=0 trace_id={} sql={}",
                    request.getProfileId(), request.isAllowWrite(),
                    MDC.get("trace_id"), request.getSql());
            throw e;
        }

        ConnectionProfile profile = profileService.getRequired(request.getProfileId());
        if (!profile.isEnabled()) {
            throw new CoreApiException("CONNECTION_DISABLED", HttpStatus.BAD_REQUEST, "Connection profile is disabled");
        }

        long startTime = System.currentTimeMillis();
        try (Connection connection = poolService.getConnection(profile);
             Statement statement = connection.createStatement()) {
            applyOptions(statement, request.getOptions());
            boolean isResultSet = statement.execute(request.getSql());
            long duration = System.currentTimeMillis() - startTime;

            ExecuteResponse response = new ExecuteResponse();
            ExecuteResponse.Metadata metadata = baseMetadata(profile, duration);
            response.setMetadata(metadata);
            if (isResultSet) {
                response.setType("tabular");
                try (ResultSet resultSet = statement.getResultSet()) {
                    processResultSet(resultSet, response, request.getOptions().getLimit());
                }
            } else {
                response.setType("update_count");
                int updateCount = statement.getUpdateCount();
                metadata.setRowsAffected(Math.max(updateCount, 0));
                ExecuteResponse.DataContent data = new ExecuteResponse.DataContent();
                data.setColumns(List.of());
                data.setRows(List.of());
                response.setData(data);
            }
            response.setMetadata(metadata);

            auditLog.info("profile_id={} db_type={} allow_write={} outcome=success duration_ms={} rows={} rows_affected={} truncated={} trace_id={} sql={}",
                    profile.getProfileId(), profile.getDbType(), request.isAllowWrite(),
                    duration, metadata.getRowsReturned(), metadata.getRowsAffected(),
                    metadata.isTruncated(), MDC.get("trace_id"), request.getSql());

            return response;
        } catch (SQLTimeoutException e) {
            long duration = System.currentTimeMillis() - startTime;
            auditLog.warn("profile_id={} db_type={} allow_write={} outcome=timeout duration_ms={} error={} trace_id={} sql={}",
                    profile.getProfileId(), profile.getDbType(), request.isAllowWrite(),
                    duration, e.getMessage(), MDC.get("trace_id"), request.getSql());
            throw new CoreApiException("SQL_TIMEOUT", HttpStatus.REQUEST_TIMEOUT, "SQL execution timed out", e.getMessage());
        } catch (CoreApiException e) {
            throw e;
        } catch (SQLException e) {
            long duration = System.currentTimeMillis() - startTime;
            auditLog.warn("profile_id={} db_type={} allow_write={} outcome=error duration_ms={} error={} trace_id={} sql={}",
                    profile.getProfileId(), profile.getDbType(), request.isAllowWrite(),
                    duration, e.getMessage(), MDC.get("trace_id"), request.getSql());
            throw new CoreApiException("SQL_EXECUTION_ERROR", HttpStatus.BAD_REQUEST, "SQL execution failed", e.getMessage());
        }
    }

    private void applyOptions(Statement statement, SqlExecuteRequest.Options options) throws SQLException {
        if (options == null) {
            return;
        }
        if (options.getTimeoutMs() > 0) {
            statement.setQueryTimeout(Math.max(1, options.getTimeoutMs() / 1000));
        }
        if (options.getFetchSize() > 0) {
            statement.setFetchSize(options.getFetchSize());
        }
    }

    private ExecuteResponse.Metadata baseMetadata(ConnectionProfile profile, long durationMs) {
        ExecuteResponse.Metadata metadata = new ExecuteResponse.Metadata();
        metadata.setProfileId(profile.getProfileId());
        metadata.setDbType(profile.getDbType());
        metadata.setDurationMs(durationMs);
        return metadata;
    }

    private void processResultSet(ResultSet rs, ExecuteResponse response, int limit) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        int columnCount = rsmd.getColumnCount();

        List<ExecuteResponse.ColumnDefinition> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            ExecuteResponse.ColumnDefinition col = new ExecuteResponse.ColumnDefinition();
            col.setName(rsmd.getColumnName(i));
            col.setType(rsmd.getColumnTypeName(i));
            columns.add(col);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int count = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (limit > 0 && count >= limit) {
                truncated = true;
                break;
            }

            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(rsmd.getColumnName(i), JdbcJsonSafe.readJsonSafeValue(rs, i));
            }
            rows.add(row);
            count++;
        }

        ExecuteResponse.DataContent data = new ExecuteResponse.DataContent();
        data.setColumns(columns);
        data.setRows(rows);
        response.setData(data);

        response.getMetadata().setRowsReturned(count);
        response.getMetadata().setRowsAffected(0);
        response.getMetadata().setTruncated(truncated);
    }
}
