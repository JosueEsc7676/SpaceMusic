/* =====================================================
   debug_flac.js — Diagnóstico FLAC
   Agregar TEMPORALMENTE en index.html:
   <script src="/js/debug_flac.js"></script>
   (después de general.js, antes de uiux.js)

   Abre la consola del navegador (F12) y reproduce
   una canción FLAC. Verás exactamente qué falla.
   ===================================================== */

(function() {
    const audio = document.getElementById('audioElement');
    if (!audio) { console.error('❌ No se encontró #audioElement'); return; }

    console.log('✅ debug_flac.js cargado');
    console.log('🎵 Navegador:', navigator.userAgent);

    // ── Soporte de FLAC en este navegador ──
    const canFlac  = audio.canPlayType('audio/flac');
    const canMp3   = audio.canPlayType('audio/mpeg');
    const canOgg   = audio.canPlayType('audio/ogg');
    console.log('📋 Soporte audio:');
    console.log('   FLAC  (audio/flac):', canFlac  || 'NO');
    console.log('   MP3   (audio/mpeg):', canMp3   || 'NO');
    console.log('   OGG   (audio/ogg): ', canOgg   || 'NO');

    if (!canFlac) {
        console.warn('⚠️ Este navegador NO soporta FLAC nativamente.');
        console.warn('   Solución: usar Chrome 56+, Firefox 51+, Edge 79+ o Safari 11+');
    }

    // ── Interceptar playSongFromObject para ver qué canción se carga ──
    const origFn = window.playSongFromObject;
    window.playSongFromObject = function(song) {
        console.group(`🎵 Cargando: ${song.nombre}`);
        console.log('ID:', song.id);
        console.log('Archivo:', song.rutaArchivo);
        console.log('URL audio:', `/api/canciones/${song.id}/audio`);
        const ext = song.rutaArchivo?.split('.').pop()?.toLowerCase();
        console.log('Extensión:', ext);
        console.log('¿Es FLAC?:', ext === 'flac');
        console.groupEnd();

        origFn(song);
    };

    // ── Escuchar TODOS los eventos del audio ──
    [
        'loadstart','durationchange','loadedmetadata','loadeddata',
        'progress','canplay','canplaythrough','playing',
        'waiting','stalled','suspend','abort','error','emptied'
    ].forEach(evt => {
        audio.addEventListener(evt, (e) => {
            if (evt === 'error') {
                const err = audio.error;
                console.error(`❌ audio [${evt}]`, {
                    code: err?.code,
                    message: err?.message,
                    src: audio.currentSrc,
                    networkState: audio.networkState,
                    readyState: audio.readyState
                });
                // Códigos de error
                const codes = {
                    1: 'MEDIA_ERR_ABORTED',
                    2: 'MEDIA_ERR_NETWORK',
                    3: 'MEDIA_ERR_DECODE',
                    4: 'MEDIA_ERR_SRC_NOT_SUPPORTED'
                };
                console.error('   Tipo de error:', codes[err?.code] || 'Desconocido');
            } else if (evt === 'progress') {
                // Solo mostrar progress ocasionalmente
                if (Math.random() < 0.1) {
                    const loaded = audio.buffered.length > 0
                        ? Math.round(audio.buffered.end(0)) : 0;
                    console.log(`📶 audio [progress] buffered: ${loaded}s / ${Math.round(audio.duration || 0)}s`);
                }
            } else {
                console.log(`🔊 audio [${evt}]`, {
                    readyState: audio.readyState,
                    networkState: audio.networkState,
                    currentSrc: audio.currentSrc?.slice(-50),
                    duration: audio.duration
                });
            }
        });
    });

    // ── Verificar el endpoint de audio directamente ──
    window.testFlacEndpoint = async function(cancionId) {
        console.group(`🧪 Probando endpoint /api/canciones/${cancionId}/audio`);
        try {
            const res = await fetch(`/api/canciones/${cancionId}/audio`, {
                headers: { 'Range': 'bytes=0-65535' }
            });
            console.log('Status:', res.status, res.statusText);
            console.log('Headers:', {
                contentType:   res.headers.get('content-type'),
                contentLength: res.headers.get('content-length'),
                contentRange:  res.headers.get('content-range'),
                acceptRanges:  res.headers.get('accept-ranges')
            });
            if (res.status === 206) {
                console.log('✅ Range requests funcionando (206 Partial Content)');
            } else if (res.status === 200) {
                console.warn('⚠️ El servidor devuelve 200 en vez de 206 — Range requests no activos');
            } else {
                console.error('❌ Error del servidor:', res.status);
            }
        } catch(e) {
            console.error('❌ Error de red:', e.message);
        }
        console.groupEnd();
    };

    // ── Spring Boot: verificar límite de tamaño ──
    window.checkSpringConfig = async function() {
        console.group('🔧 Verificación de configuración Spring Boot');
        console.log('Si el FLAC no carga, verifica en application.properties:');
        console.log('  spring.servlet.multipart.max-file-size=500MB');
        console.log('  spring.servlet.multipart.max-request-size=500MB');
        console.log('  server.tomcat.max-swallow-size=-1');
        console.log('  server.tomcat.max-http-response-header-size=65536');
        console.groupEnd();
    };

    console.log('');
    console.log('📌 Comandos disponibles en consola:');
    console.log('   testFlacEndpoint(ID)  — prueba el endpoint con un ID de canción FLAC');
    console.log('   checkSpringConfig()   — muestra config necesaria de Spring Boot');
    console.log('');
    console.log('👉 Pasos: reproduce una canción FLAC y mira los eventos arriba.');
    console.log('   Si ves MEDIA_ERR_SRC_NOT_SUPPORTED = el navegador no soporta FLAC');
    console.log('   Si ves MEDIA_ERR_NETWORK = problema en el servidor/endpoint');
    console.log('   Si ves MEDIA_ERR_DECODE = archivo FLAC corrupto');
    console.log('   Si no ves nada = player.js no está llamando a playSongFromObject');
})();