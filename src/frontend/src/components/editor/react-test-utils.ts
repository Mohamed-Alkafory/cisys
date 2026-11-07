/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import * as React from "react";

import * as ReactTestUtils from "react-dom/test-utils";

/**
 * React 19 moved act from react-dom/test-utils to react
 * https://react.dev/blog/2024/04/25/react-19-upgrade-guide#removed-react-dom-test-utils
 */
export const act =
  "act" in React
    ? (React.act as typeof ReactTestUtils.act)
    : ReactTestUtils.act;
