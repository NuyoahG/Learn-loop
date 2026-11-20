import { Suspense } from "react";
import { HistoryPage } from "@/components/pages/HistoryPage";

export const metadata = { title: "LearnLoop 答题记录" };

export default function Page() {
    return <Suspense fallback={<p role="status">正在打开答题记录…</p>}><HistoryPage /></Suspense>;
}
