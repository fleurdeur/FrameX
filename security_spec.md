# Security Specification — FramePollution

## 1. Data Invariants
1. **Strict Multi-Tenant Isolation:** Every project in `/users/{userId}/projects/{projectId}` and preset in `/users/{userId}/presets/{presetId}` belongs exclusively to `userId == request.auth.uid`. Cross-user reads, lists, writes, updates, and deletes are strictly forbidden.
2. **Unauthenticated Rejection:** All operations (`get`, `list`, `create`, `update`, `delete`) require `request.auth != null`.
3. **Immutable Ownership & Temporal Anchors:** On `update`, `userId` and `createdAt` cannot be modified (`incoming().userId == existing().userId && incoming().createdAt == existing().createdAt`).
4. **Server Timestamp Boundedness:** On `create` and `update`, `createdAt` (create) and `updatedAt` (create & update) must satisfy `is timestamp && <= request.time`.
5. **Bounded Dimensions & Payloads:**
   - `projectId` and `presetId` must match `^[a-zA-Z0-9_\-]+$` with length <= 128.
   - `width` and `height` must be integers between `64` and `7680` (up to 8K).
   - `fps` must be an integer between `1` and `240`.
   - `durationMs` must be an integer between `100` and `3600000` (up to 1 hour).
   - `codec` must be strictly `'H.264'` or `'H.265'`.
   - `layersPayload` <= 500,000 chars; `beatMarkersPayload` <= 100,000 chars; `effectChainPayload` <= 100,000 chars.
6. **Anti-Shadow-Update Key Whitelisting:** `create` enforces `hasAll` + `hasOnly` for exact schema keys; `update` enforces `affectedKeys().hasOnly(...)` wrapped inside `isValidMotionProject` / `isValidCustomEffectPreset`.

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated List Scraping:** Unauthenticated client calls `list` on `/users/alice_123/projects`. -> Rejected (`request.auth != null`).
2. **Cross-User Project Theft:** `bob_456` calls `get` on `/users/alice_123/projects/proj_1`. -> Rejected (`request.auth.uid == userId`).
3. **Identity Spoofing on Create:** `alice_123` writes to `/users/alice_123/projects/proj_1` with `{"userId": "bob_456", ...}`. -> Rejected (`incoming().userId == request.auth.uid`).
4. **Shadow Ghost Field Injection on Create:** `alice_123` creates project with extra key `"isAdmin": true`. -> Rejected (`incoming().keys().hasOnly(...)`).
5. **Shadow Ghost Field Injection on Update:** `alice_123` updates project adding `"proUnlocked": true`. -> Rejected (`affectedKeys().hasOnly(...)`).
6. **Immutable `createdAt` Tampering:** `alice_123` updates `createdAt` to a newer timestamp. -> Rejected (`incoming().createdAt == existing().createdAt`).
7. **Immutable `userId` Transfer:** `alice_123` updates `userId` to `bob_456`. -> Rejected (`incoming().userId == existing().userId` and not in `affectedKeys`).
8. **Future Timestamp Spoofing:** `alice_123` sets `updatedAt` to year 2099 (`request.time + duration.value(1, 'd')`). -> Rejected (`incoming().updatedAt <= request.time`).
9. **Resolution Overflow / Zero-Division Poisoning:** `alice_123` sets `width: 0` or `height: 9999999`. -> Rejected (`width >= 64 && width <= 7680`).
10. **Invalid Codec Enum Injection:** `alice_123` sets `codec: "AV1_MALICIOUS"`. -> Rejected (`codec in ['H.264', 'H.265']`).
11. **ID Poisoning Attack:** `alice_123` creates document with ID containing spaces/slashes or >128 chars. -> Rejected (`isValidId(projectId)`).
12. **Denial-of-Wallet Oversized String:** `alice_123` writes a 900KB `name` string. -> Rejected (`data.name.size() <= 120`).
