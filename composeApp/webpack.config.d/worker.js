// The SQLite worker is a local npm package (web-worker/). npm links it instead of copying it, so its own imports
// (@sqlite.org/sqlite-wasm) must also be looked up in the build's node_modules, not next to its source.
(function (config) {
    const path = require("path");
    config.resolve = config.resolve || {};
    config.resolve.modules = (config.resolve.modules || ["node_modules"]).concat([
        path.resolve(__dirname, "../../node_modules"),
    ]);
})(config);
