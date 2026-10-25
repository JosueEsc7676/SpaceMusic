// ========== COLA DE REPRODUCCIÓN ==========

function toggleQueuePanel() {
    document.getElementById('queuePanel').classList.toggle('open');
}

function renderQueue() {
    const container = document.getElementById('queueList');
    if (!state.queue.length) {
        container.innerHTML = '<div class="queue-empty">No hay canciones en cola</div>';
        return;
    }
    container.innerHTML = state.queue.map((song, idx) => `
        <div class="queue-item ${state.usingQueue && state.queueIndex === idx ? 'active' : ''}" onclick="playFromQueue(${idx})">
            <img class="queue-item-img" src="${song.portadaUrl}" onerror="this.src='/api/portada/${encodeURIComponent(song.nombre)}'">
            <div class="queue-item-info">
                <div class="queue-item-title">${escapeHtml(song.nombre)}</div>
                <div class="queue-item-artist">${escapeHtml(song.artista)}</div>
            </div>
            <button class="remove-from-queue" onclick="event.stopPropagation(); removeFromQueue(${idx})"><i class="fas fa-trash"></i></button>
        </div>
    `).join('');
}

function addToQueue(song) {
    state.queue.push(song);
    renderQueue();
    showToast(`✅ Agregado a la cola: ${song.nombre}`);
}

function addMultipleToQueue(songs) {
    state.queue.push(...songs);
    renderQueue();
    showToast(`✅ ${songs.length} canciones agregadas a la cola`);
}

function removeFromQueue(index) {
    state.queue.splice(index, 1);
    if (state.usingQueue && state.queueIndex >= index) {
        state.queueIndex--;
        if (state.queueIndex >= state.queue.length) state.queueIndex = state.queue.length - 1;
    }
    if (state.queue.length === 0) {
        state.usingQueue = false;
        state.queueIndex = -1;
    }
    renderQueue();
    showToast('🗑 Eliminado de la cola');
}

function playFromQueue(index) {
    if (index >= 0 && index < state.queue.length) {
        state.usingQueue = true;
        state.queueIndex = index;
        playSongFromObject(state.queue[index]);
    }
}