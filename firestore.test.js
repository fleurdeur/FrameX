const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const DATABASE_ID = "ai-studio-android-framepol-590f5abc-e593-4bb6-8295-1f0d39768b04";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function getDb(context) {
  return context.firestore();
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validProjectPayload(uid, serverTimestamp) {
  return {
    userId: uid,
    name: "Neon Synth Intro",
    aspectRatioPreset: "16:9",
    width: 1920,
    height: 1080,
    fps: 60,
    durationMs: 10000,
    codec: "H.264",
    bpm: 128,
    layersPayload: "[]",
    beatMarkersPayload: "[]",
    createdAt: serverTimestamp,
    updatedAt: serverTimestamp,
  };
}

test("1. Unauthenticated user cannot list or get projects", async () => {
  const unauthDb = getDb(testEnv.unauthenticatedContext());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("projects").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1").get());
});

test("2. Authenticated owner can create, get, list, update, and delete own project", async () => {
  const aliceCtx = testEnv.authenticatedContext(ALICE_UID);
  const aliceDb = getDb(aliceCtx);
  const ts = new Date(Date.now() - 1000);
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_1");

  await assertSucceeds(docRef.set(validProjectPayload(ALICE_UID, ts)));
  await assertSucceeds(docRef.get());
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects")
      .where("userId", "==", ALICE_UID)
      .get()
  );

  await assertSucceeds(
    docRef.update({
      name: "Neon Synth V2",
      fps: 120,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(docRef.delete());
});

test("3. Cross-user access is strictly rejected for get, list, update, delete", async () => {
  const ts = new Date(Date.now() - 2000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await getDb(ctx)
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_alice")
      .set(validProjectPayload(ALICE_UID, ts));
  });

  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const aliceDocFromBob = bobDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_alice");

  await assertFails(aliceDocFromBob.get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("projects").where("userId", "==", ALICE_UID).get());
  await assertFails(aliceDocFromBob.update({ name: "Hacked", updatedAt: new Date() }));
  await assertFails(aliceDocFromBob.delete());
});

test("4. Identity spoofing on create is rejected", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const ts = new Date(Date.now() - 1000);
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_spoof")
      .set(validProjectPayload(BOB_UID, ts))
  );
});

test("5. Shadow update with ghost field is rejected on create and update", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const ts = new Date(Date.now() - 1000);
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_ghost");

  await assertFails(
    docRef.set({
      ...validProjectPayload(ALICE_UID, ts),
      isProUnlocked: true,
    })
  );

  await assertSucceeds(docRef.set(validProjectPayload(ALICE_UID, ts)));
  await assertFails(
    docRef.update({
      name: "Valid Name",
      ghostField: "injected",
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("6. Immutable createdAt and userId tampering on update is rejected", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const ts = new Date(Date.now() - 5000);
  const docRef = aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_imm");
  await assertSucceeds(docRef.set(validProjectPayload(ALICE_UID, ts)));

  await assertFails(
    docRef.update({
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );
});

test("7. Invalid resolution, FPS, or codec bounds are rejected", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const ts = new Date(Date.now() - 1000);

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_bad_res")
      .set({ ...validProjectPayload(ALICE_UID, ts), width: 0 })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_bad_fps")
      .set({ ...validProjectPayload(ALICE_UID, ts), fps: 999 })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("projects").doc("proj_bad_codec")
      .set({ ...validProjectPayload(ALICE_UID, ts), codec: "VP8_INVALID" })
  );
});

test("8. CustomEffectPreset CRUD and cross-user isolation", async () => {
  const aliceDb = getDb(testEnv.authenticatedContext(ALICE_UID));
  const bobDb = getDb(testEnv.authenticatedContext(BOB_UID));
  const ts = new Date(Date.now() - 1000);
  const presetRef = aliceDb.collection("users").doc(ALICE_UID).collection("presets").doc("preset_glow");

  await assertSucceeds(
    presetRef.set({
      userId: ALICE_UID,
      name: "Sapphire Glow + Shake",
      category: "Desktop Suite Homages",
      effectChainPayload: "[{\"effectId\":\"S_Glow\"}]",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("presets")
      .where("userId", "==", ALICE_UID)
      .get()
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("presets").doc("preset_glow").get());
});
