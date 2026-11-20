import { Suspense } from "react";
import { ReadingPage } from "@/components/pages/ReadingPage";

export const metadata = { title: "资料阅读 · LearnLoop" };

export default function Page() {
    return <Suspense fallback={<p>正在打开资料…</p>}><ReadingPage /></Suspense>;
}
