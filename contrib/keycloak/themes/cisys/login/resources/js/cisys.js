/*
 * Applies the cisys branding colors to the Keycloak login pages.
 *
 * The colors last seen are applied at once from localStorage, so a custom
 * branding does not flash the default green, and are then refreshed from
 * the public /api/branding/colors endpoint of the cisys backend.
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'cisys-branding-colors';
  var HEX = /^#[0-9a-fA-F]{6}$/;

  // The same rule as the cisys frontend: dark text on light colors, white otherwise.
  function contrast(hex) {
    var rgb = [1, 3, 5].map(function (i) {
      var c = parseInt(hex.slice(i, i + 2), 16) / 255;
      return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 0.179 ? '#111111' : '#ffffff';
  }

  function apply(colors) {
    if (!colors || !HEX.test(colors.headerColor) || !HEX.test(colors.headingColor) || !HEX.test(colors.buttonColor)) {
      return false;
    }
    var style = document.documentElement.style;
    style.setProperty('--cisys-header', colors.headerColor);
    style.setProperty('--cisys-header-text', contrast(colors.headerColor));
    style.setProperty('--cisys-heading', colors.headingColor);
    style.setProperty('--cisys-button', colors.buttonColor);
    style.setProperty('--cisys-button-text', contrast(colors.buttonColor));
    return true;
  }

  try {
    apply(JSON.parse(window.localStorage.getItem(STORAGE_KEY)));
  } catch (e) {
    // No stored colors; the stylesheet defaults apply.
  }

  function load() {
    var source = document.querySelector('[data-cisys-api]');
    if (!source || !window.fetch) {
      return;
    }
    window.fetch(source.getAttribute('data-cisys-api') + '/api/branding/colors', { credentials: 'omit' })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (colors) {
        if (apply(colors)) {
          try {
            window.localStorage.setItem(STORAGE_KEY, JSON.stringify(colors));
          } catch (e) {
            // Storage unavailable; the colors still apply to this page.
          }
        }
      })
      .catch(function () {
        // The backend is not reachable; keep the current colors.
      });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load);
  } else {
    load();
  }
})();
