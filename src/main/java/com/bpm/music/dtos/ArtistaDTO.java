package com.bpm.music.dtos;

public class ArtistaDTO {
    private String nombre;
    private int totalCanciones;
    private int totalAlbumes;
    private int totalReproducciones;
    private String portadaUrl; // portada de su canción más popular

    public ArtistaDTO() {}

    public ArtistaDTO(String nombre, int totalCanciones, int totalAlbumes,
                      int totalReproducciones, String portadaUrl) {
        this.nombre = nombre;
        this.totalCanciones = totalCanciones;
        this.totalAlbumes = totalAlbumes;
        this.totalReproducciones = totalReproducciones;
        this.portadaUrl = portadaUrl;
    }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public int getTotalCanciones() { return totalCanciones; }
    public void setTotalCanciones(int totalCanciones) { this.totalCanciones = totalCanciones; }

    public int getTotalAlbumes() { return totalAlbumes; }
    public void setTotalAlbumes(int totalAlbumes) { this.totalAlbumes = totalAlbumes; }

    public int getTotalReproducciones() { return totalReproducciones; }
    public void setTotalReproducciones(int totalReproducciones) { this.totalReproducciones = totalReproducciones; }

    public String getPortadaUrl() { return portadaUrl; }
    public void setPortadaUrl(String portadaUrl) { this.portadaUrl = portadaUrl; }
}
