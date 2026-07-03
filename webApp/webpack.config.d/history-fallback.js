// SPA routing (WEB_PWA_GAMEPLAN.md W1.2): deep links like /messages/abc must serve
// index.html on refresh instead of 404ing; the wasm bundle then applies the path.
config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;
