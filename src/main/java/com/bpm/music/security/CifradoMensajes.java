package com.bpm.music.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado de los mensajes entre usuarios en la base de datos (AES-256-GCM).
 *
 * En la tabla «mensaje» el texto se guarda como {@code enc:v1:<base64(nonce de 12 bytes | cifrado + etiqueta)>}:
 * quien lea la base de datos (o una copia de seguridad) no ve el contenido, y cualquier alteración se detecta.
 * Cada mensaje usa un nonce aleatorio distinto.
 *
 * La clave (32 bytes) sale de la variable {@code MENSAJES_CLAVE} (base64) o del archivo {@code mensajes.clave-archivo}
 * (por defecto {@code ./config/clave-mensajes.key}); si no existe, se crea una nueva con permisos solo para el dueño.
 * Nunca va al repositorio. Sin ella los mensajes guardados no se pueden leer: hay que conservar una copia aparte.
 *
 * Los textos antiguos sin el prefijo se leen tal cual (y {@code MigracionCifrado} los cifra al arrancar).
 */
@Component
public class CifradoMensajes {

    private static final Logger log = LoggerFactory.getLogger(CifradoMensajes.class);
    static final String PREFIJO = "enc:v1:";
    private static final int NONCE = 12, ETIQUETA_BITS = 128;
    private static volatile CifradoMensajes instancia;

    private final SecretKey clave;
    private final SecureRandom azar = new SecureRandom();

    public CifradoMensajes(@Value("${MENSAJES_CLAVE:}") String claveEntorno,
                           @Value("${mensajes.clave-archivo:./config/clave-mensajes.key}") String archivo) {
        this.clave = new SecretKeySpec(cargarClave(claveEntorno, Path.of(archivo)), "AES");
        instancia = this;
    }

    /** Para el convertidor de JPA (lo crea Hibernate). */
    static CifradoMensajes instancia() {
        if (instancia == null) throw new IllegalStateException("El cifrado de mensajes no está listo");
        return instancia;
    }

    private static byte[] cargarClave(String entorno, Path archivo) {
        try {
            if (entorno != null && !entorno.isBlank()) return validar(Base64.getDecoder().decode(entorno.trim()));
            if (Files.isRegularFile(archivo)) return validar(Base64.getDecoder().decode(Files.readString(archivo).trim()));
            byte[] nueva = new byte[32];
            new SecureRandom().nextBytes(nueva);
            Path dir = archivo.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = archivo.resolveSibling(archivo.getFileName() + ".tmp");
            Files.writeString(tmp, Base64.getEncoder().encodeToString(nueva) + "\n");
            try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
            Files.move(tmp, archivo, StandardCopyOption.ATOMIC_MOVE);
            log.warn("Se creó la clave de cifrado de mensajes en {}. Guarda una copia segura aparte: sin ella no se pueden leer los mensajes.",
                    archivo.toAbsolutePath());
            return nueva;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cargar la clave de cifrado de mensajes: " + e.getMessage(), e);
        }
    }

    private static byte[] validar(byte[] k) {
        if (k.length != 32) throw new IllegalArgumentException("La clave de mensajes debe tener 32 bytes (256 bits)");
        return k;
    }

    public static boolean estaCifrado(String s) { return s != null && s.startsWith(PREFIJO); }

    public String cifrar(String texto) {
        if (texto == null || estaCifrado(texto)) return texto;
        try {
            byte[] nonce = new byte[NONCE];
            azar.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(ETIQUETA_BITS, nonce));
            c.updateAAD(PREFIJO.getBytes(StandardCharsets.US_ASCII));
            byte[] cifrado = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            return PREFIJO + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + cifrado.length).put(nonce).put(cifrado).array());
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el mensaje", e);
        }
    }

    private static final byte[] AAD_ARCHIVO = "adj:v1".getBytes(StandardCharsets.US_ASCII);

    /** Cifra un archivo (imagen o audio de un mensaje): nonce aleatorio + datos cifrados con AES-256-GCM. */
    public byte[] cifrarBytes(byte[] datos) {
        try {
            byte[] nonce = new byte[NONCE];
            azar.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(ETIQUETA_BITS, nonce));
            c.updateAAD(AAD_ARCHIVO);
            byte[] cifrado = c.doFinal(datos);
            return ByteBuffer.allocate(NONCE + cifrado.length).put(nonce).put(cifrado).array();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el archivo", e);
        }
    }

    public byte[] descifrarBytes(byte[] todo) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(ETIQUETA_BITS, todo, 0, NONCE));
            c.updateAAD(AAD_ARCHIVO);
            return c.doFinal(todo, NONCE, todo.length - NONCE);
        } catch (Exception e) {
            throw new IllegalStateException("El archivo no se pudo descifrar", e);
        }
    }

    public String descifrar(String guardado) {
        if (!estaCifrado(guardado)) return guardado; // mensaje anterior al cifrado (se cifra en la migración)
        try {
            byte[] todo = Base64.getDecoder().decode(guardado.substring(PREFIJO.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(ETIQUETA_BITS, todo, 0, NONCE));
            c.updateAAD(PREFIJO.getBytes(StandardCharsets.US_ASCII));
            return new String(c.doFinal(todo, NONCE, todo.length - NONCE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Un mensaje no se pudo descifrar (clave distinta o dato alterado)");
            return "[Mensaje no disponible]";
        }
    }
}
