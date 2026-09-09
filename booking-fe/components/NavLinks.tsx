"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { logOut } from "@/app/actions";

/*
 * The nav lives in the root layout (a server component), so the active-route
 * check is split out here - usePathname needs the client.
 */
const LINKS = [
  { href: "/", label: "Ottodot Trials", brand: true },
  { href: "/classes", label: "Classes" },
  { href: "/demo/race", label: "Race demo" },
  { href: "/concurrency", label: "Concurrency notes" },
] as const;

function isActive(pathname: string, href: string) {
  if (href === "/") return pathname === "/";
  return pathname === href || pathname.startsWith(`${href}/`);
}

export default function NavLinks() {
  const pathname = usePathname();

  return (
    <nav className="mx-auto flex max-w-3xl items-center gap-4 px-4 py-3 text-sm">
      {LINKS.map((link) => {
        const active = isActive(pathname, link.href);
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={active ? "page" : undefined}
            className={[
              active ? "font-semibold" : "font-normal",
              // The brand keeps full-contrast text even when inactive; only
              // the weight tracks the active route.
              active || "brand" in link ? "" : "muted",
              "hover:underline",
            ]
              .filter(Boolean)
              .join(" ")}
          >
            {link.label}
          </Link>
        );
      })}

      {/* Only ever rendered inside LoginGate's authenticated branch, so it
          needs no signed-in check of its own. */}
      <form action={logOut} className="ml-auto">
        <button type="submit" className="muted hover:underline">
          Sign out
        </button>
      </form>
    </nav>
  );
}
