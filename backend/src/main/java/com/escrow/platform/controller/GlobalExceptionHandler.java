package com.escrow.platform.controller;

import com.escrow.platform.service.ChainReaderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ChainReaderService.ChainReadException.class)
    public ResponseEntity<Map<String, String>> handleChainReadException(ChainReaderService.ChainReadException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Failed to read on-chain data", "details", e.getMessage()));
    }
}
