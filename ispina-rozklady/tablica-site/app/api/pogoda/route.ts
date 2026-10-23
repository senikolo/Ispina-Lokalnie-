// Extensionless PNG: Jetpack's image CDN does not transform this URL.
// Keep the existing renderer as the single implementation.
export { GET } from "../pogoda.png/route";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
