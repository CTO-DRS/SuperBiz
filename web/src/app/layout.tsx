import type { Metadata, Viewport } from "next";
import { Cairo } from "next/font/google";
import "./globals.css";
import { Toaster } from "@/components/ui/toaster";

const cairo = Cairo({
  variable: "--font-cairo",
  subsets: ["arabic", "latin"],
});

export const metadata: Metadata = {
  title: "SuperBiz — بوابة الويب",
  description:
    "بوابة الويب الرسمية لمشروع SuperBiz: لوحة قيادة حية، نقطة بيع، فواتير بQR ضريبي، مخزون، ذمم، تقارير، وخارطة الطريق الذكية المتكاملة.",
  keywords: ["SuperBiz", "نقطة البيع", "فواتير", "ZATCA", "مخزون", "إدارة أعمال"],
  openGraph: {
    title: "SuperBiz — بوابة الويب",
    description: "منظومة إدارة الأعمال المتكاملة — نسخة الويب للمعاينة الحية",
    type: "website",
  },
};

export const viewport: Viewport = {
  themeColor: "#0B1026",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ar" dir="rtl" suppressHydrationWarning>
      <body className={`${cairo.variable} font-[family-name:var(--font-cairo)] antialiased`}>
        {children}
        <Toaster />
      </body>
    </html>
  );
}
