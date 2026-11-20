export function AuthLanding({ children }) {
    return (
        <main className="auth-screen">
            <div className="auth-feature">
                <span className="app-brand"><span aria-hidden="true">L</span> LearnLoop</span>
                <div>
                    <p className="journal-kicker">你的学习工作台</p>
                    <h2>资料、练习<br />与学习笔记</h2>
                    <p>导入资料，练习考点，把自己的问题与理解留在原文旁。</p>
                    <div className="auth-tags"><span>资料阅读</span><span>随手笔记</span><span>个性化练习</span></div>
                </div>
            </div>
            <section className="auth-form-panel">{children}</section>
        </main>
    );
}
