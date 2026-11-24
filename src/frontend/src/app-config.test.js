/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import { resolveConfig } from "./app-config";

const DEFAULTS = {
  apiUrl: "http://localhost:8080/api",
  keycloakUrl: "http://localhost:8180",
  realm: "cisys",
  clientId: "cisys-ui",
};

describe("resolveConfig", () => {
  it("uses the defaults when no runtime config is present", () => {
    expect(resolveConfig({})).toEqual(DEFAULTS);
    expect(resolveConfig({ cisysConfig: undefined })).toEqual(DEFAULTS);
  });

  it("reads every value from the runtime config", () => {
    const win = {
      cisysConfig: {
        apiUrl: "https://ci.example/api",
        keycloakUrl: "https://sso.example",
        realm: "corp",
        clientId: "corp-ui",
      },
    };
    expect(resolveConfig(win)).toEqual({
      apiUrl: "https://ci.example/api",
      keycloakUrl: "https://sso.example",
      realm: "corp",
      clientId: "corp-ui",
    });
  });

  it("falls back per key when only some values are set", () => {
    const win = { cisysConfig: { keycloakUrl: "https://sso.example" } };
    expect(resolveConfig(win)).toEqual({
      ...DEFAULTS,
      keycloakUrl: "https://sso.example",
    });
  });

  it("falls back for empty, non-string and non-object values", () => {
    const win = {
      cisysConfig: {
        apiUrl: "",
        keycloakUrl: 8180,
        realm: null,
        clientId: {},
      },
    };
    expect(resolveConfig(win)).toEqual(DEFAULTS);
    expect(resolveConfig({ cisysConfig: "nope" })).toEqual(DEFAULTS);
    expect(resolveConfig({ cisysConfig: 42 })).toEqual(DEFAULTS);
  });
});
