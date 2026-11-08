/*
 * Applies the cisys branding to the Keycloak login pages: the colors, the
 * display name as the title, and the logo on the left of the title.
 *
 * The branding last seen is applied at once from localStorage, so a custom
 * branding does not flash the defaults, and is then refreshed from the
 * public /api/branding/public endpoint of the cisys backend.
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'cisys-branding';
  var DEFAULT_NAME = 'cisys';
  var HEX = /^#[0-9a-fA-F]{6}$/;
  // The cisys logo shipped with this theme, next to this script.
  var DEFAULT_LOGO = document.currentScript.src.replace(/js\/cisys\.js(\?.*)?$/, 'img/logo.svg');

  var titleText = null;
  var pageTitle = null;

  // The same rule as the cisys frontend: dark text on light colors, white otherwise.
  function contrast(hex) {
    var rgb = [1, 3, 5].map(function (i) {
      var c = parseInt(hex.slice(i, i + 2), 16) / 255;
      return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2] > 0.179 ? '#111111' : '#ffffff';
  }

  function valid(branding) {
    return !!branding && HEX.test(branding.headerColor) && HEX.test(branding.headingColor) && HEX.test(branding.buttonColor);
  }

  function applyColors(branding) {
    var style = document.documentElement.style;
    style.setProperty('--cisys-header', branding.headerColor);
    style.setProperty('--cisys-header-text', contrast(branding.headerColor));
    style.setProperty('--cisys-heading', branding.headingColor);
    style.setProperty('--cisys-button', branding.buttonColor);
    style.setProperty('--cisys-button-text', contrast(branding.buttonColor));
  }

  // The branding name, or "cisys" when none is set.
  function applyName(branding) {
    if (!titleText) {
      return;
    }
    var name = branding && typeof branding.name === 'string' && branding.name.trim() ? branding.name.trim() : DEFAULT_NAME;
    titleText.textContent = name;
    document.title = pageTitle.original ? pageTitle.text.split(pageTitle.original).join(name) : pageTitle.text;
  }

  // Puts the logo and the name side by side in the title above the login box.
  // The logo is the branding logo, or the cisys logo when none is set or cisys
  // cannot be reached.
  function setUpTitle(api) {
    var title = document.getElementById('kc-header-wrapper');
    if (!title) {
      return;
    }
    var original = title.textContent.trim();
    pageTitle = { text: document.title, original: original };

    var logo = document.createElement('img');
    logo.className = 'cisys-logo';
    logo.alt = '';
    logo.onerror = function () {
      logo.onerror = null;
      logo.src = DEFAULT_LOGO;
    };
    logo.src = api ? api + '/api/branding/logo' : DEFAULT_LOGO;

    titleText = document.createElement('span');
    titleText.textContent = original || DEFAULT_NAME;

    title.textContent = '';
    title.appendChild(logo);
    title.appendChild(titleText);
  }

  var stored = null;
  try {
    stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY));
  } catch (e) {
    // Nothing stored; the stylesheet defaults apply.
  }
  if (valid(stored)) {
    applyColors(stored);
  } else {
    stored = null;
  }

  function load() {
    var source = document.querySelector('[data-cisys-api]');
    var api = source ? source.getAttribute('data-cisys-api') : null;
    setUpTitle(api);
    applyName(stored);
    if (!api || !window.fetch) {
      return;
    }
    window.fetch(api + '/api/branding/public', { credentials: 'omit' })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (branding) {
        if (!valid(branding)) {
          return;
        }
        applyColors(branding);
        applyName(branding);
        try {
          window.localStorage.setItem(STORAGE_KEY, JSON.stringify(branding));
        } catch (e) {
          // Storage unavailable; the branding still applies to this page.
        }
      })
      .catch(function () {
        // The backend is not reachable; keep the current branding.
      });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load);
  } else {
    load();
  }
})();
