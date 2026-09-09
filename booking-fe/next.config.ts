import type { NextConfig } from "next";

const API_BASE = process.env.API_BASE ?? "http://localhost:8074";

const nextConfig: NextConfig = {
  /**
   * Emit a self-contained server bundle so the Docker runtime stage can drop
   * node_modules entirely and ship only what the build actually traced.
   */
  output: "standalone",

  /**
   * Proxy /api/* to the Spring backend so the browser only ever talks to its
   * own origin. The alternative — client components posting straight to
   * :8074 — would mean relaxing CORS on the backend purely to accommodate the
   * demo UI, which is a worse trade than one rewrite rule.
   */
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${API_BASE}/api/:path*` }];
  },
};

export default nextConfig;
