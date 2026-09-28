// Cloudflare Pages: alle Anfragen unter /api/ laufen durch denselben Code wie im Worker.
import type { Env } from "../../src/hilfen";
import { verarbeiten } from "../../src/index";

export const onRequest: PagesFunction<Env> = (ctx) => verarbeiten(ctx.request, ctx.env);
