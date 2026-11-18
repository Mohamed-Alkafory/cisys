/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

/** A rate-limited page is retried after Retry-After; enough retries for a 1 GiB log over 2 MiB pages. */
const MAX_RATE_LIMIT_RETRIES = 6;
const RETRY_FALLBACK_SECONDS = 5;

function sleep(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

/**
 * Reads the whole log in pages. The public endpoint caps each response, so keep requesting
 * from the next offset until one comes back empty. A rate-limited page is retried from the
 * same offset after Retry-After, so a large anonymous download completes across windows.
 */
export async function copyLog(api, logPath, sink, wait = sleep) {
  let offset = 0;
  let retries = 0;
  for (;;) {
    let response;
    try {
      response = await api(`${logPath}?offset=${offset}`, { raw: true });
    } catch (e) {
      if (e.status !== 429 || retries >= MAX_RATE_LIMIT_RETRIES) throw e;
      retries += 1;
      const seconds = Number(e.retryAfter);
      await wait(
        (Number.isFinite(seconds) && seconds > 0
          ? seconds
          : RETRY_FALLBACK_SECONDS) * 1000,
      );
      continue;
    }
    const reader = response.body.getReader();
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      received += value.length;
      await sink.write(value);
    }
    offset += received;
    if (received === 0) return;
    // A successful page proves the download is making progress; only bound consecutive 429s.
    retries = 0;
  }
}

/**
 * The suggested download name: project, branch and build identifier, for example
 * `cisys-main-1.log`. Characters that are unsafe in file names, such as the `/` in
 * `feature/x`, become `_`; missing parts are left out.
 */
export function logFileName(projectName, build) {
  return (
    [projectName, build.branch, build.number]
      .filter((part) => part != null && part !== "")
      .map((part) => String(part).replace(/[\\/:*?"<>|\s]+/g, "_"))
      .join("-") + ".log"
  );
}
