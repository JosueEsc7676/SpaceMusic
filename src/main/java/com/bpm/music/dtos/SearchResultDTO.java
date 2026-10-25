// dto/SearchResultDTO.java
package com.bpm.music.dto;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SearchResultDTO {
    private Long id;
    private String nombre;
    private String artista;
    private String album;
    private String tipo; // "cancion", "album", "artista"
    private int score;   // 🔥 ranking
}