// Trazos copiados de /js/icons.js de la web (24×24) para conservar la misma identidad visual.
export const paths = {
 "heart": "<path d=\"M12 20.5s-7.6-4.6-9.4-9.3C1.4 8 3.5 4.5 7 4.5c2 0 3.3 1 5 2.9 1.7-1.9 3-2.9 5-2.9 3.5 0 5.6 3.5 4.4 6.7-1.8 4.7-9.4 9.3-9.4 9.3z\"/>",
 "heartFill": "<path d=\"M12 20.5s-7.6-4.6-9.4-9.3C1.4 8 3.5 4.5 7 4.5c2 0 3.3 1 5 2.9 1.7-1.9 3-2.9 5-2.9 3.5 0 5.6 3.5 4.4 6.7-1.8 4.7-9.4 9.3-9.4 9.3z\" fill=\"currentColor\"/>",
 "play": "<path d=\"M7.5 4.6v14.8a1 1 0 0 0 1.5.86l12.2-7.4a1 1 0 0 0 0-1.72L9 3.74a1 1 0 0 0-1.5.86z\" fill=\"currentColor\" stroke=\"none\"/>",
 "pause": "<rect x=\"6\" y=\"4\" width=\"4\" height=\"16\" rx=\"1.2\" fill=\"currentColor\" stroke=\"none\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\" rx=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>",
 "rew": "<path d=\"M11.5 6.2v11.6a.7.7 0 0 1-1.1.6L2.6 12.6a.75.75 0 0 1 0-1.2l7.8-5.8a.7.7 0 0 1 1.1.6zM21.5 6.2v11.6a.7.7 0 0 1-1.1.6l-7.8-5.8a.75.75 0 0 1 0-1.2l7.8-5.8a.7.7 0 0 1 1.1.6z\" fill=\"currentColor\" stroke=\"none\"/>",
 "fwd": "<path d=\"M12.5 6.2v11.6a.7.7 0 0 0 1.1.6l7.8-5.8a.75.75 0 0 0 0-1.2l-7.8-5.8a.7.7 0 0 0-1.1.6zM2.5 6.2v11.6a.7.7 0 0 0 1.1.6l7.8-5.8a.75.75 0 0 0 0-1.2L3.6 5.6a.7.7 0 0 0-1.1.6z\" fill=\"currentColor\" stroke=\"none\"/>",
 "quote": "<path d=\"M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-8l-5 4v-4H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z\" fill=\"currentColor\" stroke=\"none\"/><path d=\"M8.2 12.8c1.3-.3 2-1.2 2-2.6V8.5H7.6v2.4h1.2c0 .7-.3 1.1-.9 1.3zM14 12.8c1.3-.3 2-1.2 2-2.6V8.5h-2.6v2.4h1.2c0 .7-.3 1.1-.9 1.3z\" fill=\"#000\" stroke=\"none\" opacity=\".55\"/>",
 "shuffle": "<path d=\"M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5\"/>",
 "repeat": "<path d=\"m17 2 4 4-4 4\"/><path d=\"M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4\"/><path d=\"M21 13v2a3 3 0 0 1-3 3H3\"/>",
 "queue": "<path d=\"M3 6h13M3 12h13M3 18h8\"/><path d=\"M17 15.5v5.5M17 21l4-2.75L17 15.5\" fill=\"currentColor\"/>",
 "more": "<circle cx=\"5\" cy=\"12\" r=\"1.6\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"12\" cy=\"12\" r=\"1.6\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"19\" cy=\"12\" r=\"1.6\" fill=\"currentColor\" stroke=\"none\"/>",
 "close": "<path d=\"M18 6 6 18M6 6l12 12\"/>",
 "down": "<path d=\"m6 9 6 6 6-6\"/>",
 "up": "<path d=\"m18 15-6-6-6 6\"/>",
 "radio": "<circle cx=\"12\" cy=\"12\" r=\"2\"/><path d=\"M16.2 7.8a6 6 0 0 1 0 8.4M7.8 16.2a6 6 0 0 1 0-8.4M19.1 4.9a10 10 0 0 1 0 14.2M4.9 19.1a10 10 0 0 1 0-14.2\"/>",
 "moon": "<path d=\"M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z\"/>",
 "list": "<path d=\"M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01\"/>",
 "drag": "<path d=\"M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01\" stroke-width=\"3\"/>",
 "send": "<path d=\"m22 2-11 11M22 2l-7 20-4-9-9-4z\"/>",
 "user": "<circle cx=\"12\" cy=\"8\" r=\"4\"/><path d=\"M4 21a8 8 0 0 1 16 0\"/>",
 "disc": "<circle cx=\"12\" cy=\"12\" r=\"9.5\"/><circle cx=\"12\" cy=\"12\" r=\"2.5\"/>",
 "plusCircle": "<circle cx=\"12\" cy=\"12\" r=\"9.5\"/><path d=\"M12 8v8M8 12h8\"/>"
} as const;
export type IconName = keyof typeof paths;
