/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

// Runtime endpoint overrides for a deployed frontend. Leave an entry out to use the
// local development default. The keys are:
//   apiUrl       - the backend API base, including the /api suffix
//   keycloakUrl  - the Keycloak base URL (the cisys realm is appended)
//   realm        - the Keycloak realm name
//   clientId     - the public Keycloak client ID
window.cisysConfig = {};
