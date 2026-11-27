// Records how the Castlabs Widevine component init went at startup, so the TV player can show it
// when a protected channel fails (6001). Set once in index.ts after components.whenReady().

let status = "Widevine init hasn't run yet.";

export function setWidevineStatus(s: string): void {
  status = s;
}

export function getWidevineStatus(): string {
  return status;
}
