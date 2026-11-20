/** 老任务没有阶段前缀时仍可显示；提示来自本地映射，绝不展示模型原始错误。 */
export function generationFailureHint(code) {
    const stages = { PREPARE: "准备任务", CATALOG: "提炼考点", SELECT: "选择考点", QUESTIONS: "生成题目", PUBLISH: "校验并保存题目" };
    const stage = Object.keys(stages).find(key => typeof code === "string" && code.startsWith(key + "_"));
    const cause = stage ? code.slice(stage.length + 1) : code;
    const reasons = { MODEL_OUTPUT_INVALID: "模型结果未通过格式或来源校验", MODEL_TIMEOUT: "模型响应超时",
        MODEL_UPSTREAM_FAILED: "模型平台调用失败", GENERATION_UNAVAILABLE: "内部模型服务不可用",
        MODEL_KEY_REQUIRED: "请检查模型密钥配置", MODEL_RESPONSE_TOO_LARGE: "模型输出超过限制" };
    return `${stage ? stages[stage] + "阶段：" : ""}${reasons[cause] || "处理失败，请根据任务编号查看诊断记录"}`;
}
