import { createHash } from "node:crypto";
import path from "node:path";
import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export const TEST_PASSWORT = "Test-Passwort-fuer-Vitest-42";

export default defineConfig(async () => {
  const migrations = await readD1Migrations(path.join(import.meta.dirname, "migrations"));
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.test.jsonc" },
        miniflare: {
          bindings: {
            TEST_MIGRATIONS: migrations,
            ADMIN_PASSWORT_SHA256: createHash("sha256").update(TEST_PASSWORT).digest("hex"),
          },
        },
      }),
    ],
    test: {
      setupFiles: ["./test/setup.ts"],
    },
  };
});
