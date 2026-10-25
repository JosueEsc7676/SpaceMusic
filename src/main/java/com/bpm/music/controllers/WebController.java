//// controllers/WebController.java
//package com.bpm.music.controllers;
//
//import com.bpm.music.services.CancionService;
//import com.bpm.music.services.PlaylistService;
//import org.springframework.stereotype.Controller;
//import org.springframework.ui.Model;
//import org.springframework.web.bind.annotation.*;
//
//@Controller
//public class WebController {
//
//    private final CancionService cancionService;
//    private final PlaylistService playlistService;
//
//    public WebController(CancionService cancionService, PlaylistService playlistService) {
//        this.cancionService = cancionService;
//        this.playlistService = playlistService;
//    }
//
//    @GetMapping("/")
//    public String index(Model model) {
//        model.addAttribute("topCanciones", cancionService.getTopReproducidas());
//        model.addAttribute("playlists", playlistService.listarTodas());
//        return "index";
//    }
//
//    @GetMapping("/playlist/{id}")
//    public String playlist(@PathVariable Long id, Model model) {
//        model.addAttribute("playlist", playlistService.getById(id));
//        return "playlist";
//    }
//}