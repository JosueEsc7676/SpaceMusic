// ========== ESTADO GLOBAL ==========

let cache = {
    todasCanciones: null,
    favoritos: null,
    top: null,
    albumes: null,
    playlists: null,
    letras: new Map(),
    albumesDetalle: new Map()
};

let state = {
    currentSong: null,
    currentIndex: -1,
    allSongs: [],
    playlists: [],
    repeatMode: 0,
    shuffleMode: false,
    shuffledSongs: [],
    queue: [],
    queueIndex: -1,
    usingQueue: false,
    currentFilter: 'all',
    currentSearchResults: null,
    currentSearchQuery: ''
};

let searchTimeout = null;
const audio = document.getElementById('audioElement');