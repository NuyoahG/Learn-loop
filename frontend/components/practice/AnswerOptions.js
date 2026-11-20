"use client";

export function AnswerOptions({ question, name, selected, disabled, correctOption, onChange }) {
    return (
        <fieldset className="workspace-answer-options" disabled={disabled}>
            <legend className="visually-hidden">答案选项</legend>
            {["A", "B", "C", "D"].map(letter => (
                <label key={letter} className="workspace-answer-option"
                    data-correct={String(correctOption === letter)}>
                    <input type="radio" name={name} value={letter}
                        checked={selected === letter} onChange={() => onChange(letter)} />
                    <span>{letter}</span>
                    <span>{question["option" + letter] ?? ""}</span>
                </label>
            ))}
        </fieldset>
    );
}

export function ConfidenceOptions({ name, value, disabled, onChange }) {
    return (
        <fieldset className="workspace-options" disabled={disabled}>
            <legend>这道题，你有多确定？</legend>
            <div className="workspace-option-row">
                {[["UNSURE", "不太确定"], ["SOMEWHAT_SURE", "有些把握"], ["VERY_SURE", "很有把握"]].map(([option, label]) => (
                    <label key={option}>
                        <input type="radio" name={name} value={option}
                            checked={value === option} onChange={() => onChange(option)} />
                        {label}
                    </label>
                ))}
            </div>
        </fieldset>
    );
}
