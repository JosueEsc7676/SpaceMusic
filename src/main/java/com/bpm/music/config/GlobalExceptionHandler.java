package com.bpm.music.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

/** Convierte las excepciones en respuestas JSON claras: {"error": "..."} */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(NoSuchElementException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage() != null && !e.getMessage().startsWith("No value")
                ? e.getMessage() : "No encontrado");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return error(HttpStatus.valueOf(e.getStatusCode().value()),
                e.getReason() != null ? e.getReason() : "Error");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> argumento(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage() != null ? e.getMessage() : "Datos inválidos");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denegado(AccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "No tienes permiso para esto");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> parametros(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "Parámetros inválidos");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tamano(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo es demasiado grande");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> metodo(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> recurso(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "No encontrado");
    }

    /** El cliente cerró la conexión (p. ej. al saltar de canción): no es un error. */
    @ExceptionHandler({AsyncRequestNotUsableException.class})
    public void desconexion() {
        // nada
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> io(IOException e, HttpServletRequest req) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        if (msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("conexión")
                || msg.contains("established connection was aborted")) {
            return null; // el navegador canceló la descarga
        }
        log.error("Error de E/S en {}: {}", req.getRequestURI(), e.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error leyendo el archivo");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> general(Exception e, HttpServletRequest req) {
        log.error("Error inesperado en {}", req.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String mensaje) {
        return ResponseEntity.status(status).body(Map.of("error", mensaje));
    }
}
