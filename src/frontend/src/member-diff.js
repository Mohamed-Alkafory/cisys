/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

const key = (username) => username.toLowerCase();

/** Builds the desired team map from a server-style team list. */
export function toMap(members) {
  const map = new Map();
  for (const m of members ?? []) {
    if (m?.username)
      map.set(key(m.username), { username: m.username, role: m.role });
  }
  return map;
}

/** A fresh, independent copy of a member map. */
export function cloneMap(map) {
  return new Map([...map].map(([k, v]) => [k, { ...v }]));
}

/**
 * The operations needed to turn the original (server) state into the desired
 * (draft) state. Entries are returned in the order the API should receive them:
 * promotions to LEAD first, demotions to MEMBER last, removals at the end, so the
 * server's incremental last-LEAD check always evaluates a safe ordering.
 */
export function diffMembers(original, draft) {
  const adds = [];
  const changes = [];
  const removals = [];
  for (const [k, entry] of draft) {
    if (!original.has(k)) adds.push({ ...entry });
  }
  for (const [k, entry] of original) {
    if (!draft.has(k)) removals.push(entry.username);
    else if (draft.get(k).role !== entry.role)
      changes.push({ username: entry.username, role: draft.get(k).role });
  }
  const promotes = changes.filter((c) => c.role === "LEAD");
  const demotes = changes.filter((c) => c.role === "MEMBER");
  const sortedAdds = [...adds].sort((a, b) =>
    a.role === b.role ? 0 : a.role === "LEAD" ? -1 : 1,
  );
  const changed = adds.length + changes.length + removals.length > 0;
  return { adds: sortedAdds, promotes, demotes, removals, changed };
}
