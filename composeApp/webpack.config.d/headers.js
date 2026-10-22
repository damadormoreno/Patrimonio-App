// SQLite WASM keeps the database in the Origin Private File System through SharedArrayBuffer, which browsers only
// allow on a cross-origin isolated page. The deployed site sends the same two headers (firebase.json).
(function (config) {
    if (!config.devServer) return;
    config.devServer.headers = [
        { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
        { key: "Cross-Origin-Embedder-Policy", value: "require-corp" },
    ];
})(config);
