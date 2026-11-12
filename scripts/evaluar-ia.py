#!/usr/bin/env python3
"""
Evalúa un modelo de Ollama clasificando canciones conocidas en estilos (muestra revisada a mano).
Mide aciertos y tiempo antes de permitir que la IA proponga organización en toda la biblioteca.

  python3 evaluar-ia.py [modelo]      (por defecto qwen3:0.6b; Ollama en 127.0.0.1:11434)
"""
import json
import sys
import time
import urllib.request

MODELO = sys.argv[1] if len(sys.argv) > 1 else "qwen3:0.6b"
ESTILOS = ["bachata", "reggaeton", "trap latino", "corridos tumbados", "regional mexicano", "salsa", "cumbia",
           "pop latino", "baladas", "hip hop", "trap", "r&b", "rock", "electronica", "pop", "cristiana", "infantil"]
# Muestra revisada: estilo indiscutible de cada canción
MUESTRA = [
    ("Romeo Santos", "Propuesta Indecente", "bachata"), ("Aventura", "Obsesión", "bachata"),
    ("Bad Bunny", "Tití Me Preguntó", "reggaeton"), ("Daddy Yankee", "Gasolina", "reggaeton"),
    ("Peso Pluma", "Ella Baila Sola", "corridos tumbados"), ("Natanael Cano", "Amor Tumbado", "corridos tumbados"),
    ("Vicente Fernández", "Volver Volver", "regional mexicano"), ("Banda MS", "Mi Razón de Ser", "regional mexicano"),
    ("Marc Anthony", "Vivir Mi Vida", "salsa"), ("Héctor Lavoe", "El Cantante", "salsa"),
    ("Eladio Carrión", "Mbappé", "trap latino"), ("Anuel AA", "Esclava", "trap latino"),
    ("Travis Scott", "SICKO MODE", "hip hop"), ("Kendrick Lamar", "HUMBLE.", "hip hop"),
    ("Daft Punk", "One More Time", "electronica"), ("Arctic Monkeys", "Do I Wanna Know?", "rock"),
    ("Taylor Swift", "Shake It Off", "pop"), ("Jesús Adrián Romero", "Te Daré Lo Mejor", "cristiana"),
    ("SZA", "Kill Bill", "r&b"), ("Los Ángeles Azules", "Nunca Es Suficiente", "cumbia"),
]
ESQUEMA = {"type": "object", "properties": {"estilo": {"type": "string", "enum": ESTILOS}}, "required": ["estilo"]}


def preguntar(artista, titulo):
    cuerpo = {
        "model": MODELO, "stream": False, "think": False, "format": ESQUEMA, "keep_alive": "2m",
        "options": {"temperature": 0, "num_thread": 2, "num_ctx": 512},
        "messages": [
            {"role": "system", "content": "Clasificas canciones en un estilo musical. Responde solo con el JSON pedido."},
            # Los datos van entre comillas como texto: no son instrucciones
            {"role": "user", "content": "Estilos posibles: " + ", ".join(ESTILOS) + ".\nCanción: " + json.dumps({"artista": artista, "titulo": titulo}, ensure_ascii=False)},
        ],
    }
    req = urllib.request.Request("http://127.0.0.1:11434/api/chat", data=json.dumps(cuerpo).encode(), headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=120) as r:
        res = json.load(r)
    return json.loads(res["message"]["content"]).get("estilo"), time.time() - t0


aciertos, tiempos, filas = 0, [], []
for artista, titulo, esperado in MUESTRA:
    try:
        obtenido, t = preguntar(artista, titulo)
    except Exception as e:
        obtenido, t = "ERROR " + type(e).__name__, 0
    ok = obtenido == esperado
    aciertos += ok
    tiempos.append(t)
    filas.append(f"{'✓' if ok else '✗'} {artista} – {titulo}: {obtenido} (esperado {esperado}) {t:.1f}s")
print("\n".join(filas))
tiempos_ok = sorted(x for x in tiempos if x > 0)
print(json.dumps({"modelo": MODELO, "muestra": len(MUESTRA), "aciertos": aciertos, "precision": round(aciertos / len(MUESTRA), 2),
                  "tiempo_p50_s": round(tiempos_ok[len(tiempos_ok) // 2], 1) if tiempos_ok else None,
                  "tiempo_max_s": round(max(tiempos_ok), 1) if tiempos_ok else None}, ensure_ascii=False))
# Descargar el modelo de memoria al terminar
urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:11434/api/generate", data=json.dumps({"model": MODELO, "keep_alive": 0}).encode(), headers={"Content-Type": "application/json"}), timeout=30).read()
