import { redirect } from "next/navigation";

export default async function Page({ searchParams }) {
    // 保留已有阅读页和书签中的 manage 参数，直接打开个人中心的资料详情。
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(await searchParams)) {
        for (const item of Array.isArray(value) ? value : [value]) query.append(key, item);
    }
    query.set("tab", "materials");
    redirect(`/workspace?${query}`);
}
