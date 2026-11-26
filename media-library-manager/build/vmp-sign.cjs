// electron-builder afterPack hook: VMP-sign the packaged Windows app so the Castlabs "Electron for
// Content Security" build will actually load its Widevine CDM when installed. Castlabs refuses to
// load Widevine in a *packaged* app that isn't Verified-Media-Path signed, which is why protected
// channels fail with Shaka error 6001 ("Widevine isn't available") in an unsigned build.
//
// Signing needs a free Castlabs EVS account, provided to CI as EVS_ACCOUNT_NAME / EVS_PASSWD. When
// those aren't set we skip signing (the build still succeeds, but DRM playback won't work) so the
// installer keeps building for anyone without the account.
const { spawnSync } = require("node:child_process");

exports.default = async function vmpSign(context) {
  if (context.electronPlatformName !== "win32") return;
  if (!process.env.EVS_ACCOUNT_NAME || !process.env.EVS_PASSWD) {
    console.warn(
      "[vmp-sign] EVS_ACCOUNT_NAME / EVS_PASSWD not set — skipping Widevine VMP signing. " +
        "Protected (DRM) channels won't play in this build; set the secrets to enable them."
    );
    return;
  }
  const dir = context.appOutDir;
  console.log(`[vmp-sign] VMP-signing ${dir} for Widevine (Castlabs EVS)…`);
  // --no-ask authenticates non-interactively from the EVS_ACCOUNT_NAME / EVS_PASSWD env vars.
  const res = spawnSync("python", ["-m", "castlabs_evs.vmp", "--no-ask", "sign-pkg", dir], {
    stdio: "inherit",
    env: process.env
  });
  if (res.error) throw res.error;
  if (res.status !== 0) throw new Error(`VMP signing failed (exit code ${res.status ?? "unknown"}).`);
};
