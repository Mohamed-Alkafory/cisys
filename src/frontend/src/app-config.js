/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

/**
 * The frontend endpoints, resolved at runtime so a built bundle can be deployed to a
 * different host without a rebuild. The values come from `window.cisysConfig`, which
 * `/config.js` sets; only the string keys below are read and anything missing, empty or
 * not a string falls back to the local development defaults.
 */
const DEFAULTS = {
  apiUrl: "http://localhost:8080/api",
  keycloakUrl: "http://localhost:8180",
  realm: "cisys",
  clientId: "cisys-ui",
};

/**
 * Reads one key from the runtime config, falling back to the default when it is not a
 * non-empty string.
 */
function value(config, key) {
  const candidate = config[key];
  return typeof candidate === "string" && candidate !== ""
    ? candidate
    : DEFAULTS[key];
}

/**
 * Resolves the endpoints from `win.cisysConfig` (normally `window`), falling back per key
 * to the local development defaults.
 */
export function resolveConfig(win = window) {
  const raw = win ? win.cisysConfig : null;
  const config =
    raw && typeof raw === "object" && !Array.isArray(raw) ? raw : {};
  return {
    apiUrl: value(config, "apiUrl"),
    keycloakUrl: value(config, "keycloakUrl"),
    realm: value(config, "realm"),
    clientId: value(config, "clientId"),
  };
}
