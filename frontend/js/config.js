/*
 * Optional frontend configuration.
 *
 * Leave this file as it is for every normal setup. The app calls /api on the
 * origin it was loaded from, and both docker-compose (nginx) and the server
 * deployment (Caddy) proxy that to the backend — so there is no backend URL to
 * configure, and none baked into the page.
 *
 * Set it only when the page is served by something that does NOT proxy /api,
 * for example `python -m http.server 8081` during development or a file:// open.
 * Then the backend must also allow the cross-origin call (cors.allowed-origins,
 * "*" by default).
 */
// window.YCI_API_BASE = "http://localhost:8080";
