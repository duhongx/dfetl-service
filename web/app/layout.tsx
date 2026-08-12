import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "东防数据采集系统",
  description: "医共体数据接入、同步、校验与运维管理平台",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
