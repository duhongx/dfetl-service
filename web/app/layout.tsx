import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "DFETL 医共体数据采集平台",
  description: "医疗机构数据接入、机构采集路由、同步任务、数据预检、同步后校验与运维管理平台",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
