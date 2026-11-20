import Link from "next/link";
import { MaterialsPage } from "@/components/pages/MaterialsPage";
import { WorkspacePage } from "@/components/pages/WorkspacePage";

export const metadata = { title: "个人中心 · LearnLoop" };

export default async function Page({ searchParams }) {
    const materials = (await searchParams).tab === "materials";
    const navigation = (
        <nav className="personal-center-navigation" aria-label="个人中心">
            <Link href="/workspace" aria-current={!materials ? "page" : undefined}>学习工作台</Link>
            <Link href="/workspace?tab=materials" aria-current={materials ? "page" : undefined}>我的资料</Link>
        </nav>
    );
    return materials ? <MaterialsPage navigation={navigation} /> : <WorkspacePage navigation={navigation} />;
}
