"""无状态的 BKT 单步计算，以及 Java/Python 之间的严格版本契约。

Java 已完成鉴权、判题、有效观测过滤、幂等及事件排序，才调用本模块。
Python 只把一次合格的对/错观测转换成下一步概率：不接收用户身份、
不连接数据库、不持有跨请求的学生状态，也不在请求中重新拟合模型。

首版采用固定工程参数；概率尚未在人群数据上校准。适配器显式绑定
pyBKT 1.4.3 的 coef_ / fit_model 元数据结构，升级时必须重新验证。
"""

import math
from importlib.metadata import version
from typing import Annotated, Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field, StrictBool
from pyBKT.models import Model, Roster


MODEL_VERSION = "bkt-fixed-v1"
LIBRARY_VERSION = "1.4.3"
SKLEARN_VERSION = "1.7.2"
LEARN = 0.1
GUESS = 0.25
SLIP = 0.1
# 单次请求仅有一个“抽象考点”和一个临时学生；它们不包含任何真实身份。
SKILL = "internal.concept"
STUDENT = "internal.observation"

Probability = Annotated[float, Field(strict=True, ge=0, le=1, allow_inf_nan=False)]
EventToken = Annotated[str, Field(strict=True, min_length=1, max_length=64,
                                 pattern=r"^[A-Za-z0-9:_-]+$")]


class BktStepRequest(BaseModel):
    """只允许当前契约的五个字段，拒绝隐式转换与额外字段。

    priorMastery 是该事件发生前已提交的掌握状态，可以是 0 或 1，
    不能把 bool、数字字符串、NaN、Infinity 当成有效概率。
    correct 严格接收 JSON true/false；跳过、提示作答等应由 Java 直接
    排除，尤其不能把 pyBKT 的 -1 当作业务“跳过”。
    """

    model_config = ConfigDict(extra="forbid", strict=True)

    schemaVersion: Literal["1"]
    eventId: EventToken
    modelVersion: Literal["bkt-fixed-v1"]
    priorMastery: Probability
    correct: StrictBool


class BktStepResponse(BaseModel):
    """明确区分预测、观察后验和学习转移后的三个时间点。

    predictedCorrect：尚未观察本次对错时，预测本次答对的概率。
    posteriorMastery：仅用本次对错做 Bayes 更新，尚未加入学习转移。
    masteryProbability：真实 pyBKT 消费观测后的 pNext；Java 持久化
    此字段，下一次调用时将其作为 priorMastery 传入。
    """

    model_config = ConfigDict(extra="forbid", strict=True)

    schemaVersion: Literal["1"]
    eventId: EventToken
    modelVersion: Literal["bkt-fixed-v1"]
    priorMastery: Probability
    predictedCorrect: Probability
    posteriorMastery: Probability
    masteryProbability: Probability
    libraryVersion: Literal["1.4.3"]


def validate_library_versions() -> None:
    """启动时核对真实运行版本，防止未回归的依赖升级悄悄改变概率。

    pyBKT 1.4.3 导入时探测 scikit-learn 的内部 metrics API；准备阶段
    已验证 1.7.2，其他版本不得仅凭安装成功就当作兼容。
    """

    if version("pyBKT") != LIBRARY_VERSION or version("scikit-learn") != SKLEARN_VERSION:
        raise RuntimeError("BKT runtime versions do not match the verified adapter")


def _make_roster(prior: float) -> Roster:
    """为一次调用新建真实 pyBKT 实例，杜绝并发请求之间共享可变状态。

    Model 通常通过 fit() 生成 coef_ 和 fit_model；首版明确使用固定
    参数而不训练，所以同时填充 1.4.3 初始化 Roster 所需的两份元数据。
    这里只建立一名临时学生和一个考点，业务身份始终留在 Java。
    """

    model = Model(seed=42, num_fits=1, parallel=False)
    model.coef_ = {
        SKILL: {
            "prior": prior,
            "learns": np.array([LEARN]),
            "guesses": np.array([GUESS]),
            "slips": np.array([SLIP]),
            "forgets": np.array([0.0]),
        }
    }
    # pyBKT 的 pi_0 行顺序固定为“未掌握、已掌握”；普通 BKT 仅用
    # default 资源及 default 猜测/失误类别，不能遗漏这些名称映射。
    model.fit_model[SKILL].update({
        "pi_0": np.array([[1 - prior], [prior]]),
        "resource_names": {"default": 1},
        "gs_names": {"default": 1},
    })
    return Roster([STUDENT], [SKILL], model=model)


def calculate_bkt_step(request: BktStepRequest) -> BktStepResponse:
    """用真实库更新一条观测，并在返回前核对时间语义和有限数范围。

    事件标识只是请求/响应关联标识；本服务没有事件存储，因此重复调用
    会返回相同计算结果，不能替代 Java 数据库的幂等、排序或版本比较。
    学习转移 T>0 时，低先验下答错也可能让 pNext 上升；不能强行把
    结果改成“答错必下降”，否则不再是当前声明的 BKT 模型。
    """

    prior = request.priorMastery
    roster = _make_roster(prior)
    predicted = float(roster.get_correct_prob(SKILL, STUDENT))
    # Bayes 后验与学习转移后的状态分别暴露，避免前端误用“答对概率”。
    # 错误分支直接求 P(答错)，避免 prior=1 时用 1-.9 的浮点相减
    # 令 .1/(1-.9) 略大于 1，进而把合法边界误判为非法概率。
    predicted_wrong = prior * SLIP + (1 - prior) * (1 - GUESS)
    posterior = (prior * (1 - SLIP) / predicted if request.correct
                 else prior * SLIP / predicted_wrong)
    # 1.4.3 的反向软计数在先验恰为 1 时会对零概率路径算 0/0，
    # 上游随即用 nan_to_num 清理该统计；它不影响这里使用的前向预测。
    # 仅屏蔽该 invalid 警告，真实输出仍通过下面的有限数与数学核验。
    with np.errstate(invalid="ignore"):
        roster.update_state(SKILL, STUDENT, correct=int(request.correct))
    next_mastery = float(roster.get_mastery_prob(SKILL, STUDENT))
    expected_next = posterior + (1 - posterior) * LEARN
    if (not all(math.isfinite(value) and 0 <= value <= 1
                for value in (predicted, posterior, next_mastery))
            or not math.isclose(next_mastery, expected_next, abs_tol=1e-12, rel_tol=1e-12)):
        raise RuntimeError("BKT adapter produced an invalid or unexpected state")

    return BktStepResponse(
        schemaVersion=request.schemaVersion,
        eventId=request.eventId,
        modelVersion=request.modelVersion,
        priorMastery=prior,
        predictedCorrect=predicted,
        posteriorMastery=posterior,
        masteryProbability=next_mastery,
        libraryVersion=LIBRARY_VERSION,
    )
