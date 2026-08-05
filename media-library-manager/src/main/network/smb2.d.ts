declare module "smb2" {
  interface SMB2Options {
    share: string;
    domain: string;
    username: string;
    password: string;
    port?: number;
    packetConcurrency?: number;
    autoCloseTimeout?: number;
  }

  class SMB2 {
    constructor(options: SMB2Options);
    readdir(path: string, callback: (err: Error | null, files: string[]) => void): void;
    exists(path: string, callback: (err: Error | null, exists: boolean) => void): void;
    readFile(
      filename: string,
      callback: (err: Error | null, data: Buffer) => void
    ): void;
    close(): void;
  }

  export = SMB2;
}
