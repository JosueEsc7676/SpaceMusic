//package com.bpm.music.controllers;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.core.io.Resource;
//import org.springframework.core.io.UrlResource;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.nio.file.Path;
//import java.nio.file.Paths;
//
//@RestController
//@RequestMapping("/music")
//public class MusicController {
//
//    @Value("${music.path}")
//    private String ruta;
//
//    @GetMapping("/{filename:.+}")
//    public ResponseEntity<Resource> getMusic(@PathVariable String filename) {
//        try {
//            Path filePath = Paths.get(ruta).resolve(filename).normalize();
//            Resource resource = new UrlResource(filePath.toUri());
//
//            if (!resource.exists()) {
//                return ResponseEntity.notFound().build();
//            }
//
//            return ResponseEntity.ok()
//                    .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
//                    .body(resource);
//
//        } catch (Exception e) {
//            return ResponseEntity.internalServerError().build();
//        }
//    }
//}