// Cloudflare Pages: Android App Links (der QR-Link öffnet direkt die App)
import type { Env } from "../../src/hilfen";
import { assetlinks } from "../../src/index";

export const onRequest: PagesFunction<Env> = (ctx) => assetlinks(ctx.env);
