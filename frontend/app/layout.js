import "./styles/web-basics.css";
import "./styles/materials.css";
import "./styles/workspace.css";
import "./styles/questions.css";
import "./styles/journal.css";
import "./styles/interaction.css";

export const metadata = {
    title: "LearnLoop",
    description: "使用自有资料进行练习和复习"
};

export default function RootLayout({ children }) {
    return (
        <html lang="zh-CN">
            <body>{children}</body>
        </html>
    );
}
