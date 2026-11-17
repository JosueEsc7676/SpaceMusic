package com.bpm.music.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Convertidor de JPA: el texto se cifra al guardarse y se descifra al leerse (ver {@link CifradoMensajes}). */
@Converter
public class TextoCifrado implements AttributeConverter<String, String> {
    @Override
    public String convertToDatabaseColumn(String texto) {
        return texto == null ? null : CifradoMensajes.instancia().cifrar(texto);
    }

    @Override
    public String convertToEntityAttribute(String guardado) {
        return guardado == null ? null : CifradoMensajes.instancia().descifrar(guardado);
    }
}
