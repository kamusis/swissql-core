package com.swissql.controller;

import com.swissql.api.ExecuteResponse;
import com.swissql.api.SqlExecuteRequest;
import com.swissql.service.SqlExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/sql")
public class SqlController {
    private final SqlExecutionService sqlExecutionService;

    public SqlController(SqlExecutionService sqlExecutionService) {
        this.sqlExecutionService = sqlExecutionService;
    }

    @PostMapping("/execute")
    public ResponseEntity<ExecuteResponse> execute(@Valid @RequestBody SqlExecuteRequest request) {
        return ResponseEntity.ok(sqlExecutionService.execute(request));
    }
}
