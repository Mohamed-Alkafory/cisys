/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import {
  pullRequestKind,
  pullRequestLabel,
  triggerLabel,
} from "./build-format";

describe("build-format", () => {
  it("labels a pull request or merge request by provider", () => {
    expect(pullRequestLabel("GITHUB", 12)).toBe("PR #12");
    expect(pullRequestLabel("GITLAB", 12)).toBe("MR !12");
    expect(pullRequestLabel("GITHUB", null)).toBeNull();
  });

  it("names the review request by provider", () => {
    expect(pullRequestKind("GITHUB")).toBe("Pull request");
    expect(pullRequestKind("GITLAB")).toBe("Merge request");
    expect(pullRequestKind(null)).toBe("Pull request");
  });

  it("labels the trigger, including merge requests", () => {
    expect(triggerLabel("MANUAL")).toBe("Manual");
    expect(triggerLabel("POLL")).toBe("Poll");
    expect(triggerLabel("PULL_REQUEST", "GITHUB")).toBe("Pull request");
    expect(triggerLabel("PULL_REQUEST", "GITLAB")).toBe("Merge request");
  });
});
