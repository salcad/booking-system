import type { Metadata } from "next";
import LoginGate from "@/components/LoginGate";
import NavLinks from "@/components/NavLinks";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Ottodot Trial Booking",
  description: "Trial class booking with seat-hold concurrency control",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <LoginGate>
          <header className="border-b" style={{ borderColor: "var(--border)" }}>
            <NavLinks />
          </header>
          <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-8">
            {children}
          </main>
        </LoginGate>
      </body>
    </html>
  );
}
